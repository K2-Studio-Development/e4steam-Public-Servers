package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.TextPolicy;

/** Builds the public presentation name without exposing an identity token. */
final class ServerDisplayName {
    static final String FALLBACK = "Public e4steam world";

    private ServerDisplayName() {
    }

    static String fromCreator(String creatorName) {
        if (creatorName == null || creatorName.isBlank()) return FALLBACK;
        String trimmed = creatorName.trim();
        int creatorLimit = 48 - " server".length();
        if (trimmed.length() > creatorLimit) trimmed = trimmed.substring(0, creatorLimit);
        try {
            return TextPolicy.title(trimmed + " server");
        } catch (IllegalArgumentException failure) {
            return FALLBACK;
        }
    }
}
