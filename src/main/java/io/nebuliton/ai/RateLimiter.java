package io.nebuliton.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private final Duration cooldown;
    private final ConcurrentHashMap<String, Instant> lastHit = new ConcurrentHashMap<>();

    public RateLimiter(Duration cooldown) {
        this.cooldown = cooldown == null ? Duration.ZERO : cooldown;
    }

    public boolean allow(long guildId, long userId) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return true;
        }
        String key = guildId + ":" + userId;
        Instant now = Instant.now();
        Instant previous = lastHit.get(key);
        if (previous == null || previous.plus(cooldown).isBefore(now)) {
            lastHit.put(key, now);
            return true;
        }
        return false;
    }
}
