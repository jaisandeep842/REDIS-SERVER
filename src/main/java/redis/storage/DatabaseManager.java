package redis.storage;

import redis.util.Clock;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages multiple logical Redis databases (0 to dbCount-1) and runs active expiration.
 */
public class DatabaseManager {

    private final int dbCount;
    private final Store[] databases;
    private final Clock clock;
    private ScheduledExecutorService expireScheduler;

    public DatabaseManager(int dbCount) {
        this(dbCount, Clock.SYSTEM);
    }

    public DatabaseManager(int dbCount, Clock clock) {
        if (dbCount <= 0 || dbCount > 1024) {
            throw new IllegalArgumentException("dbCount must be between 1 and 1024");
        }
        this.dbCount = dbCount;
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.databases = new Store[dbCount];
        for (int i = 0; i < dbCount; i++) {
            this.databases[i] = new InMemoryStore(clock);
        }
    }

    public Store getDatabase(int index) {
        if (index < 0 || index >= dbCount) {
            throw new IllegalArgumentException("ERR DB index is out of range");
        }
        return databases[index];
    }

    public int getDbCount() {
        return dbCount;
    }

    public Clock getClock() {
        return clock;
    }

    public synchronized void startActiveExpiration(long periodMs) {
        if (expireScheduler == null || expireScheduler.isShutdown()) {
            expireScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-expire-sweeper");
                t.setDaemon(true);
                return t;
            });
            expireScheduler.scheduleWithFixedDelay(this::runActiveExpireCycle, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stopActiveExpiration() {
        if (expireScheduler != null) {
            expireScheduler.shutdownNow();
            expireScheduler = null;
        }
    }

    public void runActiveExpireCycle() {
        for (Store db : databases) {
            db.activeExpireCycle(20);
        }
    }

    public void flushAll() {
        for (Store db : databases) {
            db.flushDb();
        }
    }
}
