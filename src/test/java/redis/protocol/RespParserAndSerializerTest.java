package redis.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RespParserAndSerializerTest {

    @Test
    @DisplayName("Serialize and parse Simple String")
    void testSimpleString() throws IOException {
        RespValue original = RespValue.simpleString("OK");
        byte[] serialized = RespSerializer.serialize(original);
        assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.UTF_8), serialized);

        RespValue parsed = RespParser.parse(serialized);
        assertEquals(RespType.SIMPLE_STRING, parsed.getType());
        assertEquals("OK", parsed.asString());
    }

    @Test
    @DisplayName("Serialize and parse Error")
    void testError() throws IOException {
        RespValue original = RespValue.error("ERR unknown command 'foobar'");
        byte[] serialized = RespSerializer.serialize(original);
        assertArrayEquals("-ERR unknown command 'foobar'\r\n".getBytes(StandardCharsets.UTF_8), serialized);

        RespValue parsed = RespParser.parse(serialized);
        assertEquals(RespType.ERROR, parsed.getType());
        assertEquals("ERR unknown command 'foobar'", parsed.asString());
    }

    @Test
    @DisplayName("Serialize and parse Integers (positive, negative, zero)")
    void testIntegers() throws IOException {
        long[] values = {0, 1, 42, -100, 1234567890123L, -9876543210L};
        for (long v : values) {
            RespValue orig = RespValue.integer(v);
            byte[] bytes = RespSerializer.serialize(orig);
            assertEquals(":" + v + "\r\n", new String(bytes, StandardCharsets.UTF_8));

            RespValue parsed = RespParser.parse(bytes);
            assertEquals(RespType.INTEGER, parsed.getType());
            assertEquals(v, parsed.asLong());
        }
    }

    @Test
    @DisplayName("Serialize and parse Bulk String with ASCII and Multibyte UTF-8")
    void testBulkStringUtf8() throws IOException {
        String unicodeStr = "Redis 🚀 世界! Hello 世界 ñ";
        byte[] rawBytes = unicodeStr.getBytes(StandardCharsets.UTF_8);

        RespValue orig = RespValue.bulkString(rawBytes);
        byte[] serialized = RespSerializer.serialize(orig);

        String header = "$" + rawBytes.length + "\r\n";
        assertTrue(new String(serialized, StandardCharsets.UTF_8).startsWith(header));

        RespValue parsed = RespParser.parse(serialized);
        assertEquals(RespType.BULK_STRING, parsed.getType());
        assertArrayEquals(rawBytes, parsed.asBytes());
        assertEquals(unicodeStr, parsed.asString());
    }

    @Test
    @DisplayName("Serialize and parse Empty Bulk String")
    void testEmptyBulkString() throws IOException {
        RespValue orig = RespValue.bulkString("");
        byte[] serialized = RespSerializer.serialize(orig);
        assertEquals("$0\r\n\r\n", new String(serialized, StandardCharsets.UTF_8));

        RespValue parsed = RespParser.parse(serialized);
        assertEquals(RespType.BULK_STRING, parsed.getType());
        assertEquals("", parsed.asString());
    }

    @Test
    @DisplayName("Serialize and parse Null Bulk String")
    void testNullBulkString() throws IOException {
        RespValue orig = RespValue.nullValue();
        byte[] serialized = RespSerializer.serialize(orig);
        assertEquals("$-1\r\n", new String(serialized, StandardCharsets.UTF_8));

        RespValue parsed = RespParser.parse(serialized);
        assertTrue(parsed.isNull());
        assertNull(parsed.asString());
    }

    @Test
    @DisplayName("Serialize and parse Array and Nested Array")
    void testArrays() throws IOException {
        RespValue item1 = RespValue.bulkString("ECHO");
        RespValue item2 = RespValue.bulkString("Hello World");
        RespValue array = RespValue.array(Arrays.asList(item1, item2));

        byte[] serialized = RespSerializer.serialize(array);
        RespValue parsed = RespParser.parse(serialized);

        assertEquals(RespType.ARRAY, parsed.getType());
        List<RespValue> elements = parsed.asArray();
        assertEquals(2, elements.size());
        assertEquals("ECHO", elements.get(0).asString());
        assertEquals("Hello World", elements.get(1).asString());

        // Nested Array: [1, ["foo", "bar"], 3]
        RespValue nested = RespValue.array(Arrays.asList(
                RespValue.integer(1),
                RespValue.array(Arrays.asList(RespValue.bulkString("foo"), RespValue.bulkString("bar"))),
                RespValue.integer(3)
        ));
        byte[] nestedSer = RespSerializer.serialize(nested);
        RespValue parsedNested = RespParser.parse(nestedSer);
        assertEquals(3, parsedNested.asArray().size());
        assertEquals(1, parsedNested.asArray().get(0).asLong());
        assertEquals(2, parsedNested.asArray().get(1).asArray().size());
        assertEquals("foo", parsedNested.asArray().get(1).asArray().get(0).asString());
        assertEquals("bar", parsedNested.asArray().get(1).asArray().get(1).asString());
        assertEquals(3, parsedNested.asArray().get(2).asLong());
    }

    @Test
    @DisplayName("Parse multiple commands sequentially from stream")
    void testSequentialCommandsInStream() throws IOException {
        String input = "*2\r\n$4\r\nECHO\r\n$2\r\nHI\r\n*1\r\n$4\r\nPING\r\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        RespParser parser = new RespParser(bais);

        RespValue cmd1 = parser.parseNext();
        assertNotNull(cmd1);
        assertEquals(2, cmd1.asArray().size());
        assertEquals("ECHO", cmd1.asArray().get(0).asString());
        assertEquals("HI", cmd1.asArray().get(1).asString());

        RespValue cmd2 = parser.parseNext();
        assertNotNull(cmd2);
        assertEquals(1, cmd2.asArray().size());
        assertEquals("PING", cmd2.asArray().get(0).asString());

        RespValue eof = parser.parseNext();
        assertNull(eof);
    }

    @Test
    @DisplayName("Parse inline commands (e.g. from telnet or simple TCP client)")
    void testInlineCommands() throws IOException {
        String input = "SET mykey \"hello world\"\r\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        RespParser parser = new RespParser(bais);

        RespValue cmd = parser.parseNext();
        assertNotNull(cmd);
        assertEquals(RespType.ARRAY, cmd.getType());
        assertEquals(3, cmd.asArray().size());
        assertEquals("SET", cmd.asArray().get(0).asString());
        assertEquals("mykey", cmd.asArray().get(1).asString());
        assertEquals("hello world", cmd.asArray().get(2).asString());
    }

    @Test
    @DisplayName("Protocol errors: invalid lengths and delimiters")
    void testProtocolErrors() {
        // Missing CRLF after bulk string
        byte[] malformedBulk = "$3\r\nabcX\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> RespParser.parse(malformedBulk));

        // Negative invalid length
        byte[] invalidLength = "$-5\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> RespParser.parse(invalidLength));

        // Excessively huge bulk string length claimed
        byte[] hugeBulk = "$999999999999\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> RespParser.parse(hugeBulk));
    }
}
