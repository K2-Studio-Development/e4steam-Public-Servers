package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import link.e4steam.api.Registration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory LRU/TTL cache. Target references are absent from registry entries by contract. */
public final class DirectoryCache implements Registration {
    private final int maximumEntries;
    private final Duration ttl;
    private final LinkedHashMap<String, CachedEntry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private Instant generatedAt = Instant.EPOCH;

    public DirectoryCache(int maximumEntries, Duration ttl) {
        if (maximumEntries < 1 || maximumEntries > 1_000) throw new IllegalArgumentException("invalid maximumEntries");
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(5)) < 0
                || ttl.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("invalid ttl");
        this.maximumEntries = maximumEntries;
        this.ttl = ttl;
    }

    public synchronized void put(DirectoryPage page, Instant now) {
        requireOpen();
        if (page == null || now == null) throw new NullPointerException("cache input");
        prune(now);
        for (PublicServerEntry entry : page.entries()) {
            entries.put(entry.listingId(), new CachedEntry(entry, now));
            while (entries.size() > maximumEntries) {
                String eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
            }
        }
        generatedAt = page.generatedAt().instant();
    }

    public synchronized List<PublicServerEntry> snapshot(Instant now) {
        requireOpen();
        prune(now);
        ArrayList<PublicServerEntry> copy = new ArrayList<>(entries.size());
        for (CachedEntry cached : entries.values()) copy.add(cached.entry());
        return List.copyOf(copy);
    }

    public synchronized Instant generatedAt() { return generatedAt; }

    public synchronized int size() { return entries.size(); }

    public synchronized void clear() { entries.clear(); generatedAt = Instant.EPOCH; }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        clear();
    }

    @Override
    public boolean isClosed() { return closed.get(); }

    private void prune(Instant now) {
        for (var iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<String, CachedEntry> value = iterator.next();
            if (!value.getValue().storedAt().plus(ttl).isAfter(now)
                    || value.getValue().entry().stale(now)) iterator.remove();
        }
    }

    private void requireOpen() { if (closed.get()) throw new IllegalStateException("cache is closed"); }

    private record CachedEntry(PublicServerEntry entry, Instant storedAt) { }
}
