package redis.util;

/**
 * Interface for time querying to allow deterministic testing of expiration.
 */
@FunctionalInterface
public interface Clock {

    Clock SYSTEM = System::currentTimeMillis;

    long currentTimeMillis();

    default long nanoTime() {
        return System.nanoTime();
    }
}
