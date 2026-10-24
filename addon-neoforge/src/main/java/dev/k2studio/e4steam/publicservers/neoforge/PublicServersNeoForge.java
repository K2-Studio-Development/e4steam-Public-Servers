package dev.k2studio.e4steam.publicservers.neoforge;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge discovery entrypoint. Addon lifecycle, commands and publication are
 * owned by e4steam's public addon API; this class deliberately has no client
 * imports so the same JAR can be loaded by a headless dedicated server.
 */
@Mod(PublicServersNeoForge.MOD_ID)
public final class PublicServersNeoForge {
    public static final String MOD_ID = "e4steam_public_servers";
}
