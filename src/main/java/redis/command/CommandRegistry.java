package redis.command;

import redis.command.impl.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CommandRegistry {

    private final Map<String, Command> commands = new HashMap<>();

    public void register(String name, Command command) {
        commands.put(name.toUpperCase(Locale.ROOT), command);
    }

    public Command get(String name) {
        if (name == null) {
            return null;
        }
        return commands.get(name.toUpperCase(Locale.ROOT));
    }

    public boolean hasCommand(String name) {
        if (name == null) {
            return false;
        }
        return commands.containsKey(name.toUpperCase(Locale.ROOT));
    }

    public Map<String, Command> getAllCommands() {
        return Collections.unmodifiableMap(commands);
    }

    public static CommandRegistry createDefaultRegistry() {
        CommandRegistry registry = new CommandRegistry();

        // Basic / String Commands
        registry.register("PING", new StringCommands.PingCommand());
        registry.register("ECHO", new StringCommands.EchoCommand());
        registry.register("SET", new StringCommands.SetCommand());
        registry.register("GET", new StringCommands.GetCommand());
        registry.register("MGET", new StringCommands.MgetCommand());
        registry.register("MSET", new StringCommands.MsetCommand());
        registry.register("INCR", new StringCommands.IncrCommand());
        registry.register("DECR", new StringCommands.DecrCommand());
        registry.register("INCRBY", new StringCommands.IncrByCommand());
        registry.register("DECRBY", new StringCommands.DecrByCommand());
        registry.register("APPEND", new StringCommands.AppendCommand());
        registry.register("STRLEN", new StringCommands.StrlenCommand());

        // Key Commands
        registry.register("DEL", new KeyCommands.DelCommand());
        registry.register("EXISTS", new KeyCommands.ExistsCommand());
        registry.register("TYPE", new KeyCommands.TypeCommand());
        registry.register("KEYS", new KeyCommands.KeysCommand());
        registry.register("DBSIZE", new KeyCommands.DbsizeCommand());
        registry.register("FLUSHDB", new KeyCommands.FlushdbCommand());
        registry.register("EXPIRE", new KeyCommands.ExpireCommand());
        registry.register("PEXPIRE", new KeyCommands.PexpireCommand());
        registry.register("TTL", new KeyCommands.TtlCommand());
        registry.register("PTTL", new KeyCommands.PttlCommand());
        registry.register("PERSIST", new KeyCommands.PersistCommand());

        // List Commands
        registry.register("LPUSH", new ListCommands.LpushCommand());
        registry.register("RPUSH", new ListCommands.RpushCommand());
        registry.register("LPOP", new ListCommands.LpopCommand());
        registry.register("RPOP", new ListCommands.RpopCommand());
        registry.register("LLEN", new ListCommands.LlenCommand());
        registry.register("LRANGE", new ListCommands.LrangeCommand());

        // Set Commands
        registry.register("SADD", new SetCommands.SaddCommand());
        registry.register("SREM", new SetCommands.SremCommand());
        registry.register("SISMEMBER", new SetCommands.SismemberCommand());
        registry.register("SMEMBERS", new SetCommands.SmembersCommand());
        registry.register("SCARD", new SetCommands.ScardCommand());

        // Hash Commands
        registry.register("HSET", new HashCommands.HsetCommand());
        registry.register("HGET", new HashCommands.HgetCommand());
        registry.register("HDEL", new HashCommands.HdelCommand());
        registry.register("HEXISTS", new HashCommands.HexistsCommand());
        registry.register("HGETALL", new HashCommands.HgetallCommand());
        registry.register("HLEN", new HashCommands.HlenCommand());

        // Server Commands
        registry.register("QUIT", new ServerCommands.QuitCommand());
        registry.register("SELECT", new ServerCommands.SelectCommand());
        registry.register("COMMAND", new ServerCommands.CommandCommand());
        registry.register("CLIENT", new ServerCommands.ClientCommand());
        registry.register("INFO", new ServerCommands.InfoCommand());

        return registry;
    }
}
