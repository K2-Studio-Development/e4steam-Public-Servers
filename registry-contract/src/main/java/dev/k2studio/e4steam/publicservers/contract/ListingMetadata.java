package dev.k2studio.e4steam.publicservers.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Immutable, display-safe metadata shared by both publication types. */
public record ListingMetadata(
        int schemaVersion,
        String name,
        String description,
        List<String> tags,
        String minecraftVersion,
        String loader,
        String e4steamVersion,
        int coreWireProtocol,
        String addonVersion,
        int directoryProtocol,
        int players,
        int capacity,
        String accessMode,
        Map<String, String> requiredAddons
) {
    public ListingMetadata {
        if (schemaVersion != DirectoryProtocol.METADATA_SCHEMA) {
            throw new IllegalArgumentException("unsupported metadata schema");
        }
        name = TextPolicy.title(name);
        description = TextPolicy.description(description);
        if (tags == null || tags.size() > DirectoryProtocol.MAX_TAGS) {
            throw new IllegalArgumentException("invalid tags");
        }
        LinkedHashSet<String> safeTags = new LinkedHashSet<>();
        for (String tag : tags) safeTags.add(TextPolicy.tag(tag));
        tags = Collections.unmodifiableList(new ArrayList<>(safeTags));
        minecraftVersion = TextPolicy.identifier(minecraftVersion, "minecraftVersion", 32);
        loader = TextPolicy.identifier(loader.toLowerCase(java.util.Locale.ROOT), "loader", 24);
        e4steamVersion = TextPolicy.identifier(e4steamVersion, "e4steamVersion", 32);
        addonVersion = TextPolicy.identifier(addonVersion, "addonVersion", 32);
        accessMode = TextPolicy.identifier(accessMode, "accessMode", 64);
        if (coreWireProtocol < 1 || directoryProtocol != DirectoryProtocol.VERSION) {
            throw new IllegalArgumentException("invalid protocol version");
        }
        if (players < 0 || capacity < 1 || capacity > 256 || players > capacity) {
            throw new IllegalArgumentException("invalid player capacity");
        }
        if (requiredAddons == null || requiredAddons.size() > DirectoryProtocol.MAX_REQUIRED_ADDONS) {
            throw new IllegalArgumentException("invalid required addons");
        }
        LinkedHashMap<String, String> safeAddons = new LinkedHashMap<>();
        requiredAddons.forEach((id, version) -> safeAddons.put(
                TextPolicy.identifier(id, "addonId", 96),
                TextPolicy.identifier(version, "addonVersionRange", 64)
        ));
        requiredAddons = Collections.unmodifiableMap(safeAddons);
    }
}
