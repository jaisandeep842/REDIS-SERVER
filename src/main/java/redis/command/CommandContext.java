package redis.command;

import redis.config.ServerConfig;
import redis.persistence.PersistenceManager;
import redis.storage.DatabaseManager;
import redis.storage.Store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Encapsulates the execution context of a connected client session.
 */
public class CommandContext {

    private final DatabaseManager databaseManager;
    private final ServerConfig config;
    private final PersistenceManager persistenceManager;
    private final long connectionTimeMs;
    private final AtomicLong commandCount = new AtomicLong();

    private int currentDbIndex = 0;
    private String clientName = "";
    private boolean closeRequested = false;

    public CommandContext(DatabaseManager databaseManager, ServerConfig config, PersistenceManager persistenceManager) {
        this.databaseManager = databaseManager;
        this.config = config;
        this.persistenceManager = persistenceManager;
        this.connectionTimeMs = System.currentTimeMillis();
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public Store getStore() {
        return databaseManager.getDatabase(currentDbIndex);
    }

    public redis.util.Clock getClock() {
        return databaseManager.getClock();
    }

    public int getCurrentDbIndex() {
        return currentDbIndex;
    }

    public void setCurrentDbIndex(int currentDbIndex) {
        this.databaseManager.getDatabase(currentDbIndex); // validate index
        this.currentDbIndex = currentDbIndex;
    }

    public ServerConfig getConfig() {
        return config;
    }

    public PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public boolean isCloseRequested() {
        return closeRequested;
    }

    public void requestClose() {
        this.closeRequested = true;
    }

    public void incrementCommandCount() {
        commandCount.incrementAndGet();
    }

    public long getCommandCount() {
        return commandCount.get();
    }

    public long getConnectionTimeMs() {
        return connectionTimeMs;
    }
}
