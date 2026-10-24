package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Immutable GUI-facing projection. It never contains target refs, join handles or lease proofs. */
public record PublicServersSnapshot(
        State state,
        List<Item> items,
        String selectedListingId,
        Instant generatedAt,
        String messageKey
) {
    public enum State { NOT_CONFIGURED, LOADING, READY, EMPTY, CACHED, UNAVAILABLE, RATE_LIMITED, CLOSED }

    public record Item(PublicServerEntry entry, Compatibility compatibility) {
        public Item {
            if (entry == null || compatibility == null) throw new NullPointerException("item");
        }
    }

    private static final Comparator<Item> ORDER = Comparator
            .comparing((Item item) -> item.compatibility().compatible()).reversed()
            .thenComparing(item -> item.compatibility().joinable(), Comparator.reverseOrder())
            .thenComparing(item -> item.entry().metadata().players() < item.entry().metadata().capacity(),
                    Comparator.reverseOrder())
            .thenComparing((Item item) -> item.entry().metadata().players(), Comparator.reverseOrder())
            .thenComparing(item -> sortName(item.entry().metadata().name()))
            .thenComparing(item -> item.entry().listingId());

    public PublicServersSnapshot {
        if (state == null || generatedAt == null) throw new NullPointerException("snapshot");
        ArrayList<Item> copy = new ArrayList<>(items == null ? List.of() : items);
        copy.sort(ORDER);
        items = List.copyOf(copy);
        selectedListingId = selectedListingId == null ? "" : selectedListingId;
        messageKey = safeKey(messageKey);
    }

    public Item selected() {
        if (selectedListingId.isEmpty()) return null;
        for (Item item : items) if (item.entry().listingId().equals(selectedListingId)) return item;
        return null;
    }

    public static PublicServersSnapshot initial(State state, Instant now, String messageKey) {
        return new PublicServersSnapshot(state, List.of(), "", now, messageKey);
    }

    private static String sortName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String safeKey(String value) {
        if (value == null || value.isBlank()) return "";
        String checked = value.trim();
        if (checked.length() > 128 || !checked.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid messageKey");
        }
        return checked;
    }
}
