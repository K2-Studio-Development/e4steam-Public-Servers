package dev.k2studio.e4steam.publicservers.contract;

/** Stable registry protocol constants, versioned independently from the addon. */
public final class DirectoryProtocol {
    public static final int VERSION = 1;
    public static final int METADATA_SCHEMA = 1;
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_TAGS = 5;
    public static final int MAX_REQUIRED_ADDONS = 32;
    public static final long MIN_LEASE_SECONDS = 30;
    public static final long MAX_LEASE_SECONDS = 180;
    public static final long MAX_STALE_MILLIS = 5 * 60 * 1000L;

    private DirectoryProtocol() {
    }
}
