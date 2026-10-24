package dev.k2studio.e4steam.publicservers.common;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/** Immutable bounded addon configuration. No registry endpoint is invented by default. */
public final class PublicServersConfig {
    private final URI registryOrigin;
    private final boolean steamDirectory;
    private final boolean developmentLoopback;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration cacheTtl;
    private final int httpWorkers;
    private final int queuedRequests;
    private final int cacheEntries;
    private final int pageSize;
    private final int maximumPages;
    private final boolean integratedPublicationEnabled;
    private final boolean dedicatedPublicationEnabled;

    private PublicServersConfig(Builder builder) {
        this.steamDirectory = builder.steamDirectory;
        this.developmentLoopback = builder.developmentLoopback;
        this.registryOrigin = validateOrigin(builder.registryOrigin, developmentLoopback);
        if (steamDirectory && !SteamDirectoryClient.ORIGIN.equals(registryOrigin))
            throw new IllegalArgumentException("Invalid Steam directory scope");
        this.connectTimeout = duration(builder.connectTimeout, "connectTimeout", 1L, 15L);
        this.requestTimeout = duration(builder.requestTimeout, "requestTimeout", 1L, 30L);
        this.cacheTtl = duration(builder.cacheTtl, "cacheTtl", 5L, 300L);
        this.httpWorkers = range(builder.httpWorkers, "httpWorkers", 1, 8);
        this.queuedRequests = range(builder.queuedRequests, "queuedRequests", 1, 256);
        this.cacheEntries = range(builder.cacheEntries, "cacheEntries", 1, 1_000);
        this.pageSize = range(builder.pageSize, "pageSize", 1, 50);
        this.maximumPages = range(builder.maximumPages, "maximumPages", 1, 20);
        if ((long) pageSize * maximumPages > cacheEntries) {
            throw new IllegalArgumentException("pagination exceeds cache capacity");
        }
        this.integratedPublicationEnabled = builder.integratedPublicationEnabled;
        this.dedicatedPublicationEnabled = builder.dedicatedPublicationEnabled;
    }

    public static Builder builder() { return new Builder(); }

    public static PublicServersConfig notConfigured() { return builder().build(); }

    public boolean configured() { return registryOrigin != null; }

    public URI registryOrigin() { return registryOrigin; }
    public boolean steamDirectory() { return steamDirectory; }

    public boolean developmentLoopback() { return developmentLoopback; }

    public Duration connectTimeout() { return connectTimeout; }

    public Duration requestTimeout() { return requestTimeout; }

    public Duration cacheTtl() { return cacheTtl; }

    public int httpWorkers() { return httpWorkers; }

    public int queuedRequests() { return queuedRequests; }

    public int cacheEntries() { return cacheEntries; }

    public int pageSize() { return pageSize; }

    public int maximumPages() { return maximumPages; }

    public boolean integratedPublicationEnabled() { return integratedPublicationEnabled; }

    public boolean dedicatedPublicationEnabled() { return dedicatedPublicationEnabled; }

    /** Builder with conservative bounded defaults and publication disabled. */
    public static final class Builder {
        private URI registryOrigin;
        private boolean steamDirectory;
        private boolean developmentLoopback;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration cacheTtl = Duration.ofSeconds(60);
        private int httpWorkers = 2;
        private int queuedRequests = 32;
        private int cacheEntries = 200;
        private int pageSize = 50;
        private int maximumPages = 4;
        private boolean integratedPublicationEnabled;
        private boolean dedicatedPublicationEnabled;

        public Builder registryOrigin(URI value) { registryOrigin = value; return this; }
        public Builder steamDirectory(boolean value) { steamDirectory = value; return this; }
        public Builder developmentLoopback(boolean value) { developmentLoopback = value; return this; }
        public Builder connectTimeout(Duration value) { connectTimeout = value; return this; }
        public Builder requestTimeout(Duration value) { requestTimeout = value; return this; }
        public Builder cacheTtl(Duration value) { cacheTtl = value; return this; }
        public Builder httpWorkers(int value) { httpWorkers = value; return this; }
        public Builder queuedRequests(int value) { queuedRequests = value; return this; }
        public Builder cacheEntries(int value) { cacheEntries = value; return this; }
        public Builder pageSize(int value) { pageSize = value; return this; }
        public Builder maximumPages(int value) { maximumPages = value; return this; }
        public Builder integratedPublicationEnabled(boolean value) { integratedPublicationEnabled = value; return this; }
        public Builder dedicatedPublicationEnabled(boolean value) { dedicatedPublicationEnabled = value; return this; }
        public PublicServersConfig build() { return new PublicServersConfig(this); }
    }

    private static URI validateOrigin(URI origin, boolean development) {
        if (origin == null) return null;
        String scheme = origin.getScheme();
        boolean secure = "https".equalsIgnoreCase(scheme);
        boolean loopback = development && "http".equalsIgnoreCase(scheme)
                && loopbackLiteral(origin.getHost());
        if ((!secure && !loopback) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || (origin.getPath() != null && !origin.getPath().isEmpty()
                && !"/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("registryOrigin must be an HTTPS origin");
        }
        String host = origin.getHost().toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0) host = '[' + host + ']';
        return URI.create((secure ? "https" : "http") + "://" + host
                + (origin.getPort() < 0 ? "" : ":" + origin.getPort()));
    }

    private static boolean loopbackLiteral(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1"));
    }

    private static Duration duration(Duration value, String field, long minimum, long maximum) {
        if (value == null || value.compareTo(Duration.ofSeconds(minimum)) < 0
                || value.compareTo(Duration.ofSeconds(maximum)) > 0) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return value;
    }

    private static int range(int value, String field, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return value;
    }
}
