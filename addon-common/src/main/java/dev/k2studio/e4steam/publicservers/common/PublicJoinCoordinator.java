package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.DirectoryClientException;
import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import link.e4steam.api.Registration;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Generation-safe join state machine. A stale callback cannot advance a replacement attempt. */
public final class PublicJoinCoordinator implements Registration {
    public enum State {
        IDLE, RESOLVING, JOINING_STEAM_TARGET, AUTHENTICATING, CONNECTING_MINECRAFT,
        ACTIVE, CANCELLED, STALE, FULL, INCOMPATIBLE, DENIED, TARGET_LOST,
        REGISTRY_UNAVAILABLE, FAILED, CLOSED
    }

    public record Snapshot(long generation, String listingId, State state, String detailCode) {
        public Snapshot {
            if (generation < 0L || listingId == null || state == null) throw new IllegalArgumentException("invalid join snapshot");
            detailCode = detailCode == null ? "" : detailCode;
            if (detailCode.length() > 128) throw new IllegalArgumentException("detailCode too long");
        }
    }

    private final PublicDirectoryClient client;
    private final CoreDirectoryGateway core;
    private final CompatibilityEvaluator evaluator;
    private final ClientEnvironment environment;
    private final PublicServersConfig config;
    private final Clock clock;
    private final Runnable changed;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
            new Snapshot(0L, "", State.IDLE, ""));
    private volatile String coreOperationId = "";

    public PublicJoinCoordinator(PublicDirectoryClient client, CoreDirectoryGateway core,
                                 CompatibilityEvaluator evaluator, ClientEnvironment environment,
                                 PublicServersConfig config, Clock clock, Runnable changed) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.core = java.util.Objects.requireNonNull(core, "core");
        this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.changed = changed == null ? () -> { } : changed;
    }

    /** Begins one attempt; rapid repeated calls return the already-active immutable snapshot. */
    public CompletionStage<Snapshot> join(String listingId, PublicServerType type) {
        if (closed.get()) return CompletableFuture.completedFuture(snapshot.get());
        if (!active.compareAndSet(false, true)) return CompletableFuture.completedFuture(snapshot.get());
        long expectedGeneration = generation.incrementAndGet();
        coreOperationId = "";
        update(expectedGeneration, listingId, State.RESOLVING, "");
        return client.findListing(listingId, type, config.pageSize(), config.maximumPages())
                .thenCompose(entry -> resolve(expectedGeneration, listingId, entry))
                .handle((value, failure) -> failure == null ? value
                        : failed(expectedGeneration, listingId, unwrap(failure)));
    }

    public CompletionStage<Snapshot> cancel() {
        if (closed.get()) return CompletableFuture.completedFuture(snapshot.get());
        String operation = coreOperationId;
        long cancelledGeneration = generation.incrementAndGet();
        active.set(false);
        coreOperationId = "";
        Snapshot current = snapshot.get();
        update(cancelledGeneration, current.listingId(), State.CANCELLED, "");
        if (operation.isEmpty()) return CompletableFuture.completedFuture(snapshot.get());
        return core.cancel(operation).handle((ignored, failure) -> snapshot.get());
    }

    /** Returns current state and polls only the non-blocking public core snapshot when active. */
    public Snapshot snapshot() {
        String operation = coreOperationId;
        Snapshot current = snapshot.get();
        if (operation.isEmpty() || !active.get() || closed.get()) return current;
        try {
            CoreDirectoryGateway.JoinOperation coreSnapshot = core.joinSnapshot(operation);
            if (operation.equals(coreOperationId) && generation.get() == current.generation()) {
                applyCore(current.generation(), current.listingId(), coreSnapshot);
            }
        } catch (RuntimeException failure) {
            // A temporary snapshot failure does not discard an in-flight core operation.
        }
        return snapshot.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        String operation = coreOperationId;
        generation.incrementAndGet();
        active.set(false);
        coreOperationId = "";
        if (!operation.isEmpty()) core.cancel(operation);
        Snapshot current = snapshot.get();
        snapshot.set(new Snapshot(generation.get(), current.listingId(), State.CLOSED, ""));
    }

    @Override public boolean isClosed() { return closed.get(); }

    private CompletionStage<Snapshot> resolve(long expectedGeneration, String listingId,
                                               Optional<PublicServerEntry> result) {
        if (!current(expectedGeneration)) return CompletableFuture.completedFuture(snapshot.get());
        if (result.isEmpty()) return CompletableFuture.completedFuture(
                terminal(expectedGeneration, listingId, State.STALE, "listing_missing"));
        PublicServerEntry entry = result.orElseThrow();
        Compatibility compatibility = evaluator.evaluate(entry, environment, clock.instant());
        if (compatibility.reason() == Compatibility.Reason.STALE) {
            return CompletableFuture.completedFuture(terminal(expectedGeneration, listingId, State.STALE, "listing_stale"));
        }
        if (compatibility.reason() == Compatibility.Reason.FULL) {
            return CompletableFuture.completedFuture(terminal(expectedGeneration, listingId, State.FULL, "server_full"));
        }
        if (!compatibility.compatible()) {
            return CompletableFuture.completedFuture(terminal(expectedGeneration, listingId,
                    State.INCOMPATIBLE, compatibility.reason().name().toLowerCase(java.util.Locale.ROOT)));
        }
        update(expectedGeneration, listingId, State.JOINING_STEAM_TARGET, "");
        return client.createJoinHandle(new JoinHandleRequest(listingId)).thenCompose(handle -> {
            if (!current(expectedGeneration)) return CompletableFuture.completedFuture(snapshot.get());
            if (handle.expired(clock.instant())) return CompletableFuture.completedFuture(
                    terminal(expectedGeneration, listingId, State.STALE, "join_handle_expired"));
            return core.join(handle.targetRef(), handle.handleId()).thenApply(operation -> {
                if (!current(expectedGeneration)) {
                    core.cancel(operation.id());
                    return snapshot.get();
                }
                coreOperationId = operation.id();
                applyCore(expectedGeneration, listingId, operation);
                return snapshot.get();
            });
        });
    }

    private void applyCore(long expectedGeneration, String listingId,
                           CoreDirectoryGateway.JoinOperation operation) {
        State state = State.valueOf(operation.state().name());
        update(expectedGeneration, listingId, state, operation.detailCode());
        if (terminal(state)) {
            active.set(false);
            coreOperationId = "";
        }
    }

    private Snapshot failed(long expectedGeneration, String listingId, Throwable failure) {
        if (!current(expectedGeneration)) return snapshot.get();
        if (failure instanceof DirectoryClientException directory) {
            State state = directory.category() == DirectoryClientException.Category.NOT_FOUND
                    ? State.STALE : State.REGISTRY_UNAVAILABLE;
            return terminal(expectedGeneration, listingId, state,
                    directory.category().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (failure instanceof CoreDirectoryException coreFailure) {
            State state = switch (coreFailure.code()) {
                case CAPABILITY_DENIED, SECURITY_REJECTION -> State.DENIED;
                case STALE_HANDLE, CANCELLED -> State.STALE;
                case TRANSPORT_UNAVAILABLE, UNAVAILABLE -> State.TARGET_LOST;
                case INCOMPATIBLE_VERSION -> State.INCOMPATIBLE;
                default -> State.FAILED;
            };
            return terminal(expectedGeneration, listingId, state,
                    coreFailure.code().name().toLowerCase(java.util.Locale.ROOT));
        }
        return terminal(expectedGeneration, listingId, State.FAILED, "unexpected_failure");
    }

    private Snapshot terminal(long expectedGeneration, String listingId, State state, String detail) {
        active.set(false);
        coreOperationId = "";
        update(expectedGeneration, listingId, state, detail);
        return snapshot.get();
    }

    private void update(long expectedGeneration, String listingId, State state, String detail) {
        if (closed.get() || generation.get() != expectedGeneration) return;
        snapshot.set(new Snapshot(expectedGeneration, listingId, state, detail));
        try { changed.run(); } catch (RuntimeException ignored) { }
    }

    private boolean current(long expectedGeneration) {
        return !closed.get() && generation.get() == expectedGeneration;
    }

    private static boolean terminal(State state) {
        return switch (state) {
            case ACTIVE, CANCELLED, STALE, FULL, INCOMPATIBLE, DENIED, TARGET_LOST,
                    REGISTRY_UNAVAILABLE, FAILED, CLOSED -> true;
            default -> false;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while (result instanceof CompletionException && result.getCause() != null) result = result.getCause();
        return result;
    }
}
