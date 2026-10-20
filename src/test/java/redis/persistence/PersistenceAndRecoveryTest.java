package redis.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.command.CommandContext;
import redis.command.CommandHandler;
import redis.command.CommandRegistry;
import redis.config.ServerConfig;
import redis.protocol.RespValue;
import redis.storage.DatabaseManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PersistenceAndRecoveryTest {

    @TempDir
    Path tempDir;

    private ServerConfig config;
    private DatabaseManager dbManager;
    private CommandHandler handler;
    private AofPersistenceManager aof;
    private CommandContext context;

    @BeforeEach
    void setUp() {
        config = ServerConfig.createDefault();
        config.setDataDirectory(tempDir);
        config.setAofFilename("test_appendonly.aof");
        config.setPersistenceEnabled(true);
        config.setAofSyncAlways(false); // test realistic default behavior

        dbManager = new DatabaseManager(16);
        CommandRegistry registry = CommandRegistry.createDefaultRegistry();
        handler = new CommandHandler(registry);
        aof = new AofPersistenceManager(config, dbManager, handler);
        context = new CommandContext(dbManager, config, aof);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (aof != null) {
            aof.close();
        }
    }

    private RespValue execute(String... args) {
        List<RespValue> list = Arrays.stream(args).map(RespValue::bulkString).toList();
        return handler.handle(context, RespValue.array(list));
    }

    @Test
    @DisplayName("Write commands are logged and successfully recovered into fresh database")
    void testAofSaveAndRecovery() throws IOException {
        // Perform write operations across different databases
        execute("SET", "key1", "value1");
        execute("RPUSH", "mylist", "a", "b", "c");
        execute("HSET", "myhash", "f1", "v1", "f2", "v2");
        execute("SADD", "myset", "m1", "m2");

        // Switch to DB 1 and write
        execute("SELECT", "1");
        execute("SET", "db1_key", "db1_val");

        Path aofPath = config.getAofPath();
        assertTrue(Files.exists(aofPath));
        assertTrue(Files.size(aofPath) > 0, "AOF file must become non-empty on disk immediately after writes");

        aof.close();

        // Now simulate a clean server restart with brand new database manager
        DatabaseManager freshDbManager = new DatabaseManager(16);
        AofPersistenceManager freshAof = new AofPersistenceManager(config, freshDbManager, handler);

        int replayed = freshAof.recover();
        assertTrue(replayed >= 5, "Expected at least 5 recovered commands");

        // Verify DB 0 data restored
        assertEquals("value1", new String(freshDbManager.getDatabase(0).get("key1"), StandardCharsets.UTF_8));
        assertEquals(3, freshDbManager.getDatabase(0).llen("mylist"));
        assertEquals("v1", new String(freshDbManager.getDatabase(0).hget("myhash", redis.util.ByteArrayKey.of("f1")), StandardCharsets.UTF_8));
        assertEquals(2, freshDbManager.getDatabase(0).scard("myset"));

        // Verify DB 1 data restored
        assertEquals("db1_val", new String(freshDbManager.getDatabase(1).get("db1_key"), StandardCharsets.UTF_8));

        freshAof.close();
    }

    @Test
    @DisplayName("Regression: SET command flushes immediately without manual flush, survives restart")
    void testSetCommandFlushesImmediatelyAndRecovers() throws IOException {
        Path aofPath = config.getAofPath();

        // Execute SET student Sandeep through normal command execution path
        RespValue res = execute("SET", "student", "Sandeep");
        assertEquals("OK", res.asString());

        // The file MUST be non-empty immediately without calling flush() or close()
        assertTrue(Files.exists(aofPath), "AOF file must exist");
        long sizeAfterSet = Files.size(aofPath);
        assertTrue(sizeAfterSet > 0, "AOF file must not be 0 bytes after a successful SET command! Current size: " + sizeAfterSet);

        // Reconstruct a fresh server environment
        DatabaseManager freshDbManager = new DatabaseManager(16);
        AofPersistenceManager freshAof = new AofPersistenceManager(config, freshDbManager, handler);

        int recovered = freshAof.recover();
        assertTrue(recovered >= 1, "Must recover at least the SET command");
        byte[] val = freshDbManager.getDatabase(0).get("student");
        assertNotNull(val, "Key 'student' must be present in database after recovery");
        assertEquals("Sandeep", new String(val, StandardCharsets.UTF_8));

        freshAof.close();
    }

    @Test
    @DisplayName("Regression: Full TCP server SET persists to disk and recovers after server restart")
    void testServerTcpEndToEndPersistenceAndRestartRecovery() throws Exception {
        ServerConfig serverConfig = ServerConfig.createDefault();
        serverConfig.setHost("127.0.0.1");
        serverConfig.setPort(0);
        serverConfig.setDataDirectory(tempDir.resolve("tcp_srv_data"));
        serverConfig.setAofFilename("appendonly.aof");
        serverConfig.setPersistenceEnabled(true);
        serverConfig.setAofSyncAlways(false); // default async mode

        redis.server.RedisServer server1 = new redis.server.RedisServer(serverConfig);
        server1.start();
        int srvPort1 = server1.getPort();

        Path aofFile = serverConfig.getAofPath();

        // Connect client to server 1
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", srvPort1)) {
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            // Send SET student Sandeep
            out.write("*3\r\n$3\r\nSET\r\n$7\r\nstudent\r\n$7\r\nSandeep\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            redis.protocol.RespParser parser = new redis.protocol.RespParser(in);
            RespValue setResp = parser.parseNext();
            assertEquals("OK", setResp.asString());
        }

        // Verify while server is STILL RUNNING that the AOF file is non-empty!
        assertTrue(Files.exists(aofFile), "AOF file must exist on disk");
        long fileSize = Files.size(aofFile);
        assertTrue(fileSize > 0, "AOF file must not remain 0 bytes while server is running! Size was: " + fileSize);

        // Now stop server 1 cleanly
        server1.stop();

        // Start server 2 pointing to the same data directory
        redis.server.RedisServer server2 = new redis.server.RedisServer(serverConfig);
        server2.start();
        int srvPort2 = server2.getPort();

        // Connect client to server 2 and GET student
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", srvPort2)) {
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            out.write("*2\r\n$3\r\nGET\r\n$7\r\nstudent\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            redis.protocol.RespParser parser = new redis.protocol.RespParser(in);
            RespValue getResp = parser.parseNext();
            assertNotNull(getResp);
            assertEquals("Sandeep", getResp.asString(), "Recovered value must match what was saved");
        }

        server2.stop();
    }

    @Test
    @DisplayName("Safely handles corrupt or truncated trailing records without failing recovery")
    void testTruncatedTrailingRecordRecovery() throws IOException {
        execute("SET", "intactKey", "intactValue");
        aof.flush();
        aof.close();

        Path aofPath = config.getAofPath();
        // Append an incomplete / corrupted trailing command (e.g. power failure during write)
        byte[] corruptedBytes = "*3\r\n$3\r\nSET\r\n$4\r\npart".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(aofPath.toFile(), true)) {
            fos.write(corruptedBytes);
            fos.flush();
        }

        // Restart with fresh DB
        DatabaseManager freshDbManager = new DatabaseManager(16);
        AofPersistenceManager recoveryAof = new AofPersistenceManager(config, freshDbManager, handler);

        // Must not throw exception and must recover the intact record
        assertDoesNotThrow(() -> {
            int replayed = recoveryAof.recover();
            assertTrue(replayed >= 1, "Expected at least 1 recovered command");
        });

        assertEquals("intactValue", new String(freshDbManager.getDatabase(0).get("intactKey"), StandardCharsets.UTF_8));
        recoveryAof.close();
    }
}
