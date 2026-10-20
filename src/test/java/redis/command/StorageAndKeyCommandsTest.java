package redis.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.config.ServerConfig;
import redis.protocol.RespType;
import redis.protocol.RespValue;
import redis.storage.DatabaseManager;
import redis.storage.RedisType;
import redis.util.Clock;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class StorageAndKeyCommandsTest {

    private DatabaseManager dbManager;
    private CommandHandler handler;
    private CommandContext context;
    private AtomicLong testTime;

    @BeforeEach
    void setUp() {
        testTime = new AtomicLong(1_000_000_000L);
        Clock testClock = testTime::get;
        dbManager = new DatabaseManager(16, testClock);
        CommandRegistry registry = CommandRegistry.createDefaultRegistry();
        handler = new CommandHandler(registry);
        context = new CommandContext(dbManager, ServerConfig.createDefault(), null);
    }

    private RespValue execute(String... args) {
        List<RespValue> list = Arrays.stream(args).map(RespValue::bulkString).toList();
        return handler.handle(context, RespValue.array(list));
    }

    @Test
    @DisplayName("PING and ECHO commands")
    void testPingAndEcho() {
        RespValue pingDefault = execute("PING");
        assertEquals("PONG", pingDefault.asString());

        RespValue pingCustom = execute("PING", "Hello Redis");
        assertEquals("Hello Redis", pingCustom.asString());

        RespValue echo = execute("ECHO", "Testing 123");
        assertEquals("Testing 123", echo.asString());
    }

    @Test
    @DisplayName("SET, GET, DEL, EXISTS")
    void testBasicStrings() {
        // Missing key returns null
        RespValue get1 = execute("GET", "key1");
        assertTrue(get1.isNull());

        // SET key1 val1
        RespValue set1 = execute("SET", "key1", "val1");
        assertEquals("OK", set1.asString());

        // GET key1
        RespValue get2 = execute("GET", "key1");
        assertEquals("val1", get2.asString());

        // EXISTS key1 key2
        RespValue exists1 = execute("EXISTS", "key1", "key2");
        assertEquals(1, exists1.asLong());

        // DEL key1
        RespValue del1 = execute("DEL", "key1");
        assertEquals(1, del1.asLong());

        // GET after DEL
        assertTrue(execute("GET", "key1").isNull());
    }

    @Test
    @DisplayName("MSET and MGET")
    void testMsetAndMget() {
        RespValue mset = execute("MSET", "k1", "v1", "k2", "v2", "k3", "v3");
        assertEquals("OK", mset.asString());

        RespValue mget = execute("MGET", "k1", "missing", "k3");
        assertEquals(RespType.ARRAY, mget.getType());
        List<RespValue> elements = mget.asArray();
        assertEquals(3, elements.size());
        assertEquals("v1", elements.get(0).asString());
        assertTrue(elements.get(1).isNull());
        assertEquals("v3", elements.get(2).asString());
    }

    @Test
    @DisplayName("INCR, DECR, INCRBY, DECRBY integer math and errors")
    void testIntegerMath() {
        assertEquals(1, execute("INCR", "counter").asLong());
        assertEquals(2, execute("INCR", "counter").asLong());
        assertEquals(1, execute("DECR", "counter").asLong());
        assertEquals(11, execute("INCRBY", "counter", "10").asLong());
        assertEquals(6, execute("DECRBY", "counter", "5").asLong());

        // String that is not a number
        execute("SET", "strkey", "notanumber");
        RespValue err = execute("INCR", "strkey");
        assertEquals(RespType.ERROR, err.getType());
        assertTrue(err.asString().contains("ERR value is not an integer"));
    }

    @Test
    @DisplayName("APPEND and STRLEN")
    void testAppendAndStrlen() {
        assertEquals(5, execute("APPEND", "msg", "Hello").asLong());
        assertEquals(11, execute("APPEND", "msg", " World").asLong());
        assertEquals("Hello World", execute("GET", "msg").asString());
        assertEquals(11, execute("STRLEN", "msg").asLong());
        assertEquals(0, execute("STRLEN", "missing").asLong());
    }

    @Test
    @DisplayName("KEYS pattern matching and DBSIZE and FLUSHDB")
    void testKeysAndDbsize() {
        execute("SET", "user:1", "alice");
        execute("SET", "user:2", "bob");
        execute("SET", "order:1", "apple");

        assertEquals(3, execute("DBSIZE").asLong());

        RespValue keys = execute("KEYS", "user:*");
        assertEquals(RespType.ARRAY, keys.getType());
        assertEquals(2, keys.asArray().size());

        RespValue allKeys = execute("KEYS", "*");
        assertEquals(3, allKeys.asArray().size());

        execute("FLUSHDB");
        assertEquals(0, execute("DBSIZE").asLong());
    }

    @Test
    @DisplayName("Deterministic Expiration: EXPIRE, PEXPIRE, TTL, PTTL, PERSIST, SET EX")
    void testDeterministicExpiration() {
        execute("SET", "expKey", "someValue");

        // TTL when no expiry
        assertEquals(-1, execute("TTL", "expKey").asLong());
        assertEquals(-1, execute("PTTL", "expKey").asLong());

        // EXPIRE 10 seconds
        assertEquals(1, execute("EXPIRE", "expKey", "10").asLong());
        assertEquals(10, execute("TTL", "expKey").asLong());
        assertEquals(10000, execute("PTTL", "expKey").asLong());

        // Advance time by 4 seconds (4000ms)
        testTime.addAndGet(4000);
        assertEquals(6, execute("TTL", "expKey").asLong());

        // Advance time by another 7 seconds (total 11s, expired)
        testTime.addAndGet(7000);
        assertEquals(-2, execute("TTL", "expKey").asLong());
        assertTrue(execute("GET", "expKey").isNull());

        // SET with EX option
        execute("SET", "temp", "val", "EX", "5");
        assertEquals(5, execute("TTL", "temp").asLong());

        // PERSIST removes TTL
        assertEquals(1, execute("PERSIST", "temp").asLong());
        assertEquals(-1, execute("TTL", "temp").asLong());
        testTime.addAndGet(10000);
        // Still exists because persisted
        assertEquals("val", execute("GET", "temp").asString());
    }

    @Test
    @DisplayName("TYPE command returns correct Redis types")
    void testTypeCommand() {
        execute("SET", "s", "str");
        execute("LPUSH", "l", "item");
        execute("SADD", "st", "mem");
        execute("HSET", "h", "f", "v");

        assertEquals("string", execute("TYPE", "s").asString());
        assertEquals("list", execute("TYPE", "l").asString());
        assertEquals("set", execute("TYPE", "st").asString());
        assertEquals("hash", execute("TYPE", "h").asString());
        assertEquals("none", execute("TYPE", "missing").asString());
    }

    @Test
    @DisplayName("WrongType errors when applying incompatible command")
    void testWrongTypeErrors() {
        execute("SET", "sKey", "hello");

        // Attempting list, set, or hash commands on string key
        RespValue err1 = execute("LPUSH", "sKey", "item");
        assertEquals(RespType.ERROR, err1.getType());
        assertTrue(err1.asString().startsWith("WRONGTYPE"));

        RespValue err2 = execute("SADD", "sKey", "member");
        assertEquals(RespType.ERROR, err2.getType());
        assertTrue(err2.asString().startsWith("WRONGTYPE"));

        RespValue err3 = execute("HGET", "sKey", "field");
        assertEquals(RespType.ERROR, err3.getType());
        assertTrue(err3.asString().startsWith("WRONGTYPE"));
    }
}
