package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.api.logging.SafeLogger;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.*;

/** Publishes a separate discovery lobby only while the core confirms a current public host. */
final class SteamWorldPublisher implements AutoCloseable {
    private final LobbyService lobbies;
    private final CoreDirectoryGateway core;
    private final Clock clock;
    private final SafeLogger logger;
    private final ScheduledFuture<?> timer;
    private ListingMetadata desired;
    private LobbyService.LobbyId lobby;
    private String target = "";
    private boolean busy;
    private boolean closed;
    private long epoch;
    private String lastStatus = "";

    SteamWorldPublisher(LobbyService lobbies, CoreDirectoryGateway core, Clock clock,
                        ScheduledExecutorService scheduler, SafeLogger logger) {
        this.lobbies = lobbies; this.core = core; this.clock = clock; this.logger = logger;
        timer = scheduler.scheduleWithFixedDelay(this::tick, 1, 10, TimeUnit.SECONDS);
    }
    void world(ListingMetadata metadata) {
        synchronized (this) {
            if (closed) return;
            desired = MetadataValidator.validate(PublicServerType.INTEGRATED_WORLD, metadata);
        }
        tick();
    }
    synchronized void stop() {
        desired = null;
        epoch++;
        unpublish();
    }
    private synchronized void unpublish() {
        LobbyService.LobbyId previous = lobby;
        lobby = null;
        target = "";
        if (previous != null) lobbies.leave(previous);
    }
    void tick() {
        final long generation;
        final ListingMetadata metadata;
        synchronized (this) {
            if (closed || busy || desired == null) return;
            busy = true; generation = epoch; metadata = desired;
        }
        try {
            core.publicationTarget(PublicServerType.INTEGRATED_WORLD, SteamDirectoryClient.ORIGIN)
                    .thenCompose(current -> publish(generation, metadata, current.targetRef()))
                    .whenComplete((ignored, failure) -> {
                        synchronized (this) {
                            busy = false;
                            if (generation != epoch || closed) return;
                            if (failure != null) {
                                unpublish();
                                status("steam_publication_waiting_" + safeFailure(failure), SafeLogger.Level.WARN);
                            } else status("steam_publication_active", SafeLogger.Level.INFO);
                        }
                    });
        } catch (RuntimeException failure) {
            synchronized (this) { busy = false; unpublish(); status("steam_publication_failed", SafeLogger.Level.WARN); }
        }
    }
    private synchronized CompletionStage<?> publish(long generation, ListingMetadata metadata, String currentTarget) {
        if (closed || generation != epoch || desired == null) return CompletableFuture.failedFuture(new CancellationException());
        if (!currentTarget.equals(target)) unpublish();
        LobbyService.Metadata data = SteamDirectoryClient.encode(metadata, currentTarget, clock.instant());
        if (lobby != null) return lobbies.updateMetadata(lobby, data).thenApply(SteamDirectoryClient::unwrap);
        return lobbies.create(new LobbyService.CreateRequest(LobbyService.Visibility.PUBLIC, 2, data))
                .thenAccept(result -> {
                    LobbyService.LobbySnapshot created = SteamDirectoryClient.unwrap(result);
                    synchronized (this) {
                        if (closed || generation != epoch || desired == null) {
                            lobbies.leave(created.id());
                        } else { lobby = created.id(); target = currentTarget; }
                    }
                });
    }
    private static String safeFailure(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof CoreDirectoryException) {
            CoreDirectoryException coreFailure = (CoreDirectoryException) failure;
            return (coreFailure.code().name() + "_" + coreFailure.category()).toLowerCase(java.util.Locale.ROOT);
        }
        String name = failure.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9_]{1,64}") ? name.toLowerCase(java.util.Locale.ROOT) : "unknown";
    }
    private void status(String code, SafeLogger.Level level) {
        if (code.equals(lastStatus)) return;
        lastStatus = code;
        logger.log(level, code, Map.of());
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; timer.cancel(false); stop();
    }
}
