package dev.k2studio.e4steam.publicservers.registry;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.k2studio.e4steam.publicservers.contract.DirectoryProtocol;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryJsonCodec;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ErrorResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ReportRequest;
import dev.k2studio.e4steam.publicservers.contract.StrictJson;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.ModerationAction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded local HTTP adapter. Public TLS is expected to terminate at a loopback reverse proxy. */
public final class RegistryHttpServer implements AutoCloseable {
    private static final int BACKLOG = 64;
    private static final int MAX_QUERY_BYTES = 1_024;
    private static final int MAX_LOCAL_RESPONSE_BYTES = 8 * 1_024;

    private final HttpServer server;
    private final RegistryService service;
    private final byte[] moderatorTokenHash;
    private final byte[] privacyKey;
    private final Duration requestTimeout;
    private final ThreadPoolExecutor dispatchExecutor;
    private final ThreadPoolExecutor requestExecutor;
    private final ScheduledThreadPoolExecutor cleanupExecutor;
    private final BoundedRateLimiter rateLimiter = new BoundedRateLimiter(8_192);
    private final AtomicBoolean closed = new AtomicBoolean();

    public RegistryHttpServer(InetSocketAddress address, RegistryService service,
                              byte[] moderatorTokenHash, byte[] privacyKey,
                              int workers, int queueCapacity, Duration requestTimeout,
                              Duration cleanupInterval) throws IOException {
        Objects.requireNonNull(address, "address");
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("HTTP server must bind to loopback");
        }
        this.service = Objects.requireNonNull(service, "service");
        this.moderatorTokenHash = cloneOrNull(moderatorTokenHash);
        if (privacyKey == null || privacyKey.length < 32) throw new IllegalArgumentException("privacy key too short");
        this.privacyKey = privacyKey.clone();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (workers < 2 || queueCapacity < workers) throw new IllegalArgumentException("invalid executor bounds");
        dispatchExecutor = boundedExecutor(workers, queueCapacity, "registry-http-dispatch");
        requestExecutor = boundedExecutor(workers, queueCapacity, "registry-http-worker");
        cleanupExecutor = new ScheduledThreadPoolExecutor(1,
                namedFactory("registry-cleanup", true), new ThreadPoolExecutor.DiscardPolicy());
        cleanupExecutor.setRemoveOnCancelPolicy(true);
        server = HttpServer.create(address, BACKLOG);
        server.setExecutor(dispatchExecutor);
        server.createContext("/", this::dispatch);
        long cleanupMillis = cleanupInterval.toMillis();
        if (cleanupMillis < 1_000 || cleanupMillis > Duration.ofHours(1).toMillis()) {
            throw new IllegalArgumentException("invalid cleanup interval");
        }
        cleanupExecutor.scheduleWithFixedDelay(this::safeCleanup,
                cleanupMillis, cleanupMillis, TimeUnit.MILLISECONDS);
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("server is closed");
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    private void dispatch(HttpExchange exchange) {
        ResponseGate gate = new ResponseGate();
        Future<?> future;
        try {
            future = requestExecutor.submit(() -> handle(exchange, gate));
        } catch (RejectedExecutionException overloaded) {
            safeError(exchange, gate, RegistryException.unavailable());
            exchange.close();
            return;
        }
        try {
            future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            safeError(exchange, gate, RegistryException.timeout());
            exchange.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            safeError(exchange, gate, RegistryException.unavailable());
            exchange.close();
        } catch (ExecutionException unexpected) {
            safeError(exchange, gate, RegistryException.unavailable());
            exchange.close();
        }
    }

    private void handle(HttpExchange exchange, ResponseGate gate) {
        try {
            route(exchange, gate);
        } catch (RegistryException safe) {
            safeError(exchange, gate, safe);
        } catch (IllegalArgumentException malformed) {
            safeError(exchange, gate, RegistryException.badRequest());
        } catch (RuntimeException unavailable) {
            safeError(exchange, gate, RegistryException.unavailable());
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, ResponseGate gate) {
        String path = exchange.getRequestURI().getRawPath();
        if (path == null || path.length() > 512 || path.indexOf('%') >= 0) {
            throw RegistryException.badRequest();
        }
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String clientKey = clientKey(exchange);

        if ("/v1/health".equals(path)) {
            requireMethod(method, "GET");
            limit(RatePolicy.HEALTH, clientKey);
            RegistryService.Health health = service.health();
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("status", health.storeReady() ? "ok" : "degraded");
            body.put("storeReady", health.storeReady());
            body.put("attestationReady", health.attestationReady());
            body.put("directoryProtocol", health.directoryProtocol());
            body.put("metadataSchema", health.metadataSchema());
            send(exchange, gate, health.storeReady() ? 200 : 503,
                    StrictJson.write(body, MAX_LOCAL_RESPONSE_BYTES), "application/json; charset=utf-8");
            return;
        }
        if ("/v1/challenges".equals(path)) {
            requireMethod(method, "POST");
            limit(RatePolicy.CHALLENGE, clientKey);
            ChallengeRequest request = decode(exchange, ChallengeRequest.class);
            sendJson(exchange, gate, 201, service.issueChallenge(request));
            return;
        }
        if ("/v1/attestations".equals(path)) {
            requireMethod(method, "POST");
            limit(RatePolicy.ATTESTATION, clientKey);
            Map<String, Object> request = localObject(readJsonBody(exchange),
                    Set.of("challengeId", "assertion"));
            sendJson(exchange, gate, 201, service.attest(
                    StrictJson.string(request, "challengeId"), StrictJson.string(request, "assertion")));
            return;
        }
        if ("/v1/publications".equals(path)) {
            requireMethod(method, "POST");
            limit(RatePolicy.PUBLICATION, clientKey);
            sendJson(exchange, gate, 201,
                    service.createPublication(decode(exchange, CreatePublicationRequest.class)));
            return;
        }
        if (path.startsWith("/v1/publications/")) {
            publicationRoute(exchange, gate, path, method, clientKey);
            return;
        }
        if ("/v1/catalog".equals(path)) {
            requireMethod(method, "GET");
            limit(RatePolicy.CATALOG, clientKey);
            Map<String, String> query = query(exchange);
            rejectUnknown(query, Set.of("limit", "cursor", "type"));
            int pageLimit = integer(query.get("limit"), 20, 1, DirectoryProtocol.MAX_PAGE_SIZE);
            PublicServerType type = query.containsKey("type")
                    ? enumValue(PublicServerType.class, query.get("type")) : null;
            sendJson(exchange, gate, 200, service.catalog(query.get("cursor"), pageLimit, type));
            return;
        }
        if ("/v1/join-handles".equals(path)) {
            requireMethod(method, "POST");
            limit(RatePolicy.JOIN_HANDLE, clientKey);
            sendJson(exchange, gate, 201,
                    service.issueJoinHandle(decode(exchange, JoinHandleRequest.class)));
            return;
        }
        if ("/v1/reports".equals(path)) {
            requireMethod(method, "POST");
            limit(RatePolicy.REPORT, clientKey);
            service.report(decode(exchange, ReportRequest.class),
                    SecureTokens.hmac(privacyKey, "reporter-v1\n" + clientKey));
            sendEmpty(exchange, gate, 202);
            return;
        }
        if ("/v1/moderation/actions".equals(path)) {
            requireMethod(method, "POST");
            limit(RatePolicy.MODERATION, clientKey);
            authorizeModerator(exchange.getRequestHeaders());
            Map<String, Object> request = localObject(readJsonBody(exchange),
                    Set.of("action", "listingId", "reason"));
            ModerationAction action = enumValue(ModerationAction.class,
                    StrictJson.string(request, "action"));
            service.moderate(StrictJson.string(request, "listingId"), action,
                    StrictJson.string(request, "reason"), "local.moderator");
            sendEmpty(exchange, gate, 204);
            return;
        }
        throw RegistryException.notFound();
    }

    private void publicationRoute(HttpExchange exchange, ResponseGate gate, String path,
                                  String method, String clientKey) {
        String suffix = path.substring("/v1/publications/".length());
        boolean heartbeat = suffix.endsWith("/heartbeat");
        String listingId = heartbeat ? suffix.substring(0, suffix.length() - "/heartbeat".length()) : suffix;
        if (!listingId.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,95}")) {
            throw RegistryException.notFound();
        }
        limit(RatePolicy.MUTATION, clientKey);
        if (heartbeat) {
            requireMethod(method, "POST");
            sendJson(exchange, gate, 200,
                    service.heartbeat(listingId, decode(exchange, LeaseMutationRequest.class)));
            return;
        }
        if ("PUT".equals(method)) {
            sendJson(exchange, gate, 200,
                    service.update(listingId, decode(exchange, LeaseMutationRequest.class)));
            return;
        }
        if ("DELETE".equals(method)) {
            service.delete(listingId, decode(exchange, LeaseMutationRequest.class));
            sendEmpty(exchange, gate, 204);
            return;
        }
        throw RegistryException.methodNotAllowed();
    }

    private <T> T decode(HttpExchange exchange, Class<T> type) {
        return RegistryJsonCodec.decode(readJsonBody(exchange), type);
    }

    private byte[] readJsonBody(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        List<String> contentTypes = headers.get("Content-Type");
        if (contentTypes == null || contentTypes.size() != 1
                || !contentTypes.getFirst().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw RegistryException.badRequest();
        }
        String contentEncoding = headers.getFirst("Content-Encoding");
        if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding)) {
            throw RegistryException.badRequest();
        }
        String contentLength = headers.getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length < 1) throw RegistryException.badRequest();
                if (length > DirectoryProtocol.MAX_REQUEST_BYTES) throw RegistryException.tooLarge();
            } catch (NumberFormatException malformed) {
                throw RegistryException.badRequest();
            }
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream(4_096)) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > DirectoryProtocol.MAX_REQUEST_BYTES) throw RegistryException.tooLarge();
                output.write(buffer, 0, read);
            }
            if (total == 0) throw RegistryException.badRequest();
            return output.toByteArray();
        } catch (IOException failure) {
            throw RegistryException.badRequest();
        }
    }

    private static Map<String, Object> localObject(byte[] bytes, Set<String> expected) {
        Map<String, Object> object = StrictJson.object(
                StrictJson.parse(bytes, DirectoryProtocol.MAX_REQUEST_BYTES), "root");
        if (!object.keySet().equals(expected)) throw RegistryException.badRequest();
        return object;
    }

    private void authorizeModerator(Headers headers) {
        if (moderatorTokenHash == null) throw RegistryException.forbidden();
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) throw RegistryException.forbidden();
        String value = values.getFirst();
        if (!value.startsWith("Bearer ") || value.length() < 40 || value.length() > 520) {
            throw RegistryException.forbidden();
        }
        String credential = value.substring("Bearer ".length());
        if (!SecureTokens.equal(moderatorTokenHash, SecureTokens.sha256(credential))) {
            throw RegistryException.forbidden();
        }
    }

    private void limit(RatePolicy policy, String clientKey) {
        Instant now = Instant.now();
        BoundedRateLimiter.Decision global = rateLimiter.acquire(
                policy.scope + ".global", "all", policy.globalLimit, policy.window, now);
        if (!global.allowed()) throw RegistryException.rateLimited(global.retryAfterSeconds());
        BoundedRateLimiter.Decision client = rateLimiter.acquire(
                policy.scope + ".client", clientKey, policy.clientLimit, policy.window, now);
        if (!client.allowed()) throw RegistryException.rateLimited(client.retryAfterSeconds());
    }

    private String clientKey(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        String address = remote == null || remote.getAddress() == null
                ? "unknown" : remote.getAddress().getHostAddress();
        return SecureTokens.base64(SecureTokens.hmac(privacyKey, "client-v1\n" + address));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.length() > MAX_QUERY_BYTES) throw RegistryException.badRequest();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator != pair.lastIndexOf('=')) throw RegistryException.badRequest();
            String key = decodeQuery(pair.substring(0, separator));
            String value = decodeQuery(pair.substring(separator + 1));
            if (value.isEmpty() || result.putIfAbsent(key, value) != null) {
                throw RegistryException.badRequest();
            }
        }
        return Map.copyOf(result);
    }

    private static String decodeQuery(String value) {
        try {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (!decoded.matches("[A-Za-z0-9._:+/-]{1,256}")) throw RegistryException.badRequest();
            return decoded;
        } catch (IllegalArgumentException malformed) {
            throw RegistryException.badRequest();
        }
    }

    private static void rejectUnknown(Map<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet())) throw RegistryException.badRequest();
    }

    private static int integer(String raw, int defaultValue, int minimum, int maximum) {
        if (raw == null) return defaultValue;
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw RegistryException.badRequest();
            return value;
        } catch (NumberFormatException malformed) {
            throw RegistryException.badRequest();
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException malformed) {
            throw RegistryException.badRequest();
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) throw RegistryException.methodNotAllowed();
    }

    private static void sendJson(HttpExchange exchange, ResponseGate gate, int status, Object value) {
        send(exchange, gate, status, RegistryJsonCodec.encode(value), "application/json; charset=utf-8");
    }

    private static void sendEmpty(HttpExchange exchange, ResponseGate gate, int status) {
        send(exchange, gate, status, null, null);
    }

    private static void safeError(HttpExchange exchange, ResponseGate gate, RegistryException failure) {
        try {
            if (failure.retryAfterSeconds() > 0) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(failure.retryAfterSeconds()));
            }
            sendJson(exchange, gate, failure.status(),
                    new ErrorResponse(failure.code(), failure.messageKey(), failure.retryable()));
        } catch (RuntimeException ignored) {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, ResponseGate gate, int status, byte[] body,
                             String contentType) {
        if (!gate.claim()) return;
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Request-Id", SecureTokens.random("req.", 16));
            if (contentType != null) headers.set("Content-Type", contentType);
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            if (body.length > DirectoryProtocol.MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("response too large");
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (IOException failure) {
            exchange.close();
        }
    }

    private void safeCleanup() {
        if (closed.get()) return;
        try {
            service.purgeExpired();
        } catch (RuntimeException ignored) {
            /* Health reports store readiness; no exception details or credentials are logged. */
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        cleanupExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        dispatchExecutor.shutdownNow();
    }

    private static ThreadPoolExecutor boundedExecutor(int workers, int queueCapacity, String name) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), namedFactory(name, false),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory namedFactory(String prefix, boolean daemon) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + '-' + sequence.incrementAndGet());
            thread.setDaemon(daemon);
            thread.setUncaughtExceptionHandler((ignoredThread, ignoredFailure) -> { });
            return thread;
        };
    }

    private static byte[] cloneOrNull(byte[] value) {
        return value == null ? null : value.clone();
    }

    private enum RatePolicy {
        HEALTH("health", 120, 2_000, Duration.ofMinutes(1)),
        CHALLENGE("challenge", 20, 400, Duration.ofMinutes(1)),
        ATTESTATION("attestation", 20, 400, Duration.ofMinutes(1)),
        PUBLICATION("publication", 10, 200, Duration.ofMinutes(1)),
        MUTATION("mutation", 120, 2_000, Duration.ofMinutes(1)),
        CATALOG("catalog", 120, 4_000, Duration.ofMinutes(1)),
        JOIN_HANDLE("join", 60, 2_000, Duration.ofMinutes(1)),
        REPORT("report", 5, 500, Duration.ofHours(1)),
        MODERATION("moderation", 30, 300, Duration.ofMinutes(1));

        private final String scope;
        private final int clientLimit;
        private final int globalLimit;
        private final Duration window;

        RatePolicy(String scope, int clientLimit, int globalLimit, Duration window) {
            this.scope = scope;
            this.clientLimit = clientLimit;
            this.globalLimit = globalLimit;
            this.window = window;
        }
    }

    private static final class ResponseGate {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private boolean claim() { return claimed.compareAndSet(false, true); }
    }
}
