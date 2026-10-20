package redis.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a RESP (REdis Serialization Protocol) value.
 * Supports Simple String, Error, Integer, Bulk String, Array, and Null.
 */
public final class RespValue {

    private final RespType type;
    private final Object value;

    // Common cached constants
    public static final RespValue OK = new RespValue(RespType.SIMPLE_STRING, "OK");
    public static final RespValue PONG = new RespValue(RespType.SIMPLE_STRING, "PONG");
    public static final RespValue NULL = new RespValue(RespType.NULL, null);
    public static final RespValue EMPTY_ARRAY = new RespValue(RespType.ARRAY, Collections.emptyList());
    public static final RespValue ZERO = new RespValue(RespType.INTEGER, 0L);
    public static final RespValue ONE = new RespValue(RespType.INTEGER, 1L);

    private RespValue(RespType type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static RespValue simpleString(String message) {
        return new RespValue(RespType.SIMPLE_STRING, Objects.requireNonNull(message, "message cannot be null"));
    }

    public static RespValue error(String message) {
        return new RespValue(RespType.ERROR, Objects.requireNonNull(message, "message cannot be null"));
    }

    public static RespValue integer(long val) {
        return new RespValue(RespType.INTEGER, val);
    }

    public static RespValue bulkString(byte[] bytes) {
        if (bytes == null) {
            return NULL;
        }
        return new RespValue(RespType.BULK_STRING, Arrays.copyOf(bytes, bytes.length));
    }

    public static RespValue bulkString(String string) {
        if (string == null) {
            return NULL;
        }
        return bulkString(string.getBytes(StandardCharsets.UTF_8));
    }

    public static RespValue array(List<RespValue> elements) {
        if (elements == null) {
            return NULL;
        }
        return new RespValue(RespType.ARRAY, Collections.unmodifiableList(new ArrayList<>(elements)));
    }

    public static RespValue nullValue() {
        return NULL;
    }

    public RespType getType() {
        return type;
    }

    public boolean isNull() {
        return type == RespType.NULL;
    }

    public String asString() {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    public byte[] asBytes() {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return Arrays.copyOf(bytes, bytes.length);
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    public long asLong() {
        if (type == RespType.INTEGER && value instanceof Long l) {
            return l;
        }
        String s = asString();
        if (s == null) {
            throw new NumberFormatException("Null value cannot be parsed as long");
        }
        return Long.parseLong(s);
    }

    @SuppressWarnings("unchecked")
    public List<RespValue> asArray() {
        if (type != RespType.ARRAY || value == null) {
            return Collections.emptyList();
        }
        return (List<RespValue>) value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespValue respValue = (RespValue) o;
        if (type != respValue.type) return false;
        if (value instanceof byte[] b1 && respValue.value instanceof byte[] b2) {
            return Arrays.equals(b1, b2);
        }
        return Objects.equals(value, respValue.value);
    }

    @Override
    public int hashCode() {
        if (value instanceof byte[] bytes) {
            return 31 * type.hashCode() + Arrays.hashCode(bytes);
        }
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return switch (type) {
            case SIMPLE_STRING -> "+" + value;
            case ERROR -> "-" + value;
            case INTEGER -> ":" + value;
            case BULK_STRING -> "$" + (value instanceof byte[] b ? b.length + ":" + asString() : value);
            case ARRAY -> "*" + asArray();
            case NULL -> "$-1 (null)";
        };
    }
}
