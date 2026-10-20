package redis.server;

import redis.command.CommandContext;
import redis.command.CommandHandler;
import redis.command.CommandRegistry;
import redis.config.ServerConfig;
import redis.persistence.AofPersistenceManager;
import redis.persistence.PersistenceManager;
import redis.storage.DatabaseManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core Redis-compatible TCP server managing client connections, thread pool,
 * storage, persistence lifecycle, and shutdown hooks.
 */
public class RedisServer {

    private static final Logger LOGGER = Logger.getLogger(RedisServer.class.getName());

    private final ServerConfig config;
    private final DatabaseManager databaseManager;
    private final CommandRegistry registry;
    private final CommandHandler commandHandler;
    private final PersistenceManager persistenceManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workerPool;
    private final Set<ClientHandler> activeClients = ConcurrentHashMap.newKeySet();
    private Thread shutdownHook;

    public RedisServer(ServerConfig config) {
        this(config, new DatabaseManager(config.getMaxDatabases()));
    }

    public RedisServer(ServerConfig config, DatabaseManager databaseManager) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.registry = CommandRegistry.createDefaultRegistry();
        this.commandHandler = new CommandHandler(registry);
        this.persistenceManager = new AofPersistenceManager(config, databaseManager, commandHandler);
    }

    public synchronized void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            LOGGER.info("Starting Redis server on " + config.getHost() + ":" + config.getPort() + "...");

            // 1. Recover persisted data and prepare writer
            if (config.isPersistenceEnabled()) {
                persistenceManager.recover();
                persistenceManager.open();
            }

            // 2. Start active expiration sweeper
            databaseManager.startActiveExpiration(100);

            // 3. Initialize bounded worker thread pool
            int maxWorkers = Math.max(2, config.getWorkerThreads());
            this.workerPool = new ThreadPoolExecutor(
                    2,
                    maxWorkers,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(1000),
                    new ThreadFactory() {
                        private int counter = 0;
                        @Override
                        public synchronized Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "redis-worker-" + (++counter));
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

            // 4. Bind server socket
            InetAddress bindAddr = InetAddress.getByName(config.getHost());
            this.serverSocket = new ServerSocket(config.getPort(), 128, bindAddr);
            LOGGER.info("Redis server initialized and ready to accept connections on port " + getPort());

            // 5. Register JVM shutdown hook
            this.shutdownHook = new Thread(this::stop, "redis-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // 6. Start accept thread
            this.acceptThread = new Thread(this::acceptConnections, "redis-accept-loop");
            this.acceptThread.start();
        }
    }

    private void acceptConnections() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                CommandContext context = new CommandContext(databaseManager, config, persistenceManager);

                final ClientHandler[] handlerRef = new ClientHandler[1];
                ClientHandler handler = new ClientHandler(
                        clientSocket,
                        commandHandler,
                        context,
                        () -> {
                            if (handlerRef[0] != null) {
                                activeClients.remove(handlerRef[0]);
                            }
                        }
                );
                handlerRef[0] = handler;
                activeClients.add(handler);
                workerPool.execute(handler);
            } catch (SocketException e) {
                if (!running.get()) {
                    LOGGER.info("Server socket closed during shutdown.");
                } else {
                    LOGGER.log(Level.WARNING, "Socket exception during accept", e);
                }
            } catch (RejectedExecutionException e) {
                LOGGER.warning("Worker thread pool rejected client connection: pool capacity exceeded.");
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "I/O error accepting connection", e);
                }
            }
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Shutting down Redis server...");

            // Unregister shutdown hook if stopping programmatically
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {}
                shutdownHook = null;
            }

            // Close server socket to break accept loop
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {}
            }

            // Close active client sockets
            for (ClientHandler client : activeClients) {
                client.closeSocket();
            }
            activeClients.clear();

            // Shutdown worker thread pool
            if (workerPool != null) {
                workerPool.shutdown();
                try {
                    if (!workerPool.awaitTermination(2, TimeUnit.SECONDS)) {
                        workerPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    workerPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Stop background expiration sweeper
            databaseManager.stopActiveExpiration();

            // Flush and close persistence manager
            if (persistenceManager != null) {
                try {
                    persistenceManager.flush();
                    persistenceManager.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing persistence manager", e);
                }
            }

            LOGGER.info("Redis server shutdown complete.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : config.getPort();
    }

    public ServerConfig getConfig() {
        return config;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public CommandHandler getCommandHandler() {
        return commandHandler;
    }

    public PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }
}
