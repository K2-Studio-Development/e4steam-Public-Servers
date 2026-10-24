package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Headless lifecycle adapter. It never imports or assumes a Minecraft client class. */
public final class DedicatedServerPublisher {
    public enum ServerState {
        OFF, CONFIG_VALIDATED, STEAM_INITIALIZING, TRANSPORT_READY, MINECRAFT_READY,
        ACCEPTING, DRAINING, STOPPED, FAILED
    }

    private final PublicationLeaseManager leases;
    private final boolean enabled;

    public DedicatedServerPublisher(PublicationLeaseManager leases, boolean enabled) {
        this.leases = java.util.Objects.requireNonNull(leases, "leases");
        this.enabled = enabled;
    }

    /** Reconciles a safe server snapshot with publication policy. */
    public CompletionStage<PublicationLeaseManager.Snapshot> reconcile(
            ServerState state,
            boolean configPublicationEnabled,
            boolean proposalAllowed,
            boolean steamGameServerReady,
            boolean transportReady,
            boolean minecraftReady,
            ListingMetadata metadata
    ) {
        if (state == null) throw new NullPointerException("state");
        if (state == ServerState.DRAINING || state == ServerState.STOPPED
                || state == ServerState.FAILED || state == ServerState.OFF
                || !enabled || !configPublicationEnabled || !proposalAllowed
                || !steamGameServerReady || !transportReady || !minecraftReady) {
            return leases.unpublish(reason(state));
        }
        if (state != ServerState.ACCEPTING || metadata == null) {
            return CompletableFuture.completedFuture(leases.snapshot());
        }
        ListingMetadata checked = MetadataValidator.validate(
                dev.k2studio.e4steam.publicservers.contract.PublicServerType.DEDICATED_SERVER, metadata);
        return leases.active() ? leases.updateMetadata(checked) : leases.publish(checked);
    }

    public CompletionStage<PublicationLeaseManager.Snapshot> onSteamGameServerLost() {
        return leases.unpublish("steam_gameserver_lost");
    }

    public CompletionStage<PublicationLeaseManager.Snapshot> onTransportLost() {
        return leases.unpublish("transport_lost");
    }

    public CompletionStage<PublicationLeaseManager.Snapshot> unpublish() {
        return leases.unpublish("admin_unpublish");
    }

    public PublicationLeaseManager.Snapshot snapshot() { return leases.snapshot(); }

    private static String reason(ServerState state) {
        return switch (state) {
            case DRAINING -> "dedicated_draining";
            case STOPPED -> "dedicated_stopped";
            case FAILED -> "dedicated_failed";
            case OFF -> "dedicated_off";
            default -> "publication_not_ready";
        };
    }
}
