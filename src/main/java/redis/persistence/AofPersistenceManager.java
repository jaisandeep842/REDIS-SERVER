package redis.persistence;

import redis.command.CommandContext;
import redis.command.CommandHandler;
import redis.config.ServerConfig;
import redis.protocol.ProtocolException;
import redis.protocol.RespParser;
import redis.protocol.RespSerializer;
import redis.protocol.RespValue;
import redis.storage.DatabaseManager;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-Only File (AOF) persistence manager.
 * Logs mutating commands in standard RESP array format and replays them on startup.
 * Safely recovers from trailing corrupt or truncated records.
 */
public class AofPersistenceManager implements PersistenceManager {

    private static final Logger LOGGER = Logger.getLogger(AofPersistenceManager.class.getName());

    private final ServerConfig config;
    private final DatabaseManager databaseManager;
    private final CommandHandler commandHandler;
    private final ReentrantLock fileLock = new ReentrantLock();

    private FileOutputStream fileOutputStream;
    private BufferedOutputStream bufferedOutputStream;
    private int currentRecordedDb = -1;
    private volatile boolean closed = false;

    public AofPersistenceManager(ServerConfig config, DatabaseManager databaseManager, CommandHandler commandHandler) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.commandHandler = commandHandler;
    }

    @Override
    public void open() throws IOException {
        fileLock.lock();
        try {
            ensureWriterOpen();
        } finally {
            fileLock.unlock();
        }
    }

    private void ensureWriterOpen() throws IOException {
        if (!config.isPersistenceEnabled()) {
            return;
        }
        if (bufferedOutputStream == null) {
            Path dir = config.getDataDirectory();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path aofPath = config.getAofPath();
            this.fileOutputStream = new FileOutputStream(aofPath.toFile(), true);
            this.bufferedOutputStream = new BufferedOutputStream(fileOutputStream, 64 * 1024);
        }
    }

    @Override
    public boolean isEnabled() {
        return config.isPersistenceEnabled() && !closed;
    }

    @Override
    public void recordCommand(int dbIndex, List<RespValue> command) {
        if (!isEnabled()) {
            return;
        }

        fileLock.lock();
        try {
            ensureWriterOpen();

            // If switching database, record SELECT dbIndex first
            if (dbIndex != currentRecordedDb) {
                RespValue selectCmd = RespValue.array(Arrays.asList(
                        RespValue.bulkString("SELECT"),
                        RespValue.bulkString(Integer.toString(dbIndex))
                ));
                RespSerializer.write(selectCmd, bufferedOutputStream);
                currentRecordedDb = dbIndex;
            }

            RespValue cmdValue = RespValue.array(command);
            RespSerializer.write(cmdValue, bufferedOutputStream);

            // Crucial: flush the user-space buffer to the underlying FileOutputStream/OS handle
            bufferedOutputStream.flush();

            if (config.isAofSyncAlways()) {
                fileOutputStream.getFD().sync();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to append command to AOF persistence log", e);
            throw new PersistenceException("Failed to append command to AOF persistence log: " + e.getMessage(), e);
        } finally {
            fileLock.unlock();
        }
    }

    @Override
    public void flush() throws IOException {
        fileLock.lock();
        try {
            if (bufferedOutputStream != null) {
                bufferedOutputStream.flush();
                if (fileOutputStream != null) {
                    fileOutputStream.getFD().sync();
                }
            }
        } finally {
            fileLock.unlock();
        }
    }

    @Override
    public int recover() throws IOException {
        if (!config.isPersistenceEnabled()) {
            return 0;
        }

        Path aofPath = config.getAofPath();
        if (!Files.exists(aofPath) || Files.size(aofPath) == 0) {
            LOGGER.info("No existing AOF file found at " + aofPath + ", starting with empty store.");
            return 0;
        }

        LOGGER.info("Recovering data from AOF file: " + aofPath + " (size: " + Files.size(aofPath) + " bytes)");

        int recoveredCount = 0;
        long lastValidPosition = 0;
        CommandContext recoveryContext = new CommandContext(databaseManager, config, null);

        // Open a separate read stream with counting capability
        try (CountingInputStream cis = new CountingInputStream(new BufferedInputStream(Files.newInputStream(aofPath)))) {
            RespParser parser = new RespParser(cis);

            while (true) {
                lastValidPosition = cis.getBytesRead();
                RespValue command;
                try {
                    command = parser.parseNext();
                } catch (EOFException | ProtocolException e) {
                    LOGGER.warning("Encountered corrupt or incomplete trailing record in AOF at offset "
                            + lastValidPosition + ": " + e.getMessage() + ". Recovered " + recoveredCount + " commands successfully.");
                    break;
                }

                if (command == null) {
                    // Clean EOF
                    lastValidPosition = cis.getBytesRead();
                    break;
                }

                commandHandler.handle(recoveryContext, command);
                recoveredCount++;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during AOF recovery", e);
            throw new IOException("Failed to recover data from AOF", e);
        }

        LOGGER.info("AOF recovery complete. Total valid commands replayed: " + recoveredCount);
        this.currentRecordedDb = recoveryContext.getCurrentDbIndex();
        return recoveredCount;
    }

    @Override
    public void close() throws IOException {
        fileLock.lock();
        try {
            closed = true;
            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.flush();
                    if (fileOutputStream != null) {
                        fileOutputStream.getFD().sync();
                    }
                } finally {
                    bufferedOutputStream.close();
                    bufferedOutputStream = null;
                    fileOutputStream = null;
                }
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * InputStream wrapper that counts the number of bytes read.
     */
    private static class CountingInputStream extends FilterInputStream {
        private long bytesRead = 0;

        public CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
            }
            return n;
        }

        public long getBytesRead() {
            return bytesRead;
        }
    }
}
