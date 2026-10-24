package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.HttpPublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import link.e4steam.api.Registration;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.api.logging.SafeLogger;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Shared loader-neutral facade. Reopening UI reuses this runtime and never creates an executor. */
public final class PublicServersRuntime implements Registration {
    private static final int MAX_LISTENERS = 32;
    private static final AtomicReference<PublicServersRuntime> CURRENT = new AtomicReference<>();
    private static final AtomicReference<String> INTEGRATED_SERVER_NAME =
            new AtomicReference<>(ServerDisplayName.FALLBACK);

    private static volatile String customIntegratedName;
    public static void setIntegratedServerName(String name) {
        customIntegratedName = dev.k2studio.e4steam.publicservers.contract.TextPolicy.title(name);
    }
    private final CoreDirectoryGateway core;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Session> session = new AtomicReference<>();
    private final AtomicReference<PublicServersFilter> desiredFilter =
            new AtomicReference<>(PublicServersFilter.all());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile PublicServersSnapshot unconfigured;

    private PublicServersRuntime(CoreDirectoryGateway core, Clock clock) {
        this.core = java.util.Objects.requireNonNull(core, "core");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.unconfigured = PublicServersSnapshot.initial(PublicServersSnapshot.State.NOT_CONFIGURED,
                clock.instant(), "e4steam_public_servers.state.not_configured");
    }

    /** Installs the one active addon-scoped facade. */
    static PublicServersRuntime install(CoreDirectoryGateway core, Clock clock) {
        PublicServersRuntime value = new PublicServersRuntime(core, clock);
        if (!CURRENT.compareAndSet(null, value)) {
            throw new IllegalStateException("public servers runtime is already initialized");
        }
        return value;
    }

    /** Loader adapters use this bridge without loading Minecraft classes into common code. */
    public static Optional<PublicServersRuntime> current() {
        PublicServersRuntime value = CURRENT.get();
        return value == null || value.isClosed() ? Optional.empty() : Optional.of(value);
    }

    /** Supplies the local Minecraft nickname used only for the public listing title. */
    public static void setLocalCreatorName(String creatorName) {
        INTEGRATED_SERVER_NAME.set(ServerDisplayName.fromCreator(creatorName));
    }

    String integratedServerName() {
        return customIntegratedName == null ? INTEGRATED_SERVER_NAME.get() : customIntegratedName;
    }

    /** Creates the production/dev HTTP client from a validated explicit configuration. */
    public synchronized void configure(PublicServersConfig config, ClientEnvironment environment) {
        java.util.Objects.requireNonNull(config, "config");
        java.util.Objects.requireNonNull(environment, "environment");
        requireOpen();
        if (config.steamDirectory()) throw new IllegalArgumentException("Steam services required");
        if (!config.configured()) {
            replace(null);
            unconfigured = PublicServersSnapshot.initial(PublicServersSnapshot.State.NOT_CONFIGURED,
                    clock.instant(), "e4steam_public_servers.state.not_configured");
            notifyListeners();
            return;
        }
        var policy = config.developmentLoopback()
                ? HttpPublicDirectoryClient.TransportPolicy.ALLOW_LOOPBACK_HTTP
                : HttpPublicDirectoryClient.TransportPolicy.HTTPS_ONLY;
        PublicDirectoryClient client = new HttpPublicDirectoryClient(config.registryOrigin(),
                config.connectTimeout(), config.requestTimeout(), config.httpWorkers(),
                config.queuedRequests(), policy);
        try {
            configure(config, environment, client);
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    synchronized void configureSteam(PublicServersConfig config, ClientEnvironment environment,
                                     LobbyService lobbies, SafeLogger logger) {
        requireOpen();
        if (!config.steamDirectory()) throw new IllegalArgumentException("Steam configuration required");
        Session replacement = new Session(config, environment, new SteamDirectoryClient(lobbies, clock));
        replacement.steam = new SteamWorldPublisher(lobbies, core, clock, replacement.timer, logger);
        replacement.controller.filter(desiredFilter.get());
        replace(replacement);
        notifyListeners();
    }

    Optional<SteamWorldPublisher> steamPublisher() {
        Session value = session.get();
        return value == null ? Optional.empty() : Optional.ofNullable(value.steam);
    }

    /** Injection seam for deterministic tests and local adapters. Ownership transfers on success. */
    public synchronized void configure(PublicServersConfig config, ClientEnvironment environment,
                                       PublicDirectoryClient client) {
        requireOpen();
        if (config == null || environment == null || client == null) throw new NullPointerException("configuration");
        if (!config.configured() || !config.registryOrigin().equals(client.origin())) {
            throw new IllegalArgumentException("client origin does not match configured registry");
        }
        Session replacement = new Session(config, environment, client);
        replacement.controller.filter(desiredFilter.get());
        replace(replacement);
        notifyListeners();
    }

    void reconcileSessions(link.e4steam.api.session.SessionService sessions,
                           java.util.function.Consumer<link.e4steam.api.session.SessionService.SessionSnapshot> callback) {
        Session current = session.get();
        if (current == null) return;
        current.timer.scheduleWithFixedDelay(() -> {
            if (closed.get() || session.get() != current) return;
            try {
                var result = sessions.snapshot();
                if (result.isSuccess()) callback.accept(result.value().orElseThrow());
                else if (current.steam != null) current.steam.stop();
            } catch (RuntimeException failure) {
                java.util.logging.Logger.getLogger("e4steam-public-servers").warning(
                        "Publication reconciliation failed: " + failure.getClass().getSimpleName());
            }
        }, 1, 10, java.util.concurrent.TimeUnit.SECONDS);
    }
    public PublicServersSnapshot clientSnapshot() {
        Session value = session.get();
        return value == null ? unconfigured : value.controller.snapshot();
    }

    public CompletionStage<PublicServersSnapshot> refresh() {
        Session value = session.get();
        return value == null ? CompletableFuture.completedFuture(unconfigured)
                : value.controller.refresh();
    }

    public void filter(PublicServersFilter value) {
        desiredFilter.set(java.util.Objects.requireNonNull(value, "filter"));
        Session currentSession = session.get();
        if (currentSession != null) currentSession.controller.filter(value);
    }

    public PublicServersFilter filter() { return desiredFilter.get(); }

    public void search(String value) {
        PublicServersFilter old = desiredFilter.get();
        filter(new PublicServersFilter(value, old.type(), old.compatibleOnly(),
                old.minecraftVersion(), old.loader(), old.hasSlotsOnly(),
                old.hiddenListings(), old.blockedOwners()));
    }

    public void type(PublicServerType value) {
        PublicServersFilter old = desiredFilter.get();
        filter(new PublicServersFilter(old.search(), value, old.compatibleOnly(),
                old.minecraftVersion(), old.loader(), old.hasSlotsOnly(),
                old.hiddenListings(), old.blockedOwners()));
    }

    public void compatibleOnly(boolean value) {
        PublicServersFilter old = desiredFilter.get();
        filter(new PublicServersFilter(old.search(), old.type(), value,
                old.minecraftVersion(), old.loader(), old.hasSlotsOnly(),
                old.hiddenListings(), old.blockedOwners()));
    }

    public void hasSlotsOnly(boolean value) {
        PublicServersFilter old = desiredFilter.get();
        filter(new PublicServersFilter(old.search(), old.type(), old.compatibleOnly(),
                old.minecraftVersion(), old.loader(), value,
                old.hiddenListings(), old.blockedOwners()));
    }

    public void select(String listingId) {
        Session value = session.get();
        if (value != null) value.controller.select(listingId);
    }

    public CompletionStage<PublicJoinCoordinator.Snapshot> join(String listingId) {
        Session value = session.get();
        if (value == null) return CompletableFuture.completedFuture(
                new PublicJoinCoordinator.Snapshot(0L, listingId == null ? "" : listingId,
                        PublicJoinCoordinator.State.REGISTRY_UNAVAILABLE, "not_configured"));
        for (PublicServersSnapshot.Item item : value.controller.snapshot().items()) {
            if (item.entry().listingId().equals(listingId)) {
                value.controller.select(listingId);
                return value.join.join(listingId, item.entry().type());
            }
        }
        return CompletableFuture.completedFuture(new PublicJoinCoordinator.Snapshot(0L,
                listingId == null ? "" : listingId, PublicJoinCoordinator.State.STALE,
                "listing_missing"));
    }

    public CompletionStage<PublicJoinCoordinator.Snapshot> joinSelected() {
        PublicServersSnapshot.Item selected = clientSnapshot().selected();
        return selected == null ? CompletableFuture.completedFuture(new PublicJoinCoordinator.Snapshot(
                0L, "", PublicJoinCoordinator.State.STALE, "no_selection"))
                : join(selected.entry().listingId());
    }

    public CompletionStage<PublicJoinCoordinator.Snapshot> cancelJoin() {
        Session value = session.get();
        return value == null ? CompletableFuture.completedFuture(new PublicJoinCoordinator.Snapshot(
                0L, "", PublicJoinCoordinator.State.CANCELLED, "")) : value.join.cancel();
    }

    public PublicJoinCoordinator.Snapshot joinSnapshot() {
        Session value = session.get();
        return value == null ? new PublicJoinCoordinator.Snapshot(0L, "",
                PublicJoinCoordinator.State.IDLE, "") : value.join.snapshot();
    }

    public Optional<IntegratedWorldPublisher> integratedPublisher() {
        Session value = session.get();
        return value == null || value.steam != null ? Optional.empty() : Optional.of(value.integrated);
    }

    public Optional<DedicatedServerPublisher> dedicatedPublisher() {
        Session value = session.get();
        return value == null || value.steam != null ? Optional.empty() : Optional.of(value.dedicated);
    }

    public Optional<ModerationClient> moderation() {
        Session value = session.get();
        return value == null ? Optional.empty() : Optional.of(value.moderation);
    }

    public Registration addListener(Runnable listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        requireOpen();
        if (listeners.size() >= MAX_LISTENERS) throw new IllegalStateException("listener limit reached");
        listeners.add(listener);
        return new RuntimeListener(listener);
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        replace(null);
        listeners.clear();
        unconfigured = PublicServersSnapshot.initial(PublicServersSnapshot.State.CLOSED,
                clock.instant(), "e4steam_public_servers.state.closed");
        CURRENT.compareAndSet(this, null);
    }

    @Override public boolean isClosed() { return closed.get(); }

    private void replace(Session replacement) {
        Session old = session.getAndSet(replacement);
        if (old != null) old.close();
    }

    private void notifyListeners() {
        for (Runnable listener : List.copyOf(listeners)) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    private void requireOpen() { if (closed.get()) throw new IllegalStateException("runtime is closed"); }

    private final class Session implements AutoCloseable {
        private final PublicDirectoryClient client;
        private final ScheduledThreadPoolExecutor timer;
        private final DirectoryController controller;
        private final PublicJoinCoordinator join;
        private final PublicationLeaseManager integratedLeases;
        private final PublicationLeaseManager dedicatedLeases;
        private final IntegratedWorldPublisher integrated;
        private final DedicatedServerPublisher dedicated;
        private final ModerationClient moderation;
        private SteamWorldPublisher steam;

        private Session(PublicServersConfig config, ClientEnvironment environment,
                        PublicDirectoryClient client) {
            this.client = client;
            this.timer = new ScheduledThreadPoolExecutor(1, new TimerThreadFactory());
            timer.setRemoveOnCancelPolicy(true);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            CompatibilityEvaluator evaluator = new CompatibilityEvaluator();
            DirectoryCache cache = new DirectoryCache(config.cacheEntries(), config.cacheTtl());
            this.controller = new DirectoryController(client, config, environment, evaluator, cache, clock);
            controller.addListener(PublicServersRuntime.this::notifyListeners);
            this.join = new PublicJoinCoordinator(client, core, evaluator, environment, config,
                    clock, PublicServersRuntime.this::notifyListeners);
            PublisherAttestationCoordinator attest = new PublisherAttestationCoordinator(client, core, clock);
            this.integratedLeases = new PublicationLeaseManager(PublicServerType.INTEGRATED_WORLD,
                    client, attest, timer, clock, PublicServersRuntime.this::notifyListeners);
            this.dedicatedLeases = new PublicationLeaseManager(PublicServerType.DEDICATED_SERVER,
                    client, attest, timer, clock, PublicServersRuntime.this::notifyListeners);
            this.integrated = new IntegratedWorldPublisher(integratedLeases,
                    config.integratedPublicationEnabled());
            this.dedicated = new DedicatedServerPublisher(dedicatedLeases,
                    config.dedicatedPublicationEnabled());
            this.moderation = new ModerationClient(client);
        }

        @Override
        public void close() {
            if (steam != null) steam.close();
            integratedLeases.close();
            dedicatedLeases.close();
            join.close();
            controller.close();
            moderation.close();
            client.close();
            timer.shutdownNow();
            try { timer.awaitTermination(2L, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
    }

    private final class RuntimeListener implements Registration {
        private final Runnable listener;
        private final AtomicBoolean listenerClosed = new AtomicBoolean();
        private RuntimeListener(Runnable listener) { this.listener = listener; }
        @Override public void close() {
            if (listenerClosed.compareAndSet(false, true)) listeners.remove(listener);
        }
        @Override public boolean isClosed() { return listenerClosed.get(); }
    }

    private static final class TimerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "e4steam-public-servers-timer-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
