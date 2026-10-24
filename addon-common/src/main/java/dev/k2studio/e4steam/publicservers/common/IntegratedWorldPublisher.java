package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Integrated/listen-host lifecycle adapter; publication always remains explicit opt-in. */
public final class IntegratedWorldPublisher {
    private final PublicationLeaseManager leases;
    private final boolean enabled;

    public IntegratedWorldPublisher(PublicationLeaseManager leases, boolean enabled) {
        this.leases = java.util.Objects.requireNonNull(leases, "leases");
        this.enabled = enabled;
    }

    public CompletionStage<PublicationLeaseManager.Snapshot> publish(
            ListingMetadata metadata,
            boolean worldOpened,
            boolean steamSessionActive,
            boolean ownerConfirmed
    ) {
        if (!enabled || !worldOpened || !steamSessionActive || !ownerConfirmed) {
            return CompletableFuture.completedFuture(leases.snapshot());
        }
        ListingMetadata checked = MetadataValidator.validate(
                dev.k2studio.e4steam.publicservers.contract.PublicServerType.INTEGRATED_WORLD, metadata);
        return leases.active() ? leases.updateMetadata(checked) : leases.publish(checked);
    }

    public CompletionStage<PublicationLeaseManager.Snapshot> onE4steamStop() { return stop("e4steam_stop"); }
    public CompletionStage<PublicationLeaseManager.Snapshot> onWorldClosed() { return stop("world_closed"); }
    public CompletionStage<PublicationLeaseManager.Snapshot> onLobbyLost() { return stop("lobby_lost"); }
    public CompletionStage<PublicationLeaseManager.Snapshot> onOpenCancelled() { return stop("open_cancelled"); }
    public CompletionStage<PublicationLeaseManager.Snapshot> onConnectingElsewhere() { return stop("client_connecting_elsewhere"); }
    public CompletionStage<PublicationLeaseManager.Snapshot> onRuntimeStopping() { return stop("runtime_stopping"); }
    public PublicationLeaseManager.Snapshot snapshot() { return leases.snapshot(); }
    private CompletionStage<PublicationLeaseManager.Snapshot> stop(String reason) { return leases.unpublish(reason); }
}
