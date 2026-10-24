package dev.k2studio.e4steam.publicservers.registry;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Environment-only backend configuration. Secret values are never included in {@link #toString()}. */
public final class RegistryServerConfig {
    private final InetAddress bindAddress;
    private final int port;
    private final Path databasePath;
    private final String audience;
    private final byte[] developmentHmacKey;
    private final byte[] moderatorTokenHash;
    private final byte[] privacyKey;
    private final int requestWorkers;
    private final int requestQueueCapacity;
    private final Duration requestTimeout;
    private final Duration cleanupInterval;
    private final Duration challengeTtl;
    private final Duration receiptTtl;
    private final Duration leaseTtl;
    private final Duration joinHandleTtl;
    private final Duration auditRetention;

    private RegistryServerConfig(InetAddress bindAddress, int port, Path databasePath,
                                 String audience, byte[] developmentHmacKey,
                                 byte[] moderatorTokenHash, byte[] privacyKey,
                                 int requestWorkers, int requestQueueCapacity,
                                 Duration requestTimeout, Duration cleanupInterval,
                                 Duration challengeTtl, Duration receiptTtl,
                                 Duration leaseTtl, Duration joinHandleTtl,
                                 Duration auditRetention) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (!bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("registry must bind to loopback behind a TLS terminator");
        }
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("invalid port");
        this.port = port;
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        this.audience = Objects.requireNonNull(audience, "audience");
        this.developmentHmacKey = cloneOrNull(developmentHmacKey);
        this.moderatorTokenHash = cloneOrNull(moderatorTokenHash);
        if (privacyKey == null || privacyKey.length < 32) throw new IllegalArgumentException("privacy key too short");
        this.privacyKey = privacyKey.clone();
        if (requestWorkers < 2 || requestWorkers > 64
                || requestQueueCapacity < requestWorkers || requestQueueCapacity > 4_096) {
            throw new IllegalArgumentException("invalid request executor bounds");
        }
        this.requestWorkers = requestWorkers;
        this.requestQueueCapacity = requestQueueCapacity;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.cleanupInterval = Objects.requireNonNull(cleanupInterval, "cleanupInterval");
        this.challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl");
        this.receiptTtl = Objects.requireNonNull(receiptTtl, "receiptTtl");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.joinHandleTtl = Objects.requireNonNull(joinHandleTtl, "joinHandleTtl");
        this.auditRetention = Objects.requireNonNull(auditRetention, "auditRetention");
    }

    public static RegistryServerConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        InetAddress address = loopback(environment.get("E4STEAM_REGISTRY_BIND"));
        int port = integer(environment, "E4STEAM_REGISTRY_PORT", 8_090, 0, 65_535);
        Path database = Path.of(environment.getOrDefault("E4STEAM_REGISTRY_DATABASE",
                Path.of("data", "registry.sqlite3").toString()));
        String audience = environment.getOrDefault("E4STEAM_REGISTRY_AUDIENCE",
                "e4steam-registry-local");
        byte[] developmentKey = optionalKey(environment.get("E4STEAM_REGISTRY_DEV_HMAC_KEY"));
        String moderatorToken = environment.get("E4STEAM_REGISTRY_MODERATOR_TOKEN");
        byte[] moderatorHash = null;
        if (moderatorToken != null && !moderatorToken.isBlank()) {
            if (moderatorToken.length() < 32 || moderatorToken.length() > 512) {
                throw new IllegalArgumentException("moderator credential has an invalid length");
            }
            moderatorHash = SecureTokens.sha256(moderatorToken);
        }
        byte[] privacyKey = optionalKey(environment.get("E4STEAM_REGISTRY_PRIVACY_KEY"));
        if (privacyKey == null) privacyKey = SecureTokens.randomBytes(32);
        int workers = integer(environment, "E4STEAM_REGISTRY_WORKERS", 8, 2, 64);
        int queue = integer(environment, "E4STEAM_REGISTRY_QUEUE", 128, workers, 4_096);
        int timeoutSeconds = integer(environment, "E4STEAM_REGISTRY_REQUEST_TIMEOUT_SECONDS", 5, 1, 30);
        return new RegistryServerConfig(address, port, database, audience, developmentKey,
                moderatorHash, privacyKey, workers, queue, Duration.ofSeconds(timeoutSeconds),
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(90), Duration.ofSeconds(15), Duration.ofDays(30));
    }

    public InetAddress bindAddress() { return bindAddress; }
    public int port() { return port; }
    public Path databasePath() { return databasePath; }
    public String audience() { return audience; }
    public byte[] developmentHmacKey() { return cloneOrNull(developmentHmacKey); }
    public byte[] moderatorTokenHash() { return cloneOrNull(moderatorTokenHash); }
    public byte[] privacyKey() { return privacyKey.clone(); }
    public int requestWorkers() { return requestWorkers; }
    public int requestQueueCapacity() { return requestQueueCapacity; }
    public Duration requestTimeout() { return requestTimeout; }
    public Duration cleanupInterval() { return cleanupInterval; }
    public Duration challengeTtl() { return challengeTtl; }
    public Duration receiptTtl() { return receiptTtl; }
    public Duration leaseTtl() { return leaseTtl; }
    public Duration joinHandleTtl() { return joinHandleTtl; }
    public Duration auditRetention() { return auditRetention; }

    public boolean developmentAttestationConfigured() { return developmentHmacKey != null; }
    public boolean moderationConfigured() { return moderatorTokenHash != null; }

    @Override
    public String toString() {
        return "RegistryServerConfig{bind=loopback, port=" + port
                + ", database=" + databasePath.getFileName()
                + ", developmentAttestation=" + developmentAttestationConfigured()
                + ", moderation=" + moderationConfigured()
                + ", secrets=redacted}";
    }

    private static InetAddress loopback(String configured) {
        if (configured == null || configured.isBlank()) return InetAddress.getLoopbackAddress();
        try {
            InetAddress address = InetAddress.getByName(configured);
            if (!address.isLoopbackAddress()) {
                throw new IllegalArgumentException("non-loopback bind is forbidden");
            }
            return address;
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("invalid bind address", failure);
        }
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue,
                               int minimum, int maximum) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw new IllegalArgumentException("invalid " + name);
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid " + name, failure);
        }
    }

    private static byte[] optionalKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        byte[] key = SecureTokens.decodeBase64(raw, 64);
        if (key.length < 32) throw new IllegalArgumentException("configured key is too short");
        return key;
    }

    private static byte[] cloneOrNull(byte[] value) {
        return value == null ? null : value.clone();
    }
}
