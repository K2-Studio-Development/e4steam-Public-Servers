package dev.k2studio.e4steam.publicservers.common;

import link.e4steam.api.ApiError;
import link.e4steam.api.ApiErrorCode;

/** Sanitized failure copied from a public e4steam ApiResult. */
public final class CoreDirectoryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ApiErrorCode code;
    private final String messageKey;
    private final String category;

    CoreDirectoryException(ApiError error) {
        super("e4steam public-directory operation failed: " + error.code().name().toLowerCase(java.util.Locale.ROOT));
        this.code = error.code();
        this.messageKey = error.messageKey();
        this.category = error.causeCategory();
    }

    public String category() { return category; }
    public ApiErrorCode code() { return code; }

    public String messageKey() { return messageKey; }
}
