package dev.k2studio.e4steam.publicservers.client;

import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.DirectoryProtocol;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryJsonCodec;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ErrorResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.PublicationLease;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ReportRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Strict Java HTTP client for the versioned public-directory registry. */
public final class HttpPublicDirectoryClient implements PublicDirectoryClient {
    /** Production permits HTTPS only; development additionally permits loopback HTTP. */
    public enum TransportPolicy { HTTPS_ONLY, ALLOW_LOOPBACK_HTTP }

    private static final String JSON = "application/json";
    private static final int ERROR_BODY_LIMIT = 8 * 1024;

    private final URI origin;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ThreadPoolExecutor workers;
    private final Set<CompletableFuture<?>> calls = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a production client with a bounded executor and no redirects. */
    public HttpPublicDirectoryClient(URI origin, Duration connectTimeout, Duration requestTimeout,
                                     int workerCount, int queueCapacity) {
        this(origin, connectTimeout, requestTimeout, workerCount, queueCapacity,
                TransportPolicy.HTTPS_ONLY);
    }

    /** Creates a client with an explicit transport policy; loopback HTTP is test/dev-only. */
    public HttpPublicDirectoryClient(URI origin, Duration connectTimeout, Duration requestTimeout,
                                     int workerCount, int queueCapacity, TransportPolicy policy) {
        this.origin = validateOrigin(origin, policy);
        this.requestTimeout = boundedDuration(requestTimeout, "requestTimeout", 1L, 30L);
        Duration checkedConnect = boundedDuration(connectTimeout, "connectTimeout", 1L, 15L);
        if (workerCount < 1 || workerCount > 8) throw new IllegalArgumentException("invalid workerCount");
        if (queueCapacity < 1 || queueCapacity > 256) throw new IllegalArgumentException("invalid queueCapacity");
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ClientThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.http = HttpClient.newBuilder()
                .connectTimeout(checkedConnect)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public URI origin() { return origin; }

    @Override
    public CompletableFuture<ChallengeResponse> requestChallenge(ChallengeRequest request) {
        return json("POST", "/v1/challenges", request, 201, ChallengeResponse.class);
    }

    @Override
    public CompletableFuture<PublicationLease> createPublication(CreatePublicationRequest request) {
        return json("POST", "/v1/publications", request, 201, PublicationLease.class);
    }

    @Override
    public CompletableFuture<PublicationLease> heartbeat(String listingId, LeaseMutationRequest request) {
        requireNoMetadata(request, "heartbeat");
        return json("POST", publicationPath(listingId) + "/heartbeat", request, 200,
                PublicationLease.class);
    }

    @Override
    public CompletableFuture<PublicationLease> updatePublication(String listingId,
                                                                  LeaseMutationRequest request) {
        if (request == null || request.metadata() == null) {
            throw new IllegalArgumentException("update requires metadata");
        }
        return json("PUT", publicationPath(listingId), request, 200, PublicationLease.class);
    }

    @Override
    public CompletableFuture<Void> deletePublication(String listingId, LeaseMutationRequest request) {
        requireNoMetadata(request, "delete");
        return empty("DELETE", publicationPath(listingId), request, 204);
    }

    @Override
    public CompletableFuture<DirectoryPage> catalog(int limit, String cursor, PublicServerType type) {
        if (limit < 1 || limit > DirectoryProtocol.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid catalog limit");
        }
        StringBuilder path = new StringBuilder("/v1/catalog?limit=").append(limit);
        if (cursor != null && !cursor.isBlank()) path.append("&cursor=").append(query(cursor));
        if (type != null) path.append("&type=").append(type.name());
        return json("GET", path.toString(), null, 200, DirectoryPage.class);
    }

    @Override
    public CompletableFuture<JoinHandleResponse> createJoinHandle(JoinHandleRequest request) {
        return json("POST", "/v1/join-handles", request, 201, JoinHandleResponse.class);
    }

    @Override
    public CompletableFuture<Void> report(ReportRequest request) {
        return empty("POST", "/v1/reports", request, 202);
    }

    @Override
    public boolean isClosed() { return closed.get(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (CompletableFuture<?> call : calls) call.cancel(true);
        calls.clear();
        workers.shutdownNow();
        closeHttpClientIfSupported();
    }

    /**
     * {@link HttpClient} only became {@code AutoCloseable} in newer JDKs.
     * Minecraft 1.17-era clients run Java 16, where shutting down our owned
     * executor is sufficient; newer runtimes additionally release the
     * client's internal resources through its close method.
     */
    private void closeHttpClientIfSupported() {
        try {
            http.getClass().getMethod("close").invoke(http);
        } catch (NoSuchMethodException ignored) {
            // Java 11-20 HttpClient has no explicit close operation.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Closing is best effort after all calls and owned workers stopped.
        }
    }

    private <T> CompletableFuture<T> json(String method, String path, Object request,
                                           int expectedStatus, Class<T> responseType) {
        return submit(() -> {
            HttpResponse<InputStream> response = send(method, path, request);
            byte[] body = readBody(response.body(), DirectoryProtocol.MAX_RESPONSE_BYTES);
            if (response.statusCode() != expectedStatus) throw failure(response, body);
            requireJson(response);
            try {
                return RegistryJsonCodec.decode(body, responseType);
            } catch (RuntimeException failure) {
                throw new DirectoryClientException(DirectoryClientException.Category.PROTOCOL,
                        response.statusCode(), false, 0L, requestId(response), failure);
            }
        });
    }

    private CompletableFuture<Void> empty(String method, String path, Object request,
                                           int expectedStatus) {
        return submit(() -> {
            HttpResponse<InputStream> response = send(method, path, request);
            byte[] body = readBody(response.body(), ERROR_BODY_LIMIT);
            if (response.statusCode() != expectedStatus) throw failure(response, body);
            if (body.length != 0) {
                throw new DirectoryClientException(DirectoryClientException.Category.PROTOCOL,
                        response.statusCode(), false, 0L, requestId(response));
            }
            return null;
        });
    }

    private HttpResponse<InputStream> send(String method, String path, Object request) {
        byte[] body = request == null ? new byte[0] : RegistryJsonCodec.encode(request);
        if (body.length > DirectoryProtocol.MAX_REQUEST_BYTES) {
            throw new DirectoryClientException(DirectoryClientException.Category.TOO_LARGE,
                    0, false, 0L, "");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("User-Agent", "e4steam-public-servers/0.1");
        if (request == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", JSON);
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException failure) {
            throw new DirectoryClientException(DirectoryClientException.Category.TIMEOUT,
                    0, true, 0L, "", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DirectoryClientException(closed.get()
                    ? DirectoryClientException.Category.CLOSED
                    : DirectoryClientException.Category.NETWORK, 0, !closed.get(), 0L, "", failure);
        } catch (IOException failure) {
            throw new DirectoryClientException(DirectoryClientException.Category.NETWORK,
                    0, true, 0L, "", failure);
        }
    }

    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        if (closed.get()) return CompletableFuture.failedFuture(closedFailure());
        CompletableFuture<T> result = new CompletableFuture<>();
        calls.add(result);
        result.whenComplete((ignored, failure) -> calls.remove(result));
        try {
            workers.execute(() -> {
                if (closed.get()) {
                    result.completeExceptionally(closedFailure());
                    return;
                }
                try {
                    result.complete(operation.get());
                } catch (Throwable failure) {
                    if (failure instanceof Error error) throw error;
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            calls.remove(result);
            result.completeExceptionally(new DirectoryClientException(
                    closed.get() ? DirectoryClientException.Category.CLOSED
                            : DirectoryClientException.Category.QUEUE_FULL,
                    0, !closed.get(), 0L, "", failure));
        }
        return result;
    }

    private static DirectoryClientException failure(HttpResponse<?> response, byte[] body) {
        int status = response.statusCode();
        boolean retryable = status == 429 || status == 503;
        if (body.length != 0 && body.length <= ERROR_BODY_LIMIT) {
            try {
                ErrorResponse error = RegistryJsonCodec.decode(body, ErrorResponse.class);
                retryable = error.retryable();
            } catch (RuntimeException ignored) {
                // Invalid remote errors remain a sanitized status-based failure.
            }
        }
        return new DirectoryClientException(category(status), status, retryable,
                retryAfter(response), requestId(response));
    }

    private static DirectoryClientException.Category category(int status) {
        return switch (status) {
            case 400 -> DirectoryClientException.Category.BAD_REQUEST;
            case 401, 403 -> DirectoryClientException.Category.AUTHENTICATION;
            case 404 -> DirectoryClientException.Category.NOT_FOUND;
            case 409 -> DirectoryClientException.Category.CONFLICT;
            case 413 -> DirectoryClientException.Category.TOO_LARGE;
            case 429 -> DirectoryClientException.Category.RATE_LIMITED;
            case 503 -> DirectoryClientException.Category.UNAVAILABLE;
            default -> status >= 300 && status < 400
                    ? DirectoryClientException.Category.PROTOCOL
                    : DirectoryClientException.Category.SERVICE;
        };
    }

    private static byte[] readBody(InputStream input, int limit) {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                total += count;
                if (total > limit) {
                    throw new DirectoryClientException(DirectoryClientException.Category.TOO_LARGE,
                            0, false, 0L, "");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new DirectoryClientException(DirectoryClientException.Category.NETWORK,
                    0, true, 0L, "", failure);
        }
    }

    private static void requireJson(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        if (!JSON.equalsIgnoreCase(mediaType)) {
            throw new DirectoryClientException(DirectoryClientException.Category.PROTOCOL,
                    response.statusCode(), false, 0L, requestId(response));
        }
    }

    private URI resolve(String path) {
        return URI.create(origin.toASCIIString() + path);
    }

    private static String publicationPath(String listingId) {
        if (listingId == null || listingId.isBlank() || listingId.length() > 96) {
            throw new IllegalArgumentException("invalid listingId");
        }
        return "/v1/publications/" + pathSegment(listingId);
    }

    private static String pathSegment(String value) { return query(value); }

    private static String query(String value) {
        if (value == null || value.length() > 256) throw new IllegalArgumentException("query value too long");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireNoMetadata(LeaseMutationRequest request, String operation) {
        if (request == null || request.metadata() != null) {
            throw new IllegalArgumentException(operation + " must not include metadata");
        }
    }

    private static URI validateOrigin(URI value, TransportPolicy policy) {
        if (value == null || policy == null) throw new NullPointerException("origin");
        String scheme = value.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean loopbackHttp = policy == TransportPolicy.ALLOW_LOOPBACK_HTTP
                && "http".equalsIgnoreCase(scheme) && loopback(value.getHost());
        if ((!https && !loopbackHttp) || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || (value.getPath() != null && !value.getPath().isEmpty() && !"/".equals(value.getPath()))) {
            throw new IllegalArgumentException("registry endpoint must be an HTTPS origin");
        }
        String normalizedScheme = https ? "https" : "http";
        return URI.create(normalizedScheme + "://" + host(value.getHost())
                + (value.getPort() < 0 ? "" : ":" + value.getPort()));
    }

    private static boolean loopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1"));
    }

    private static String host(String host) {
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.indexOf(':') >= 0 ? '[' + lower + ']' : lower;
    }

    private static Duration boundedDuration(Duration value, String field, long minimum, long maximum) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofSeconds(minimum)) < 0
                || value.compareTo(Duration.ofSeconds(maximum)) > 0) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return value;
    }

    private static long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return Math.min(3_600L, Math.max(0L, Long.parseLong(value))); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue("X-Request-ID").orElse("");
    }

    private static DirectoryClientException closedFailure() {
        return new DirectoryClientException(DirectoryClientException.Category.CLOSED,
                0, false, 0L, "");
    }

    private static final class ClientThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task,
                    "e4steam-public-directory-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> { });
            return thread;
        }
    }
}
