package dev.k2studio.e4steam.publicservers.common;

import java.net.URI;

/**
 * Explicit process configuration used until Addon API configuration schemas can expose a
 * launcher-neutral config file. Steam discovery is the default; publication still
 * requires the host to select and confirm the addon's public access mode.
 */
final class PublicServersSystemConfiguration {
    static final String ORIGIN = "e4steam.publicServers.registryOrigin";
    static final String DEVELOPMENT_LOOPBACK = "e4steam.publicServers.allowDevelopmentLoopback";
    static final String PUBLISH_INTEGRATED = "e4steam.publicServers.publishIntegrated";
    static final String PUBLISH_DEDICATED = "e4steam.publicServers.publishDedicated";

    private PublicServersSystemConfiguration() { }

    static PublicServersConfig load() {
        String origin = property(ORIGIN);
        if (origin.isEmpty()) return PublicServersConfig.builder()
                .registryOrigin(SteamDirectoryClient.ORIGIN).steamDirectory(true)
                .integratedPublicationEnabled(true).maximumPages(1).build();
        return PublicServersConfig.builder()
                .registryOrigin(URI.create(origin))
                .developmentLoopback(booleanProperty(DEVELOPMENT_LOOPBACK))
                .integratedPublicationEnabled(booleanProperty(PUBLISH_INTEGRATED))
                .dedicatedPublicationEnabled(booleanProperty(PUBLISH_DEDICATED))
                .build();
    }

    private static String property(String key) {
        String value = System.getProperty(key, "");
        return value == null ? "" : value.trim();
    }

    private static boolean booleanProperty(String key) {
        String value = property(key);
        if (value.isEmpty() || value.equalsIgnoreCase("false")) return false;
        if (value.equalsIgnoreCase("true")) return true;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
