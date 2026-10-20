package redis.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.config.ServerConfig;
import redis.protocol.RespParser;
import redis.protocol.RespSerializer;
import redis.protocol.RespValue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class TcpServerIntegrationTest {

    @TempDir
    Path tempDir;

    private RedisServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        ServerConfig config = ServerConfig.createDefault();
        config.setHost("127.0.0.1");
        config.setPort(0); // Choose ephemeral port
        config.setDataDirectory(tempDir);
        config.setPersistenceEnabled(false); // fast in-memory for network tests
        config.setWorkerThreads(16);

        server = new RedisServer(config);
        server.start();
        port = server.getPort();
        assertTrue(port > 0, "Server should be bound to a valid port");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private RespValue sendCommand(Socket socket, String... args) throws IOException {
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

        List<RespValue> elements = Arrays.stream(args).map(RespValue::bulkString).toList();
        RespValue command = RespValue.array(elements);

        RespSerializer.write(command, out);
        out.flush();

        return new RespParser(in).parseNext();
    }

    @Test
    @DisplayName("End-to-End: Real TCP socket command exchange over RESP")
    void testBasicTcpCommunication() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            // PING
            RespValue pingResp = sendCommand(socket, "PING");
            assertEquals("PONG", pingResp.asString());

            // SET & GET
            RespValue setResp = sendCommand(socket, "SET", "greetings", "Hello from TCP!");
            assertEquals("OK", setResp.asString());

            RespValue getResp = sendCommand(socket, "GET", "greetings");
            assertEquals("Hello from TCP!", getResp.asString());

            // INCRBY
            RespValue incrResp = sendCommand(socket, "INCRBY", "counter", "100");
            assertEquals(100, incrResp.asLong());

            // DEL
            RespValue delResp = sendCommand(socket, "DEL", "greetings");
            assertEquals(1, delResp.asLong());
        }
    }

    @Test
    @DisplayName("End-to-End: Inline commands over raw TCP (telnet / netcat style)")
    void testInlineCommandsOverTcp() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

            // Send raw inline PING\r\n
            out.write("PING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            RespValue resp1 = new RespParser(in).parseNext();
            assertEquals("PONG", resp1.asString());

            // Send inline SET
            out.write("SET inlineKey \"custom message\"\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            RespValue resp2 = new RespParser(in).parseNext();
            assertEquals("OK", resp2.asString());

            // Send inline GET
            out.write("GET inlineKey\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            RespValue resp3 = new RespParser(in).parseNext();
            assertEquals("custom message", resp3.asString());
        }
    }

    @Test
    @DisplayName("End-to-End: Multiple concurrent clients performing operations")
    void testConcurrentClients() throws Exception {
        int clientCount = 10;
        int operationsPerClient = 50;
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int c = 0; c < clientCount; c++) {
            final int clientId = c;
            futures.add(executor.submit(() -> {
                startLatch.await();
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    for (int i = 0; i < operationsPerClient; i++) {
                        String key = "client:" + clientId + ":key:" + i;
                        String val = "val:" + i;
                        RespValue setRes = sendCommand(socket, "SET", key, val);
                        assertEquals("OK", setRes.asString());

                        RespValue getRes = sendCommand(socket, "GET", key);
                        assertEquals(val, getRes.asString());

                        // Atomic counter increment shared across clients
                        sendCommand(socket, "INCR", "global:counter");
                    }
                }
                return null;
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();

        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Check global counter value equals clientCount * operationsPerClient
        try (Socket socket = new Socket("127.0.0.1", port)) {
            RespValue finalCount = sendCommand(socket, "GET", "global:counter");
            assertEquals(Integer.toString(clientCount * operationsPerClient), finalCount.asString());
        }
    }

    @Test
    @DisplayName("End-to-End: QUIT command closes client connection cleanly")
    void testQuitCommand() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            RespValue quitResp = sendCommand(socket, "QUIT");
            assertEquals("OK", quitResp.asString());

            // Server closes socket after QUIT; reading should return EOF (-1)
            int nextByte = socket.getInputStream().read();
            assertEquals(-1, nextByte);
        }
    }
}
