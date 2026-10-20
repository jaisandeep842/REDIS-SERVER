package redis.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Server configuration holding network, thread pool, database, and persistence settings.
 */
public class ServerConfig {

    private String host = "127.0.0.1";
    private int port = 6379;
    private int workerThreads = 16;
    private int maxDatabases = 16;
    private boolean persistenceEnabled = true;
    private Path dataDirectory = Paths.get("data");
    private String aofFilename = "appendonly.aof";
    private boolean aofSyncAlways = false;

    public ServerConfig() {}

    public static ServerConfig createDefault() {
        return new ServerConfig();
    }

    public String getHost() {
        return host;
    }

    public ServerConfig setHost(String host) {
        this.host = host;
        return this;
    }

    public int getPort() {
        return port;
    }

    public ServerConfig setPort(int port) {
        this.port = port;
        return this;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public ServerConfig setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public int getMaxDatabases() {
        return maxDatabases;
    }

    public ServerConfig setMaxDatabases(int maxDatabases) {
        this.maxDatabases = maxDatabases;
        return this;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public ServerConfig setPersistenceEnabled(boolean persistenceEnabled) {
        this.persistenceEnabled = persistenceEnabled;
        return this;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ServerConfig setDataDirectory(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        return this;
    }

    public String getAofFilename() {
        return aofFilename;
    }

    public ServerConfig setAofFilename(String aofFilename) {
        this.aofFilename = aofFilename;
        return this;
    }

    public Path getAofPath() {
        return dataDirectory.resolve(aofFilename);
    }

    public boolean isAofSyncAlways() {
        return aofSyncAlways;
    }

    public ServerConfig setAofSyncAlways(boolean aofSyncAlways) {
        this.aofSyncAlways = aofSyncAlways;
        return this;
    }
}
