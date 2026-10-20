package redis.storage;

import redis.util.ByteArrayKey;
import redis.util.Clock;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Thread-safe, in-memory store supporting Redis data structures, expiration,
 * and atomic command execution.
 */
public class InMemoryStore implements Store {

    private final Clock clock;
    private final Map<String, ValueEntry> map = new ConcurrentHashMap<>();

    // Striped locks for fine-grained key locking
    private static final int STRIPE_COUNT = 256;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    public InMemoryStore() {
        this(Clock.SYSTEM);
    }

    public InMemoryStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        for (int i = 0; i < STRIPE_COUNT; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private int getStripe(String key) {
        int h = key.hashCode();
        return (h ^ (h >>> 16)) & (STRIPE_COUNT - 1);
    }

    private ReentrantLock getLock(String key) {
        return locks[getStripe(key)];
    }

    /**
     * Checks whether an entry has expired. If expired, removes it from the store.
     * Must be called while holding the key's lock.
     */
    private ValueEntry checkAndGet(String key) {
        ValueEntry entry = map.get(key);
        if (entry != null && entry.hasExpired(clock)) {
            map.remove(key);
            return null;
        }
        return entry;
    }

    @Override
    public boolean exists(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            return checkAndGet(key) != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int exists(List<String> keys) {
        int count = 0;
        for (String k : keys) {
            if (exists(k)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean del(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry != null) {
                map.remove(key);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int del(List<String> keys) {
        int count = 0;
        for (String k : keys) {
            if (del(k)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public RedisType type(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            return entry == null ? RedisType.NONE : entry.getType();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Set<String> keys(String globPattern) {
        Pattern pattern = globToRegex(globPattern);
        Set<String> result = new HashSet<>();
        long now = clock.currentTimeMillis();

        for (Map.Entry<String, ValueEntry> entry : map.entrySet()) {
            String k = entry.getKey();
            ValueEntry v = entry.getValue();
            if (v.hasExpired(clock)) {
                map.remove(k);
                continue;
            }
            if (pattern.matcher(k).matches()) {
                result.add(k);
            }
        }
        return result;
    }

    private Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> sb.append("\\").append(c);
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        sb.append("\\").append(glob.charAt(++i));
                    } else {
                        sb.append("\\\\");
                    }
                }
                default -> sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    @Override
    public int dbsize() {
        int count = 0;
        for (Map.Entry<String, ValueEntry> entry : map.entrySet()) {
            if (!entry.getValue().hasExpired(clock)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void flushDb() {
        for (ReentrantLock lock : locks) {
            lock.lock();
        }
        try {
            map.clear();
        } finally {
            for (ReentrantLock lock : locks) {
                lock.unlock();
            }
        }
    }

    @Override
    public boolean expireAt(String key, long epochMs) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return false;
            }
            if (epochMs <= clock.currentTimeMillis()) {
                map.remove(key);
            } else {
                entry.setExpireAtEpochMs(epochMs);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean persist(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null || entry.getExpireAtEpochMs() <= 0) {
                return false;
            }
            entry.setExpireAtEpochMs(-1);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long ttl(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return -2;
            }
            return entry.getTtlSeconds(clock);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pttl(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return -2;
            }
            return entry.getPttlMillis(clock);
        } finally {
            lock.unlock();
        }
    }

    // --- STRING OPERATIONS ---

    @Override
    public byte[] get(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return null;
            }
            if (entry.getType() != RedisType.STRING) {
                throw new WrongTypeException();
            }
            byte[] bytes = (byte[]) entry.getData();
            return Arrays.copyOf(bytes, bytes.length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void set(String key, byte[] value, long expireAtEpochMs) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            byte[] copy = Arrays.copyOf(value, value.length);
            map.put(key, new ValueEntry(RedisType.STRING, copy, expireAtEpochMs));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<byte[]> mget(List<String> keys) {
        List<byte[]> results = new ArrayList<>(keys.size());
        for (String k : keys) {
            ReentrantLock lock = getLock(k);
            lock.lock();
            try {
                ValueEntry entry = checkAndGet(k);
                if (entry == null || entry.getType() != RedisType.STRING) {
                    results.add(null);
                } else {
                    byte[] bytes = (byte[]) entry.getData();
                    results.add(Arrays.copyOf(bytes, bytes.length));
                }
            } finally {
                lock.unlock();
            }
        }
        return results;
    }

    @Override
    public void mset(Map<String, byte[]> keyValues) {
        // Sort keys to prevent deadlock when acquiring multiple locks
        List<String> sortedKeys = new ArrayList<>(keyValues.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys) {
            set(key, keyValues.get(key), -1);
        }
    }

    @Override
    public long incrBy(String key, long delta) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            long currentVal = 0;
            long expireAt = -1;
            if (entry != null) {
                if (entry.getType() != RedisType.STRING) {
                    throw new WrongTypeException();
                }
                expireAt = entry.getExpireAtEpochMs();
                byte[] raw = (byte[]) entry.getData();
                String str = new String(raw, StandardCharsets.UTF_8);
                try {
                    currentVal = Long.parseLong(str.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("ERR value is not an integer or out of range");
                }
            }

            long newVal = Math.addExact(currentVal, delta);
            byte[] newBytes = Long.toString(newVal).getBytes(StandardCharsets.UTF_8);
            map.put(key, new ValueEntry(RedisType.STRING, newBytes, expireAt));
            return newVal;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ERR increment or decrement would overflow");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int append(String key, byte[] value) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                set(key, value, -1);
                return value.length;
            }
            if (entry.getType() != RedisType.STRING) {
                throw new WrongTypeException();
            }
            byte[] existing = (byte[]) entry.getData();
            byte[] combined = new byte[existing.length + value.length];
            System.arraycopy(existing, 0, combined, 0, existing.length);
            System.arraycopy(value, 0, combined, existing.length, value.length);

            map.put(key, new ValueEntry(RedisType.STRING, combined, entry.getExpireAtEpochMs()));
            return combined.length;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int strlen(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return 0;
            }
            if (entry.getType() != RedisType.STRING) {
                throw new WrongTypeException();
            }
            return ((byte[]) entry.getData()).length;
        } finally {
            lock.unlock();
        }
    }

    // --- LIST OPERATIONS ---

    @SuppressWarnings("unchecked")
    @Override
    public int lpush(String key, List<byte[]> values) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            Deque<byte[]> list;
            long expireAt = -1;
            if (entry == null) {
                list = new LinkedList<>();
                map.put(key, new ValueEntry(RedisType.LIST, list, -1));
            } else {
                if (entry.getType() != RedisType.LIST) {
                    throw new WrongTypeException();
                }
                list = (Deque<byte[]>) entry.getData();
                expireAt = entry.getExpireAtEpochMs();
            }

            for (byte[] val : values) {
                list.addFirst(Arrays.copyOf(val, val.length));
            }
            return list.size();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int rpush(String key, List<byte[]> values) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            Deque<byte[]> list;
            if (entry == null) {
                list = new LinkedList<>();
                map.put(key, new ValueEntry(RedisType.LIST, list, -1));
            } else {
                if (entry.getType() != RedisType.LIST) {
                    throw new WrongTypeException();
                }
                list = (Deque<byte[]>) entry.getData();
            }

            for (byte[] val : values) {
                list.addLast(Arrays.copyOf(val, val.length));
            }
            return list.size();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<byte[]> lpop(String key, int count) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return Collections.emptyList();
            }
            if (entry.getType() != RedisType.LIST) {
                throw new WrongTypeException();
            }
            Deque<byte[]> list = (Deque<byte[]>) entry.getData();
            List<byte[]> popped = new ArrayList<>();
            for (int i = 0; i < count && !list.isEmpty(); i++) {
                popped.add(list.removeFirst());
            }
            if (list.isEmpty()) {
                map.remove(key);
            }
            return popped;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<byte[]> rpop(String key, int count) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return Collections.emptyList();
            }
            if (entry.getType() != RedisType.LIST) {
                throw new WrongTypeException();
            }
            Deque<byte[]> list = (Deque<byte[]>) entry.getData();
            List<byte[]> popped = new ArrayList<>();
            for (int i = 0; i < count && !list.isEmpty(); i++) {
                popped.add(list.removeLast());
            }
            if (list.isEmpty()) {
                map.remove(key);
            }
            return popped;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int llen(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return 0;
            }
            if (entry.getType() != RedisType.LIST) {
                throw new WrongTypeException();
            }
            return ((Deque<byte[]>) entry.getData()).size();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<byte[]> lrange(String key, int start, int stop) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return Collections.emptyList();
            }
            if (entry.getType() != RedisType.LIST) {
                throw new WrongTypeException();
            }
            Deque<byte[]> list = (Deque<byte[]>) entry.getData();
            int size = list.size();
            if (size == 0) {
                return Collections.emptyList();
            }

            int s = start < 0 ? Math.max(0, size + start) : start;
            int e = stop < 0 ? Math.max(0, size + stop) : Math.min(stop, size - 1);

            if (s > e || s >= size) {
                return Collections.emptyList();
            }

            List<byte[]> result = new ArrayList<>(e - s + 1);
            int idx = 0;
            for (byte[] item : list) {
                if (idx >= s && idx <= e) {
                    result.add(Arrays.copyOf(item, item.length));
                }
                idx++;
                if (idx > e) break;
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    // --- SET OPERATIONS ---

    @SuppressWarnings("unchecked")
    @Override
    public int sadd(String key, List<ByteArrayKey> members) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            Set<ByteArrayKey> set;
            if (entry == null) {
                set = new LinkedHashSet<>();
                map.put(key, new ValueEntry(RedisType.SET, set, -1));
            } else {
                if (entry.getType() != RedisType.SET) {
                    throw new WrongTypeException();
                }
                set = (Set<ByteArrayKey>) entry.getData();
            }

            int added = 0;
            for (ByteArrayKey member : members) {
                if (set.add(member)) {
                    added++;
                }
            }
            return added;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int srem(String key, List<ByteArrayKey> members) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return 0;
            }
            if (entry.getType() != RedisType.SET) {
                throw new WrongTypeException();
            }
            Set<ByteArrayKey> set = (Set<ByteArrayKey>) entry.getData();
            int removed = 0;
            for (ByteArrayKey member : members) {
                if (set.remove(member)) {
                    removed++;
                }
            }
            if (set.isEmpty()) {
                map.remove(key);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean sismember(String key, ByteArrayKey member) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return false;
            }
            if (entry.getType() != RedisType.SET) {
                throw new WrongTypeException();
            }
            return ((Set<ByteArrayKey>) entry.getData()).contains(member);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<ByteArrayKey> smembers(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return Collections.emptySet();
            }
            if (entry.getType() != RedisType.SET) {
                throw new WrongTypeException();
            }
            return new LinkedHashSet<>((Set<ByteArrayKey>) entry.getData());
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int scard(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return 0;
            }
            if (entry.getType() != RedisType.SET) {
                throw new WrongTypeException();
            }
            return ((Set<ByteArrayKey>) entry.getData()).size();
        } finally {
            lock.unlock();
        }
    }

    // --- HASH OPERATIONS ---

    @SuppressWarnings("unchecked")
    @Override
    public int hset(String key, Map<ByteArrayKey, byte[]> fieldValues) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            Map<ByteArrayKey, byte[]> hash;
            if (entry == null) {
                hash = new LinkedHashMap<>();
                map.put(key, new ValueEntry(RedisType.HASH, hash, -1));
            } else {
                if (entry.getType() != RedisType.HASH) {
                    throw new WrongTypeException();
                }
                hash = (Map<ByteArrayKey, byte[]>) entry.getData();
            }

            int addedCount = 0;
            for (Map.Entry<ByteArrayKey, byte[]> e : fieldValues.entrySet()) {
                byte[] valCopy = Arrays.copyOf(e.getValue(), e.getValue().length);
                if (hash.put(e.getKey(), valCopy) == null) {
                    addedCount++;
                }
            }
            return addedCount;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public byte[] hget(String key, ByteArrayKey field) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return null;
            }
            if (entry.getType() != RedisType.HASH) {
                throw new WrongTypeException();
            }
            Map<ByteArrayKey, byte[]> hash = (Map<ByteArrayKey, byte[]>) entry.getData();
            byte[] val = hash.get(field);
            return val == null ? null : Arrays.copyOf(val, val.length);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int hdel(String key, List<ByteArrayKey> fields) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return 0;
            }
            if (entry.getType() != RedisType.HASH) {
                throw new WrongTypeException();
            }
            Map<ByteArrayKey, byte[]> hash = (Map<ByteArrayKey, byte[]>) entry.getData();
            int removed = 0;
            for (ByteArrayKey field : fields) {
                if (hash.remove(field) != null) {
                    removed++;
                }
            }
            if (hash.isEmpty()) {
                map.remove(key);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean hexists(String key, ByteArrayKey field) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return false;
            }
            if (entry.getType() != RedisType.HASH) {
                throw new WrongTypeException();
            }
            Map<ByteArrayKey, byte[]> hash = (Map<ByteArrayKey, byte[]>) entry.getData();
            return hash.containsKey(field);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<ByteArrayKey, byte[]> hgetall(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return Collections.emptyMap();
            }
            if (entry.getType() != RedisType.HASH) {
                throw new WrongTypeException();
            }
            Map<ByteArrayKey, byte[]> original = (Map<ByteArrayKey, byte[]>) entry.getData();
            Map<ByteArrayKey, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<ByteArrayKey, byte[]> e : original.entrySet()) {
                copy.put(e.getKey(), Arrays.copyOf(e.getValue(), e.getValue().length));
            }
            return copy;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int hlen(String key) {
        ReentrantLock lock = getLock(key);
        lock.lock();
        try {
            ValueEntry entry = checkAndGet(key);
            if (entry == null) {
                return 0;
            }
            if (entry.getType() != RedisType.HASH) {
                throw new WrongTypeException();
            }
            return ((Map<ByteArrayKey, byte[]>) entry.getData()).size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int activeExpireCycle(int sampleLimit) {
        int expiredCount = 0;
        int checked = 0;
        for (Map.Entry<String, ValueEntry> entry : map.entrySet()) {
            if (checked++ >= sampleLimit) {
                break;
            }
            String key = entry.getKey();
            ReentrantLock lock = getLock(key);
            if (lock.tryLock()) {
                try {
                    ValueEntry ve = map.get(key);
                    if (ve != null && ve.hasExpired(clock)) {
                        map.remove(key);
                        expiredCount++;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
        return expiredCount;
    }
}
