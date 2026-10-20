package redis.storage;

import redis.util.ByteArrayKey;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface defining all database operations supported by a Redis database instance.
 */
public interface Store {

    // General Key Operations
    boolean exists(String key);
    int exists(List<String> keys);
    boolean del(String key);
    int del(List<String> keys);
    RedisType type(String key);
    Set<String> keys(String globPattern);
    int dbsize();
    void flushDb();

    // Expiration Operations
    boolean expireAt(String key, long epochMs);
    boolean persist(String key);
    long ttl(String key);
    long pttl(String key);

    // String Operations
    byte[] get(String key);
    void set(String key, byte[] value, long expireAtEpochMs);
    List<byte[]> mget(List<String> keys);
    void mset(Map<String, byte[]> keyValues);
    long incrBy(String key, long delta);
    int append(String key, byte[] value);
    int strlen(String key);

    // List Operations
    int lpush(String key, List<byte[]> values);
    int rpush(String key, List<byte[]> values);
    List<byte[]> lpop(String key, int count);
    List<byte[]> rpop(String key, int count);
    int llen(String key);
    List<byte[]> lrange(String key, int start, int stop);

    // Set Operations
    int sadd(String key, List<ByteArrayKey> members);
    int srem(String key, List<ByteArrayKey> members);
    boolean sismember(String key, ByteArrayKey member);
    Set<ByteArrayKey> smembers(String key);
    int scard(String key);

    // Hash Operations
    int hset(String key, Map<ByteArrayKey, byte[]> fieldValues);
    byte[] hget(String key, ByteArrayKey field);
    int hdel(String key, List<ByteArrayKey> fields);
    boolean hexists(String key, ByteArrayKey field);
    Map<ByteArrayKey, byte[]> hgetall(String key);
    int hlen(String key);

    // Housekeeping
    int activeExpireCycle(int sampleLimit);
}
