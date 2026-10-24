package dev.k2studio.e4steam.publicservers.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe deterministic clock for lease, cache and expiry tests. */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant now) {
        this(now, ZoneId.of("UTC"));
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = new AtomicReference<>(Objects.requireNonNull(now, "now"));
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public void advance(Duration duration) {
        if (duration == null || duration.isNegative()) throw new IllegalArgumentException("duration");
        now.updateAndGet(value -> value.plus(duration));
    }

    public void set(Instant value) {
        now.set(Objects.requireNonNull(value, "value"));
    }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId value) { return new MutableClock(now.get(), value); }

    @Override public Instant instant() { return now.get(); }
}
