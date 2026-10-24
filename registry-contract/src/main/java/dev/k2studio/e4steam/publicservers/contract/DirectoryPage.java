package dev.k2studio.e4steam.publicservers.contract;

import java.util.List;

/** Bounded cursor page returned by the catalog. */
public record DirectoryPage(List<PublicServerEntry> entries, String nextCursor, InstantStamp generatedAt) {
    public DirectoryPage {
        if (entries == null || entries.size() > DirectoryProtocol.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid page size");
        }
        entries = List.copyOf(entries);
        nextCursor = nextCursor == null || nextCursor.isBlank()
                ? "" : TextPolicy.opaque(nextCursor, "nextCursor", 256);
        if (generatedAt == null) throw new NullPointerException("generatedAt");
    }
}
