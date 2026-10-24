package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable local-only catalog filter, including privacy-preserving moderation exclusions. */
public record PublicServersFilter(
        String search,
        PublicServerType type,
        boolean compatibleOnly,
        String minecraftVersion,
        String loader,
        boolean hasSlotsOnly,
        Set<String> hiddenListings,
        Set<String> blockedOwners
) {
    public PublicServersFilter {
        search = normalized(search, 64);
        minecraftVersion = normalized(minecraftVersion, 32);
        loader = normalized(loader, 24);
        hiddenListings = immutable(hiddenListings, 1_000, "hiddenListings");
        blockedOwners = immutable(blockedOwners, 1_000, "blockedOwners");
    }

    public static PublicServersFilter all() {
        return new PublicServersFilter("", null, false, "", "", false,
                Collections.emptySet(), Collections.emptySet());
    }

    public boolean matches(PublicServerEntry entry, Compatibility compatibility) {
        if (entry == null || compatibility == null) return false;
        if (hiddenListings.contains(entry.listingId()) || blockedOwners.contains(entry.ownerRef())) return false;
        if (type != null && entry.type() != type) return false;
        if (compatibleOnly && !compatibility.compatible()) return false;
        if (hasSlotsOnly && entry.metadata().players() >= entry.metadata().capacity()) return false;
        if (!minecraftVersion.isEmpty() && !entry.metadata().minecraftVersion().equals(minecraftVersion)) return false;
        if (!loader.isEmpty() && !entry.metadata().loader().equalsIgnoreCase(loader)) return false;
        if (search.isEmpty()) return true;
        String haystack = normalized(entry.metadata().name() + " " + entry.metadata().description()
                + " " + String.join(" ", entry.metadata().tags()), 512);
        return haystack.contains(search);
    }

    private static Set<String> immutable(Set<String> values, int maximum, String field) {
        if (values == null || values.size() > maximum) throw new IllegalArgumentException("invalid " + field);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException("invalid " + field + " value");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String normalized(String value, int maximum) {
        if (value == null) return "";
        String result = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (result.length() > maximum) throw new IllegalArgumentException("filter text is too long");
        return result;
    }
}
