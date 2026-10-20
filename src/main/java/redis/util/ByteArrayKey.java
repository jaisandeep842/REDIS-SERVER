package redis.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable wrapper around a byte[] that implements equals and hashCode
 * based on array content, allowing byte arrays to be used as map keys or set elements.
 */
public final class ByteArrayKey implements Comparable<ByteArrayKey> {

    private final byte[] data;
    private final int hash;

    public ByteArrayKey(byte[] data) {
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.hash = Arrays.hashCode(data);
    }

    public static ByteArrayKey of(String string) {
        return new ByteArrayKey(string.getBytes(StandardCharsets.UTF_8));
    }

    public static ByteArrayKey of(byte[] bytes) {
        return new ByteArrayKey(bytes);
    }

    public byte[] getData() {
        return data;
    }

    public String asUtf8String() {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ByteArrayKey that = (ByteArrayKey) o;
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(ByteArrayKey o) {
        return Arrays.compare(this.data, o.data);
    }

    @Override
    public String toString() {
        return asUtf8String();
    }
}
