package redis.persistence;

import redis.protocol.RespValue;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

public interface PersistenceManager extends Closeable {

    void open() throws IOException;

    void recordCommand(int dbIndex, List<RespValue> command);

    void flush() throws IOException;

    int recover() throws IOException;

    void close() throws IOException;

    boolean isEnabled();
}
