package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.DirectoryProtocol;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import link.e4steam.api.ApiServiceKeys;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiVersion;
import link.e4steam.api.ApiVersionRange;
import link.e4steam.api.addon.AddonContext;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.addon.E4steamAddonEntrypoint;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.capability.CapabilityId;
import link.e4steam.api.directory.PublicDirectoryService;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.api.event.DedicatedStateEvent;
import link.e4steam.api.event.RuntimeStoppingEvent;
import link.e4steam.api.event.SessionStateEvent;
import link.e4steam.api.runtime.RuntimeSnapshot;
import link.e4steam.api.session.SessionService;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loader-independent Addon API 1.1 entrypoint. */
public final class PublicServersAddon implements E4steamAddonEntrypoint {
    public static final String ADDON_ID = "k2studio:e4steam_public_servers";
    public static final String VERSION = "0.1.0-beta.18";
    private static final System.Logger LOGGER =
            System.getLogger("e4steam Public Servers");

    @Override
    public AddonDescriptor descriptor() {
        Set<CapabilityId> requested = new LinkedHashSet<>();
        requested.add(Capabilities.SESSION_OBSERVE);
        requested.add(Capabilities.LOBBY_CREATE);
        requested.add(Capabilities.LOBBY_METADATA_WRITE);
        requested.add(Capabilities.LOBBY_SEARCH);
        requested.add(Capabilities.DIRECTORY_PUBLICATION);
        requested.add(Capabilities.DIRECTORY_ATTESTATION);
        requested.add(Capabilities.DIRECTORY_JOIN);
        requested.add(Capabilities.ACCESS_MODE_REGISTER);
        requested.add(Capabilities.ACCESS_POLICY_EVALUATE);
        requested.add(Capabilities.DEDICATED_OBSERVE);
        requested.add(Capabilities.DEDICATED_PUBLICATION_PROPOSE);
        requested.add(Capabilities.UI_CONTRIBUTE);
        requested.add(Capabilities.COMMANDS_REGISTER);
        return new AddonDescriptor(new AddonId(ADDON_ID), "e4steam Public Servers",
                ApiVersion.parse(VERSION),
                new ApiVersionRange(ApiVersion.parse("1.1.0"), ApiVersion.parse("2.0.0")),
                Collections.emptyList(), requested,
                Collections.singleton(Capabilities.SESSION_OBSERVE));
    }

    @Override
    public void initialize(AddonContext context) {
        if (context == null) throw new NullPointerException("context");
        PublicDirectoryService service = context.api().services()
                .find(ApiServiceKeys.PUBLIC_DIRECTORY).orElse(null);
        CoreDirectoryGateway gateway = service == null
                ? new UnavailableCoreDirectoryGateway()
                : new E4steamCoreDirectoryGateway(service);
        PublicServersConfig config = PublicServersSystemConfiguration.load();
        LOGGER.log(System.Logger.Level.INFO,
                config.configured()
                        ? "Public directory configured; integrated publication="
                        + config.integratedPublicationEnabled() + ", dedicated publication="
                        + config.dedicatedPublicationEnabled()
                        : "Public directory is not configured");
        // Register during addon initialization even when the directory has not
        // been configured. Registration freezes before the LAN screen opens.
        var accessRegistration = context.api().access().register(new PublicAccessModeProvider(config));
        if (accessRegistration.isSuccess()) {
            context.resources().own(accessRegistration.value().orElseThrow());
        } else {
            LOGGER.log(System.Logger.Level.WARNING, "Public access mode registration failed");
        }
        PublicServersRuntime runtime = PublicServersRuntime.install(gateway, Clock.systemUTC());
        context.resources().own(runtime);
        if (config.configured()) {
            RuntimeSnapshot core = context.api().runtime().snapshot();
            LinkedHashMap<String, String> addons = new LinkedHashMap<>();
            context.api().addons().addons().forEach(handle -> addons.put(
                    handle.descriptor().id().value(), handle.descriptor().version().toString()));
            ClientEnvironment environment = new ClientEnvironment(core.minecraftVersion(),
                    core.loader().id(), core.modVersion(), Math.max(1, core.wireVersion()),
                    DirectoryProtocol.VERSION, addons);
            if (config.steamDirectory()) {
                runtime.configureSteam(config, environment, context.api().lobbies(), context.api().logger());
                context.api().logger().log(link.e4steam.api.logging.SafeLogger.Level.INFO,
                        "steam_directory_enabled", Map.of());
            } else runtime.configure(config, environment);
            registerLifecycle(context, runtime, config, core);
            runtime.reconcileSessions(context.api().sessions(), snapshot -> onSession(runtime, config, core, snapshot));
        }
    }

    private static void registerLifecycle(AddonContext context, PublicServersRuntime runtime,
                                          PublicServersConfig config, RuntimeSnapshot core) {
        own(context, context.api().events().subscribe(SessionStateEvent.TYPE,
                event -> onSession(runtime, config, core, event.snapshot())));
        own(context, context.api().events().subscribe(DedicatedStateEvent.TYPE,
                event -> onDedicated(context, runtime, config, core, event.snapshot())));
        own(context, context.api().events().subscribe(RuntimeStoppingEvent.TYPE,
                event -> {
                    runtime.steamPublisher().ifPresent(SteamWorldPublisher::stop);
                    runtime.integratedPublisher().ifPresent(IntegratedWorldPublisher::onRuntimeStopping);
                    runtime.dedicatedPublisher().ifPresent(DedicatedServerPublisher::unpublish);
                }));
        context.api().events().lastSnapshot(SessionStateEvent.TYPE)
                .ifPresent(event -> onSession(runtime, config, core, event.snapshot()));
        context.api().events().lastSnapshot(DedicatedStateEvent.TYPE)
                .ifPresent(event -> onDedicated(context, runtime, config, core, event.snapshot()));
    }

    private static void onSession(PublicServersRuntime runtime, PublicServersConfig config,
                                  RuntimeSnapshot core, SessionService.SessionSnapshot session) {
        runtime.steamPublisher().ifPresent(publisher -> {
            if (config.integratedPublicationEnabled()
                    && session.role() == SessionService.SessionRole.INTEGRATED_HOST
                    && session.state() == SessionService.SessionState.ACTIVE && validWorldCapacity(session.capacity())) {
                publisher.world(metadata(core, runtime.integratedServerName(), Math.min(session.capacity(), session.peers() + 1), session.capacity()));
            } else publisher.stop();
        });
        runtime.integratedPublisher().ifPresent(publisher -> {
            boolean host = session.role() == SessionService.SessionRole.INTEGRATED_HOST;
            boolean active = session.state() == SessionService.SessionState.ACTIVE;
            if (!host || !active || !validWorldCapacity(session.capacity())) {
                if (session.state() == SessionService.SessionState.CLOSED
                        || session.state() == SessionService.SessionState.CLOSING
                        || session.state() == SessionService.SessionState.FAILED || !host) {
                    publisher.onWorldClosed();
                }
                return;
            }
            publisher.publish(metadata(core, runtime.integratedServerName(), Math.min(session.capacity(), session.peers() + 1), session.capacity()),
                    true, true, config.integratedPublicationEnabled())
                    .whenComplete(PublicServersAddon::logPublication);
        });
    }

    private static void onDedicated(AddonContext context, PublicServersRuntime runtime,
                                    PublicServersConfig config, RuntimeSnapshot core,
                                    DedicatedServerService.DedicatedServerSnapshot server) {
        runtime.dedicatedPublisher().ifPresent(publisher -> {
            DedicatedServerPublisher.ServerState state = dedicatedState(server.state());
            if (state != DedicatedServerPublisher.ServerState.ACCEPTING) {
                publisher.reconcile(state, false, false, false, false, false, null);
                return;
            }
            context.api().dedicatedServers().proposePublication(
                    new DedicatedServerService.PublicationProposal(PublicAccessModeProvider.MODE_ID.value(),
                            "e4steam Public Servers", Set.of("modded")))
                    .thenAccept(result -> {
                        boolean allowed = result.isSuccess()
                                && result.value().map(DedicatedServerService.PublicationPlan::allowed).orElse(false);
                        publisher.reconcile(state, config.dedicatedPublicationEnabled(), allowed,
                                true, true, true, metadata(core, "Public e4steam server",
                                        server.players(), server.capacity()));
                    });
        });
    }

    static boolean validWorldCapacity(int capacity) { return capacity >= 2 && capacity <= 64; }

    private static ListingMetadata metadata(RuntimeSnapshot core, String name,
                                            int players, int capacity) {
        return new ListingMetadata(DirectoryProtocol.METADATA_SCHEMA, name, "",
                List.of("modded"), core.minecraftVersion(), core.loader().id(), core.modVersion(),
                Math.max(1, core.wireVersion()), VERSION, DirectoryProtocol.VERSION,
                players, capacity, PublicAccessModeProvider.MODE_ID.value(), Map.of());
    }

    private static DedicatedServerPublisher.ServerState dedicatedState(
            DedicatedServerService.DedicatedServerState value) {
        return switch (value) {
            case OFF -> DedicatedServerPublisher.ServerState.OFF;
            case CONFIG_VALIDATED -> DedicatedServerPublisher.ServerState.CONFIG_VALIDATED;
            case NATIVES_READY, STEAM_INITIALIZING, STEAM_LOGGING_ON ->
                    DedicatedServerPublisher.ServerState.STEAM_INITIALIZING;
            case TRANSPORT_READY -> DedicatedServerPublisher.ServerState.TRANSPORT_READY;
            case MINECRAFT_READY -> DedicatedServerPublisher.ServerState.MINECRAFT_READY;
            case ACCEPTING -> DedicatedServerPublisher.ServerState.ACCEPTING;
            case DRAINING -> DedicatedServerPublisher.ServerState.DRAINING;
            case STOPPED -> DedicatedServerPublisher.ServerState.STOPPED;
            case FAILED -> DedicatedServerPublisher.ServerState.FAILED;
        };
    }

    private static void own(AddonContext context, ApiResult<? extends link.e4steam.api.Registration> result) {
        result.value().ifPresent(context.resources()::own);
    }

    private static void logPublication(PublicationLeaseManager.Snapshot snapshot,
                                       Throwable failure) {
        if (failure != null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Public world publication failed before a safe snapshot was returned");
            return;
        }
        if (snapshot == null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Public world publication returned no state");
            return;
        }
        System.Logger.Level level = snapshot.state() == PublicationLeaseManager.State.ACTIVE
                ? System.Logger.Level.INFO : System.Logger.Level.WARNING;
        String detail = snapshot.errorCategory().isEmpty()
                ? "" : ", category=" + snapshot.errorCategory();
        LOGGER.log(level, "Public world publication state=" + snapshot.state() + detail);
    }
}
