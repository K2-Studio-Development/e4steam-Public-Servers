package dev.k2studio.e4steam.publicservers.contract;

import java.time.Instant;

/** JSON-friendly epoch-millisecond timestamp with strict range checks. */
public record InstantStamp(long epochMillis) {
    public InstantStamp {
        if (epochMillis < 1_577_836_800_000L || epochMillis > 4_102_444_800_000L) {
            throw new IllegalArgumentException("timestamp outside supported range");
        }
    }

    public static InstantStamp of(Instant value) {
        return new InstantStamp(value.toEpochMilli());
    }

    public Instant instant() {
        return Instant.ofEpochMilli(epochMillis);
    }
}
