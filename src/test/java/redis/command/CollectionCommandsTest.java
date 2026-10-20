package redis.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.config.ServerConfig;
import redis.protocol.RespType;
import redis.protocol.RespValue;
import redis.storage.DatabaseManager;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CollectionCommandsTest {

    private DatabaseManager dbManager;
    private CommandHandler handler;
    private CommandContext context;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(16);
        CommandRegistry registry = CommandRegistry.createDefaultRegistry();
        handler = new CommandHandler(registry);
        context = new CommandContext(dbManager, ServerConfig.createDefault(), null);
    }

    private RespValue execute(String... args) {
        List<RespValue> list = Arrays.stream(args).map(RespValue::bulkString).toList();
        return handler.handle(context, RespValue.array(list));
    }

    @Test
    @DisplayName("Lists: LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE")
    void testLists() {
        // RPUSH numbers 1 2 3
        assertEquals(3, execute("RPUSH", "mylist", "1", "2", "3").asLong());

        // LPUSH numbers 0
        assertEquals(4, execute("LPUSH", "mylist", "0").asLong());

        // LLEN
        assertEquals(4, execute("LLEN", "mylist").asLong());

        // LRANGE 0 -1 -> ["0", "1", "2", "3"]
        RespValue fullRange = execute("LRANGE", "mylist", "0", "-1");
        assertEquals(RespType.ARRAY, fullRange.getType());
        List<RespValue> listItems = fullRange.asArray();
        assertEquals(4, listItems.size());
        assertEquals("0", listItems.get(0).asString());
        assertEquals("1", listItems.get(1).asString());
        assertEquals("2", listItems.get(2).asString());
        assertEquals("3", listItems.get(3).asString());

        // LRANGE 1 2 -> ["1", "2"]
        RespValue subRange = execute("LRANGE", "mylist", "1", "2");
        assertEquals(2, subRange.asArray().size());
        assertEquals("1", subRange.asArray().get(0).asString());
        assertEquals("2", subRange.asArray().get(1).asString());

        // LPOP
        assertEquals("0", execute("LPOP", "mylist").asString());

        // RPOP
        assertEquals("3", execute("RPOP", "mylist").asString());

        // LLEN should now be 2
        assertEquals(2, execute("LLEN", "mylist").asLong());

        // Pop remaining
        assertEquals("1", execute("LPOP", "mylist").asString());
        assertEquals("2", execute("LPOP", "mylist").asString());

        // Empty list -> key deleted
        assertEquals(0, execute("EXISTS", "mylist").asLong());
        assertTrue(execute("LPOP", "mylist").isNull());
    }

    @Test
    @DisplayName("Sets: SADD, SREM, SISMEMBER, SMEMBERS, SCARD")
    void testSets() {
        // SADD
        assertEquals(3, execute("SADD", "myset", "apple", "banana", "cherry").asLong());
        // Duplicate adds 0
        assertEquals(0, execute("SADD", "myset", "apple").asLong());

        // SCARD
        assertEquals(3, execute("SCARD", "myset").asLong());

        // SISMEMBER
        assertEquals(1, execute("SISMEMBER", "myset", "banana").asLong());
        assertEquals(0, execute("SISMEMBER", "myset", "orange").asLong());

        // SMEMBERS
        RespValue members = execute("SMEMBERS", "myset");
        assertEquals(RespType.ARRAY, members.getType());
        assertEquals(3, members.asArray().size());

        // SREM
        assertEquals(1, execute("SREM", "myset", "banana").asLong());
        assertEquals(0, execute("SREM", "myset", "nonexistent").asLong());
        assertEquals(2, execute("SCARD", "myset").asLong());

        // Remove rest
        assertEquals(2, execute("SREM", "myset", "apple", "cherry").asLong());
        assertEquals(0, execute("EXISTS", "myset").asLong());
    }

    @Test
    @DisplayName("Hashes: HSET, HGET, HDEL, HEXISTS, HGETALL, HLEN")
    void testHashes() {
        // HSET user:1 name John age 30 email john@example.com
        RespValue hset = execute("HSET", "user:1", "name", "John", "age", "30", "email", "john@example.com");
        assertEquals(3, hset.asLong());

        // HLEN
        assertEquals(3, execute("HLEN", "user:1").asLong());

        // HGET
        assertEquals("John", execute("HGET", "user:1", "name").asString());
        assertEquals("30", execute("HGET", "user:1", "age").asString());
        assertTrue(execute("HGET", "user:1", "missingField").isNull());

        // HEXISTS
        assertEquals(1, execute("HEXISTS", "user:1", "email").asLong());
        assertEquals(0, execute("HEXISTS", "user:1", "missingField").asLong());

        // HGETALL
        RespValue all = execute("HGETALL", "user:1");
        assertEquals(RespType.ARRAY, all.getType());
        assertEquals(6, all.asArray().size()); // 3 key-value pairs = 6 elements

        // HDEL
        assertEquals(1, execute("HDEL", "user:1", "age").asLong());
        assertEquals(2, execute("HLEN", "user:1").asLong());

        // HDEL remaining
        assertEquals(2, execute("HDEL", "user:1", "name", "email").asLong());
        assertEquals(0, execute("EXISTS", "user:1").asLong());
    }
}
