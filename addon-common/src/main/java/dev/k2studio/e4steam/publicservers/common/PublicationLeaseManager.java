package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.DirectoryClientException;
import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.PublicationLease;
import link.e4steam.api.Registration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one generation-bound publication lease, heartbeat and best-effort unpublication. */
public final class PublicationLeaseManager implements Registration {
    public enum State { IDLE, ATTESTING, CREATING, ACTIVE, UPDATING, UNPUBLISHING, FAILED, CLOSED }

    public record Snapshot(long generation, PublicServerType type, State state, String listingId,
                           long revision, Instant expiresAt, String errorCategory) {
        public Snapshot {
            if (generation < 0L || type == null || state == null || listingId == null
                    || expiresAt == null || errorCategory == null) throw new IllegalArgumentException("invalid lease snapshot");
        }
        @Override public String toString() {
            return "PublicationSnapshot{generation=" + generation + ",type=" + type
                    + ",state=" + state + ",listing=" + (listingId.isEmpty() ? "none" : "opaque")
                    + ",revision=" + revision + ",expires=" + expiresAt + '}';
        }
    }

    private static final int MAX_TRANSIENT_FAILURES = 3;
    private static final int MAX_STARTUP_RETRIES = 15;
    private static final long STARTUP_RETRY_DELAY_MILLIS = 1_000L;

    private final PublicServerType type;
    private final PublicDirectoryClient client;
    private final PublisherAttestationCoordinator attestation;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Runnable changed;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Snapshot> snapshot;
    private volatile PublicationLease lease;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile int consecutiveFailures;

    public PublicationLeaseManager(PublicServerType type, PublicDirectoryClient client,
                                   PublisherAttestationCoordinator attestation,
                                   ScheduledExecutorService scheduler, Clock clock, Runnable changed) {
        this.type = java.util.Objects.requireNonNull(type, "type");
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.attestation = java.util.Objects.requireNonNull(attestation, "attestation");
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.changed = changed == null ? () -> { } : changed;
        this.snapshot = new AtomicReference<>(emptySnapshot(0L, State.IDLE, ""));
    }

    /** Starts publication once; duplicate calls while creating/active are coalesced. */
    public synchronized CompletionStage<Snapshot> publish(ListingMetadata metadata) {
        if (closed.get()) return CompletableFuture.completedFuture(snapshot.get());
        State state = snapshot.get().state();
        if (state == State.ATTESTING || state == State.CREATING || state == State.ACTIVE
                || state == State.UPDATING || state == State.UNPUBLISHING) {
            return CompletableFuture.completedFuture(snapshot.get());
        }
        ListingMetadata checked = MetadataValidator.validate(type, metadata);
        long expectedGeneration = generation.incrementAndGet();
        lease = null;
        consecutiveFailures = 0;
        update(emptySnapshot(expectedGeneration, State.ATTESTING, ""));
        return publishAttempt(expectedGeneration, checked, 0);
    }

    private CompletionStage<Snapshot> publishAttempt(long expectedGeneration,
                                                     ListingMetadata metadata,
                                                     int retry) {
        if (!current(expectedGeneration)) {
            return CompletableFuture.completedFuture(snapshot.get());
        }
        return attestation.attest(type).thenCompose(attested -> {
            if (!current(expectedGeneration)) return CompletableFuture.completedFuture(snapshot.get());
            update(emptySnapshot(expectedGeneration, State.CREATING, ""));
            var request = new CreatePublicationRequest(type, metadata,
                    attested.target().targetRef(), attested.receipt().receiptRef());
            return client.createPublication(request).thenApply(created -> activate(expectedGeneration, created));
        }).handle((value, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(value);
            Throwable cause = unwrap(failure);
            if (retry < MAX_STARTUP_RETRIES && current(expectedGeneration)
                    && retryableStartupFailure(cause)) {
                update(emptySnapshot(expectedGeneration, State.ATTESTING, ""));
                CompletableFuture<Snapshot> delayed = new CompletableFuture<>();
                scheduler.schedule(() -> publishAttempt(expectedGeneration, metadata, retry + 1)
                                .whenComplete((next, nextFailure) -> {
                                    if (nextFailure == null) delayed.complete(next);
                                    else delayed.completeExceptionally(nextFailure);
                                }),
                        STARTUP_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                return delayed;
            }
            return CompletableFuture.completedFuture(fail(expectedGeneration, cause));
        }).thenCompose(stage -> stage);
    }

    public synchronized CompletionStage<Snapshot> updateMetadata(ListingMetadata metadata) {
        PublicationLease currentLease = lease;
        Snapshot currentSnapshot = snapshot.get();
        if (closed.get() || currentLease == null || currentSnapshot.state() != State.ACTIVE) {
            return CompletableFuture.completedFuture(currentSnapshot);
        }
        ListingMetadata checked = MetadataValidator.validate(type, metadata);
        long expectedGeneration = generation.get();
        update(new Snapshot(expectedGeneration, type, State.UPDATING,
                currentLease.entry().listingId(), currentLease.entry().revision(),
                currentLease.entry().expiresAt(), ""));
        var request = new LeaseMutationRequest(currentLease.leaseId(), currentLease.leaseProof(),
                currentLease.entry().revision(), checked);
        return client.updatePublication(currentLease.entry().listingId(), request)
                .thenApply(updated -> activate(expectedGeneration, updated))
                .exceptionally(failure -> fail(expectedGeneration, failure));
    }

    /** Clears local active state even when DELETE fails; backend TTL remains the final fail-safe. */
    public synchronized CompletionStage<Snapshot> unpublish(String safeReasonCode) {
        if (safeReasonCode == null || !safeReasonCode.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid unpublish reason");
        }
        PublicationLease oldLease = lease;
        long nextGeneration = generation.incrementAndGet();
        lease = null;
        cancelHeartbeat();
        consecutiveFailures = 0;
        if (closed.get() || oldLease == null) {
            update(emptySnapshot(nextGeneration, closed.get() ? State.CLOSED : State.IDLE, ""));
            return CompletableFuture.completedFuture(snapshot.get());
        }
        update(new Snapshot(nextGeneration, type, State.UNPUBLISHING,
                oldLease.entry().listingId(), oldLease.entry().revision(),
                oldLease.entry().expiresAt(), ""));
        var request = new LeaseMutationRequest(oldLease.leaseId(), oldLease.leaseProof(),
                oldLease.entry().revision(), null);
        return client.deletePublication(oldLease.entry().listingId(), request)
                .handle((ignored, failure) -> {
                    String error = failure == null ? "" : "ttl_cleanup_pending";
                    if (!closed.get() && generation.get() == nextGeneration) {
                        update(emptySnapshot(nextGeneration, State.IDLE, error));
                    }
                    return snapshot.get();
                });
    }

    public Snapshot snapshot() { return snapshot.get(); }

    public boolean active() { return snapshot.get().state() == State.ACTIVE; }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        PublicationLease oldLease = lease;
        long nextGeneration = generation.incrementAndGet();
        lease = null;
        cancelHeartbeat();
        if (oldLease != null && !client.isClosed()) {
            var request = new LeaseMutationRequest(oldLease.leaseId(), oldLease.leaseProof(),
                    oldLease.entry().revision(), null);
            client.deletePublication(oldLease.entry().listingId(), request);
        }
        update(emptySnapshot(nextGeneration, State.CLOSED, ""));
    }

    @Override public boolean isClosed() { return closed.get(); }

    private synchronized Snapshot activate(long expectedGeneration, PublicationLease value) {
        if (!current(expectedGeneration)) return snapshot.get();
        if (value.entry().type() != type || value.entry().stale(clock.instant())) {
            return fail(expectedGeneration, new IllegalStateException("registry returned an invalid lease"));
        }
        lease = value;
        consecutiveFailures = 0;
        Snapshot activeSnapshot = new Snapshot(expectedGeneration, type, State.ACTIVE,
                value.entry().listingId(), value.entry().revision(), value.entry().expiresAt(), "");
        update(activeSnapshot);
        scheduleHeartbeat(expectedGeneration, value.heartbeatAfterSeconds());
        return activeSnapshot;
    }

    private synchronized void runHeartbeat(long expectedGeneration) {
        PublicationLease currentLease = lease;
        if (!current(expectedGeneration) || currentLease == null
                || snapshot.get().state() != State.ACTIVE) return;
        var request = new LeaseMutationRequest(currentLease.leaseId(), currentLease.leaseProof(),
                currentLease.entry().revision(), null);
        client.heartbeat(currentLease.entry().listingId(), request).whenComplete((updated, failure) -> {
            synchronized (PublicationLeaseManager.this) {
                if (!current(expectedGeneration)) return;
                if (failure == null) {
                    activate(expectedGeneration, updated);
                    return;
                }
                consecutiveFailures++;
                Throwable cause = unwrap(failure);
                boolean retryable = cause instanceof DirectoryClientException directory && directory.retryable();
                if (retryable && consecutiveFailures <= MAX_TRANSIENT_FAILURES
                        && currentLease.entry().expiresAt().isAfter(clock.instant().plusSeconds(5))) {
                    scheduleHeartbeat(expectedGeneration, 5L);
                } else {
                    lease = null;
                    cancelHeartbeat();
                    update(emptySnapshot(expectedGeneration, State.FAILED, category(cause)));
                }
            }
        });
    }

    private synchronized void scheduleHeartbeat(long expectedGeneration, long delaySeconds) {
        cancelHeartbeat();
        if (!current(expectedGeneration)) return;
        long boundedDelay = Math.max(1L, Math.min(120L, delaySeconds));
        heartbeat = scheduler.schedule(() -> runHeartbeat(expectedGeneration),
                boundedDelay, TimeUnit.SECONDS);
    }

    private synchronized void cancelHeartbeat() {
        ScheduledFuture<?> old = heartbeat;
        heartbeat = null;
        if (old != null) old.cancel(false);
    }

    private Snapshot fail(long expectedGeneration, Throwable failure) {
        if (!current(expectedGeneration)) return snapshot.get();
        lease = null;
        cancelHeartbeat();
        Snapshot failed = emptySnapshot(expectedGeneration, State.FAILED, category(unwrap(failure)));
        update(failed);
        return failed;
    }

    private void update(Snapshot value) {
        snapshot.set(value);
        try { changed.run(); } catch (RuntimeException ignored) { }
    }

    private boolean current(long expectedGeneration) {
        return !closed.get() && generation.get() == expectedGeneration;
    }

    private Snapshot emptySnapshot(long value, State state, String error) {
        return new Snapshot(value, type, state, "", 0L, Instant.EPOCH, error);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while (value instanceof CompletionException && value.getCause() != null) value = value.getCause();
        return value;
    }

    private static String category(Throwable failure) {
        if (failure instanceof DirectoryClientException directory) {
            return directory.category().name().toLowerCase(java.util.Locale.ROOT);
        }
        if (failure instanceof CoreDirectoryException core) {
            return core.code().name().toLowerCase(java.util.Locale.ROOT);
        }
        return "unexpected_failure";
    }

    private static boolean retryableStartupFailure(Throwable failure) {
        if (failure instanceof CoreDirectoryException core) {
            return core.code() == link.e4steam.api.ApiErrorCode.UNAVAILABLE;
        }
        if (failure instanceof DirectoryClientException directory) {
            return directory.retryable()
                    || directory.category() == DirectoryClientException.Category.NETWORK
                    || directory.category() == DirectoryClientException.Category.TIMEOUT
                    || directory.category() == DirectoryClientException.Category.UNAVAILABLE;
        }
        return false;
    }
}
