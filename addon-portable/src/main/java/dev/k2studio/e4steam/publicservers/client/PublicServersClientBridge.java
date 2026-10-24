package dev.k2studio.e4steam.publicservers.client;

import dev.k2studio.e4steam.publicservers.common.PublicJoinCoordinator;
import dev.k2studio.e4steam.publicservers.common.PublicServersRuntime;
import dev.k2studio.e4steam.publicservers.common.PublicServersSnapshot;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import link.e4steam.api.Registration;

import java.time.Instant;
import java.util.Optional;

/** Thin client-only adapter around the loader-neutral runtime. */
public final class PublicServersClientBridge {
    private static final PublicServersSnapshot NOT_CONFIGURED = PublicServersSnapshot.initial(
            PublicServersSnapshot.State.NOT_CONFIGURED,
            Instant.EPOCH,
            "e4steam_public_servers.state.not_configured");

    private PublicServersClientBridge() {
    }

    public static void start() {
        // Runtime ownership remains with e4steam's addon lifecycle.
    }

    public static void setCreatorName(String creatorName) {
        PublicServersRuntime.setLocalCreatorName(creatorName);
    }

    public static void stop() {
        // Screen registrations close themselves; no second executor exists here.
    }

    public static PublicServersSnapshot snapshot() {
        return PublicServersRuntime.current()
                .map(PublicServersRuntime::clientSnapshot)
                .orElse(NOT_CONFIGURED);
    }

    public static void refresh() {
        PublicServersRuntime.current().ifPresent(PublicServersRuntime::refresh);
    }

    public static void search(String value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.search(value));
    }

    public static void type(PublicServerType value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.type(value));
    }

    public static void compatibleOnly(boolean value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.compatibleOnly(value));
    }

    public static void hasSlotsOnly(boolean value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.hasSlotsOnly(value));
    }

    public static void select(String listingId) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.select(listingId));
    }

    public static void join(String listingId) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.join(listingId));
    }

    public static void cancelJoin() {
        PublicServersRuntime.current().ifPresent(PublicServersRuntime::cancelJoin);
    }

    public static Optional<PublicJoinCoordinator.Snapshot> joinSnapshot() {
        return PublicServersRuntime.current().map(PublicServersRuntime::joinSnapshot);
    }

    public static AutoCloseable listen(Runnable listener) {
        Optional<PublicServersRuntime> runtime = PublicServersRuntime.current();
        if (runtime.isEmpty()) return () -> { };
        Registration registration = runtime.orElseThrow().addListener(listener);
        return registration::close;
    }
}
