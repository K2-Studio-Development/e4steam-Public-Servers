package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;

/** Type-aware validation in addition to the strict registry-contract constructors. */
public final class MetadataValidator {
    private MetadataValidator() { }

    public static ListingMetadata validate(PublicServerType type, ListingMetadata metadata) {
        if (type == null || metadata == null) throw new NullPointerException("publication metadata");
        if (type == PublicServerType.INTEGRATED_WORLD && !PublicServersAddon.validWorldCapacity(metadata.capacity())) {
            throw new IllegalArgumentException("integrated world capacity must be between 2 and 64");
        }
        return new ListingMetadata(metadata.schemaVersion(), metadata.name(), metadata.description(),
                metadata.tags(), metadata.minecraftVersion(), metadata.loader(), metadata.e4steamVersion(),
                metadata.coreWireProtocol(), metadata.addonVersion(), metadata.directoryProtocol(),
                metadata.players(), metadata.capacity(), metadata.accessMode(), metadata.requiredAddons());
    }
}
