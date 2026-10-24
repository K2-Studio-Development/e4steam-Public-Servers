package dev.k2studio.e4steam.publicservers.common;

/** Immutable compatibility/joinability result with a localization-safe reason. */
public record Compatibility(boolean compatible, boolean joinable, Reason reason, String detail) {
    public enum Reason {
        COMPATIBLE,
        STALE,
        FULL,
        MINECRAFT_VERSION,
        LOADER,
        E4STEAM_VERSION,
        CORE_PROTOCOL,
        DIRECTORY_PROTOCOL,
        MISSING_REQUIRED_ADDON,
        INCOMPATIBLE_REQUIRED_ADDON
    }

    public Compatibility {
        if (reason == null) throw new NullPointerException("reason");
        detail = detail == null ? "" : detail.trim();
        if (detail.length() > 96 || !detail.matches("[A-Za-z0-9._:+/-]*")) {
            throw new IllegalArgumentException("invalid compatibility detail");
        }
        if (joinable && !compatible) throw new IllegalArgumentException("joinable must be compatible");
    }

    public static Compatibility compatibleResult() {
        return new Compatibility(true, true, Reason.COMPATIBLE, "");
    }
}
