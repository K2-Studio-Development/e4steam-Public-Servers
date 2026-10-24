package dev.k2studio.e4steam.publicservers.registry;

/** A safe HTTP-facing failure. No constructor accepts arbitrary remote text. */
public final class RegistryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;
    private final String messageKey;
    private final boolean retryable;
    private final long retryAfterSeconds;

    private RegistryException(int status, String code, String messageKey,
                              boolean retryable, long retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.messageKey = messageKey;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RegistryException badRequest() {
        return new RegistryException(400, "bad_request", "registry.error.bad_request", false, 0);
    }

    public static RegistryException unauthorized() {
        return new RegistryException(401, "unauthorized", "registry.error.unauthorized", false, 0);
    }

    public static RegistryException forbidden() {
        return new RegistryException(403, "forbidden", "registry.error.forbidden", false, 0);
    }

    public static RegistryException notFound() {
        return new RegistryException(404, "not_found", "registry.error.not_found", false, 0);
    }

    public static RegistryException methodNotAllowed() {
        return new RegistryException(405, "method_not_allowed", "registry.error.method_not_allowed", false, 0);
    }

    public static RegistryException conflict() {
        return new RegistryException(409, "revision_conflict", "registry.error.revision_conflict", false, 0);
    }

    public static RegistryException replay() {
        return new RegistryException(409, "replay_rejected", "registry.error.replay_rejected", false, 0);
    }

    public static RegistryException tooLarge() {
        return new RegistryException(413, "body_too_large", "registry.error.body_too_large", false, 0);
    }

    public static RegistryException rateLimited(long retryAfterSeconds) {
        return new RegistryException(429, "rate_limited", "registry.error.rate_limited", true,
                Math.max(1, retryAfterSeconds));
    }

    public static RegistryException timeout() {
        return new RegistryException(504, "request_timeout", "registry.error.request_timeout", true, 0);
    }

    public static RegistryException unavailable() {
        return new RegistryException(503, "registry_unavailable", "registry.error.unavailable", true, 0);
    }

    public int status() { return status; }
    public String code() { return code; }
    public String messageKey() { return messageKey; }
    public boolean retryable() { return retryable; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
