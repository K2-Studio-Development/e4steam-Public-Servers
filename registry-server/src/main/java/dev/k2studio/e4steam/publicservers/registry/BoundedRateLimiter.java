package dev.k2studio.e4steam.publicservers.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-window limiter with an explicit maximum number of tracked keys. */
final class BoundedRateLimiter {
    private final int maximumKeys;
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(128, 0.75f, true);

    BoundedRateLimiter(int maximumKeys) {
        if (maximumKeys < 128) throw new IllegalArgumentException("rate limiter is too small");
        this.maximumKeys = maximumKeys;
    }

    synchronized Decision acquire(String scope, String clientKey, int limit, Duration duration,
                                  Instant now) {
        if (scope == null || clientKey == null || limit < 1 || duration == null
                || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("invalid rate limit request");
        }
        long nowMillis = now.toEpochMilli();
        long windowMillis = duration.toMillis();
        String key = scope + ':' + clientKey;
        Window current = windows.get(key);
        if (current == null || nowMillis >= current.endsAt) {
            makeRoom(nowMillis);
            windows.put(key, new Window(nowMillis + windowMillis, 1));
            return new Decision(true, 0);
        }
        if (current.count >= limit) {
            long remaining = current.endsAt - nowMillis;
            return new Decision(false, Math.max(1, (remaining + 999) / 1_000));
        }
        windows.put(key, new Window(current.endsAt, current.count + 1));
        return new Decision(true, 0);
    }

    synchronized int trackedKeys() {
        return windows.size();
    }

    private void makeRoom(long nowMillis) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window value = iterator.next().getValue();
            if (nowMillis >= value.endsAt) iterator.remove();
        }
        while (windows.size() >= maximumKeys) {
            Iterator<String> eldest = windows.keySet().iterator();
            if (!eldest.hasNext()) break;
            eldest.next();
            eldest.remove();
        }
    }

    record Decision(boolean allowed, long retryAfterSeconds) { }
    private record Window(long endsAt, int count) { }
}
