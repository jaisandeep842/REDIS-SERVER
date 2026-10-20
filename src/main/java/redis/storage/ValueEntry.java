package redis.storage;

import redis.util.Clock;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a value stored in Redis, along with its type and optional expiration time.
 */
public class ValueEntry {

    private final RedisType type;
    private final Object data;
    private volatile long expireAtEpochMs; // -1 if no expiration

    public ValueEntry(RedisType type, Object data) {
        this(type, data, -1);
    }

    public ValueEntry(RedisType type, Object data, long expireAtEpochMs) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.expireAtEpochMs = expireAtEpochMs;
    }

    public RedisType getType() {
        return type;
    }

    public Object getData() {
        return data;
    }

    public long getExpireAtEpochMs() {
        return expireAtEpochMs;
    }

    public void setExpireAtEpochMs(long expireAtEpochMs) {
        this.expireAtEpochMs = expireAtEpochMs;
    }

    public boolean hasExpired(Clock clock) {
        return expireAtEpochMs > 0 && clock.currentTimeMillis() >= expireAtEpochMs;
    }

    public long getTtlSeconds(Clock clock) {
        if (expireAtEpochMs <= 0) {
            return -1; // No expiry set
        }
        long now = clock.currentTimeMillis();
        if (now >= expireAtEpochMs) {
            return -2; // Key expired
        }
        return (expireAtEpochMs - now + 999) / 1000;
    }

    public long getPttlMillis(Clock clock) {
        if (expireAtEpochMs <= 0) {
            return -1; // No expiry set
        }
        long now = clock.currentTimeMillis();
        if (now >= expireAtEpochMs) {
            return -2; // Key expired
        }
        return expireAtEpochMs - now;
    }
}
