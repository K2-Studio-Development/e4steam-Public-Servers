package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ReportRequest;
import dev.k2studio.e4steam.publicservers.contract.ReportReason;
import link.e4steam.api.Registration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-reason reports plus bounded local listing/owner suppression. */
public final class ModerationClient implements Registration {
    private static final int MAX_LOCAL_RULES = 1_000;
    private final PublicDirectoryClient client;
    private final LinkedHashSet<String> hiddenListings = new LinkedHashSet<>();
    private final LinkedHashSet<String> blockedOwners = new LinkedHashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ModerationClient(PublicDirectoryClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    public synchronized void hide(PublicServerEntry entry) {
        requireOpen();
        boundedAdd(hiddenListings, java.util.Objects.requireNonNull(entry, "entry").listingId());
    }

    public synchronized void blockOwner(PublicServerEntry entry) {
        requireOpen();
        boundedAdd(blockedOwners, java.util.Objects.requireNonNull(entry, "entry").ownerRef());
    }

    public CompletionStage<Void> report(PublicServerEntry entry, ReportReason reason) {
        requireOpen();
        return client.report(new ReportRequest(
                java.util.Objects.requireNonNull(entry, "entry").listingId(),
                java.util.Objects.requireNonNull(reason, "reason")));
    }

    public synchronized Set<String> hiddenListings() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(hiddenListings));
    }

    public synchronized Set<String> blockedOwners() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(blockedOwners));
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        hiddenListings.clear();
        blockedOwners.clear();
    }
    @Override public boolean isClosed() { return closed.get(); }

    private static void boundedAdd(LinkedHashSet<String> values, String value) {
        if (values.contains(value)) return;
        if (values.size() >= MAX_LOCAL_RULES) values.remove(values.iterator().next());
        values.add(value);
    }

    private void requireOpen() { if (closed.get()) throw new IllegalStateException("moderation client is closed"); }
}
