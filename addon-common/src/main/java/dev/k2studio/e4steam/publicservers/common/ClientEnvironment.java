package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.TextPolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable local compatibility inputs; no Minecraft or loader classes cross this boundary. */
public record ClientEnvironment(
        String minecraftVersion,
        String loader,
        String e4steamVersion,
        int coreWireProtocol,
        int directoryProtocol,
        Map<String, String> installedAddons
) {
    public ClientEnvironment {
        minecraftVersion = TextPolicy.identifier(minecraftVersion, "minecraftVersion", 32);
        loader = TextPolicy.identifier(loader.toLowerCase(Locale.ROOT), "loader", 24);
        e4steamVersion = TextPolicy.identifier(e4steamVersion, "e4steamVersion", 32);
        if (coreWireProtocol < 1 || directoryProtocol < 1) {
            throw new IllegalArgumentException("invalid protocol version");
        }
        if (installedAddons == null || installedAddons.size() > 256) {
            throw new IllegalArgumentException("invalid installed addon inventory");
        }
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        installedAddons.forEach((id, version) -> safe.put(
                TextPolicy.identifier(id, "addonId", 96),
                TextPolicy.identifier(version, "addonVersion", 64)));
        installedAddons = Collections.unmodifiableMap(safe);
    }
}
