package redis.command;

import redis.config.ServerConfig;
import redis.persistence.PersistenceManager;
import redis.protocol.RespValue;
import redis.storage.DatabaseManager;
import redis.storage.Store;

import java.util.List;

public interface Command {
    RespValue execute(CommandContext context, List<RespValue> args);

    default boolean isWriteCommand() {
        return false;
    }
}
