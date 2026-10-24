package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.DirectoryClientException;
import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import link.e4steam.api.Registration;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Loader-neutral catalog state owner. Network callbacks never mutate a replacement generation. */
public final class DirectoryController implements Registration {
    private static final int MAX_LISTENERS = 32;

    private final PublicDirectoryClient client;
    private final PublicServersConfig config;
    private final ClientEnvironment environment;
    private final CompatibilityEvaluator evaluator;
    private final DirectoryCache cache;
    private final Clock clock;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<PublicServersFilter> filter = new AtomicReference<>(PublicServersFilter.all());
    private final AtomicReference<PublicServersSnapshot> snapshot;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String selectedListingId = "";

    public DirectoryController(PublicDirectoryClient client, PublicServersConfig config,
                               ClientEnvironment environment, CompatibilityEvaluator evaluator,
                               DirectoryCache cache, Clock clock) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
        this.cache = java.util.Objects.requireNonNull(cache, "cache");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (!config.configured()) throw new IllegalArgumentException("directory is not configured");
        this.snapshot = new AtomicReference<>(PublicServersSnapshot.initial(
                PublicServersSnapshot.State.EMPTY, clock.instant(), "e4steam_public_servers.state.empty"));
    }

    public PublicServersSnapshot snapshot() { return snapshot.get(); }

    public CompletionStage<PublicServersSnapshot> refresh() {
        if (closed.get()) return CompletableFuture.completedFuture(snapshot.get());
        long expectedGeneration = generation.incrementAndGet();
        setSnapshot(new PublicServersSnapshot(PublicServersSnapshot.State.LOADING,
                snapshot.get().items(), selectedListingId, clock.instant(), "e4steam_public_servers.state.loading"));
        ArrayList<DirectoryPage> pages = new ArrayList<>();
        return page(expectedGeneration, "", config.maximumPages(), new HashSet<>(), pages)
                .handle((ignored, failure) -> finishRefresh(expectedGeneration, pages, failure));
    }

    public void filter(PublicServersFilter value) {
        filter.set(java.util.Objects.requireNonNull(value, "filter"));
        if (!closed.get()) rebuild(snapshot.get().state(), snapshot.get().messageKey());
    }

    public PublicServersFilter filter() { return filter.get(); }

    public void select(String listingId) {
        String checked = listingId == null ? "" : listingId;
        if (!checked.isEmpty() && checked.length() > 96) throw new IllegalArgumentException("invalid listingId");
        selectedListingId = checked;
        PublicServersSnapshot current = snapshot.get();
        setSnapshot(new PublicServersSnapshot(current.state(), current.items(), checked,
                current.generatedAt(), current.messageKey()));
    }

    public Registration addListener(Runnable listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        if (closed.get()) throw new IllegalStateException("controller is closed");
        if (listeners.size() >= MAX_LISTENERS) throw new IllegalStateException("listener limit reached");
        listeners.add(listener);
        return new ListenerRegistration(listener);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        generation.incrementAndGet();
        listeners.clear();
        cache.close();
        snapshot.set(PublicServersSnapshot.initial(PublicServersSnapshot.State.CLOSED,
                clock.instant(), "e4steam_public_servers.state.closed"));
    }

    @Override
    public boolean isClosed() { return closed.get(); }

    private CompletionStage<Void> page(long expectedGeneration, String cursor, int pagesRemaining,
                                       Set<String> visitedCursors, List<DirectoryPage> pages) {
        if (!current(expectedGeneration)) return CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("stale catalog generation"));
        if (!visitedCursors.add(cursor)) return CompletableFuture.failedFuture(
                new IllegalStateException("registry repeated a pagination cursor"));
        return client.catalog(config.pageSize(), cursor, null).thenCompose(result -> {
            if (!current(expectedGeneration)) return CompletableFuture.failedFuture(
                    new java.util.concurrent.CancellationException("stale catalog generation"));
            pages.add(result);
            if (pagesRemaining == 1 || result.nextCursor().isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return page(expectedGeneration, result.nextCursor(), pagesRemaining - 1,
                    visitedCursors, pages);
        });
    }

    private PublicServersSnapshot finishRefresh(long expectedGeneration, List<DirectoryPage> pages,
                                                  Throwable failure) {
        if (!current(expectedGeneration)) return snapshot.get();
        Instant now = clock.instant();
        if (failure == null) {
            cache.clear();
            for (DirectoryPage page : pages) cache.put(page, now);
            rebuild(PublicServersSnapshot.State.READY, "");
            return snapshot.get();
        }
        Throwable cause = unwrap(failure);
        List<PublicServerEntry> cached = cache.snapshot(now);
        if (!cached.isEmpty()) {
            rebuild(PublicServersSnapshot.State.CACHED, "e4steam_public_servers.state.cached");
        } else if (cause instanceof DirectoryClientException directory
                && directory.category() == DirectoryClientException.Category.RATE_LIMITED) {
            rebuild(PublicServersSnapshot.State.RATE_LIMITED, "e4steam_public_servers.state.rate_limited");
        } else {
            rebuild(PublicServersSnapshot.State.UNAVAILABLE, "e4steam_public_servers.state.registry_unavailable");
        }
        return snapshot.get();
    }

    private void rebuild(PublicServersSnapshot.State requestedState, String messageKey) {
        Instant now = clock.instant();
        ArrayList<PublicServersSnapshot.Item> items = new ArrayList<>();
        PublicServersFilter currentFilter = filter.get();
        for (PublicServerEntry entry : cache.snapshot(now)) {
            Compatibility compatibility = evaluator.evaluate(entry, environment, now);
            if (currentFilter.matches(entry, compatibility)) {
                items.add(new PublicServersSnapshot.Item(entry, compatibility));
            }
        }
        PublicServersSnapshot.State state = requestedState;
        String message = messageKey;
        if ((requestedState == PublicServersSnapshot.State.READY
                || requestedState == PublicServersSnapshot.State.EMPTY) && items.isEmpty()) {
            state = PublicServersSnapshot.State.EMPTY;
            message = "e4steam_public_servers.state.empty";
        } else if (requestedState == PublicServersSnapshot.State.READY) {
            message = "";
        }
        setSnapshot(new PublicServersSnapshot(state, items, selectedListingId,
                cache.generatedAt().equals(Instant.EPOCH) ? now : cache.generatedAt(), message));
    }

    private void setSnapshot(PublicServersSnapshot value) {
        snapshot.set(value);
        for (Runnable listener : listeners) {
            try { listener.run(); }
            catch (RuntimeException ignored) { /* UI listeners are isolated and removable. */ }
        }
    }

    private boolean current(long value) { return !closed.get() && generation.get() == value; }

    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while ((value instanceof CompletionException || value instanceof java.util.concurrent.ExecutionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    private final class ListenerRegistration implements Registration {
        private final Runnable listener;
        private final AtomicBoolean registrationClosed = new AtomicBoolean();
        private ListenerRegistration(Runnable listener) { this.listener = listener; }
        @Override public void close() {
            if (registrationClosed.compareAndSet(false, true)) listeners.remove(listener);
        }
        @Override public boolean isClosed() { return registrationClosed.get(); }
    }
}
