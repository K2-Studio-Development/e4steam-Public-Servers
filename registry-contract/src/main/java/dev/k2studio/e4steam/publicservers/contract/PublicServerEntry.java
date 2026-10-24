package dev.k2studio.e4steam.publicservers.contract;

import java.time.Instant;
import java.util.Objects;

/** Immutable catalog row. Target references are intentionally absent. */
public record PublicServerEntry(
        String listingId,
        long revision,
        PublicServerType type,
        ListingMetadata metadata,
        Instant lastHeartbeat,
        Instant expiresAt,
        String ownerRef
) {
    public PublicServerEntry {
        listingId = TextPolicy.opaque(listingId, "listingId", 96);
        if (revision < 1) throw new IllegalArgumentException("invalid revision");
        type = Objects.requireNonNull(type, "type");
        metadata = Objects.requireNonNull(metadata, "metadata");
        lastHeartbeat = Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(lastHeartbeat)) throw new IllegalArgumentException("invalid expiry");
        ownerRef = TextPolicy.opaque(ownerRef, "ownerRef", 96);
    }

    public boolean stale(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
