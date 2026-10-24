package dev.k2studio.e4steam.publicservers.client;

/** Sanitized registry failure. Response bodies, targets and credentials are never retained. */
public final class DirectoryClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Stable categories suitable for UI state and bounded retry policy. */
    public enum Category {
        CLOSED,
        QUEUE_FULL,
        TIMEOUT,
        NETWORK,
        PROTOCOL,
        BAD_REQUEST,
        AUTHENTICATION,
        NOT_FOUND,
        CONFLICT,
        TOO_LARGE,
        RATE_LIMITED,
        UNAVAILABLE,
        SERVICE
    }

    private final Category category;
    private final int statusCode;
    private final boolean retryable;
    private final long retryAfterSeconds;
    private final String requestId;

    /** Creates a failure containing only allowlisted diagnostic fields. */
    public DirectoryClientException(Category category, int statusCode, boolean retryable,
                                    long retryAfterSeconds, String requestId) {
        this(category, statusCode, retryable, retryAfterSeconds, requestId, null);
    }

    /** Creates a failure with a local cause; the cause text is not copied into the message. */
    public DirectoryClientException(Category category, int statusCode, boolean retryable,
                                    long retryAfterSeconds, String requestId, Throwable cause) {
        super("public directory request failed: " + category.name().toLowerCase(java.util.Locale.ROOT),
                cause);
        if (category == null) throw new NullPointerException("category");
        if (statusCode < 0 || statusCode > 999) throw new IllegalArgumentException("invalid statusCode");
        if (retryAfterSeconds < 0L || retryAfterSeconds > 3_600L) {
            throw new IllegalArgumentException("invalid retryAfterSeconds");
        }
        this.category = category;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.requestId = safeRequestId(requestId);
    }

    public Category category() { return category; }

    public int statusCode() { return statusCode; }

    public boolean retryable() { return retryable; }

    public long retryAfterSeconds() { return retryAfterSeconds; }

    public String requestId() { return requestId; }

    private static String safeRequestId(String value) {
        if (value == null || value.isBlank()) return "";
        String checked = value.trim();
        return checked.length() <= 96 && checked.matches("[A-Za-z0-9._-]+") ? checked : "";
    }
}
