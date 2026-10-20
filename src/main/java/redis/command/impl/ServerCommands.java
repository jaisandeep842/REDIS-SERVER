package redis.command.impl;

import redis.command.Command;
import redis.command.CommandContext;
import redis.protocol.RespValue;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

public final class ServerCommands {

    private ServerCommands() {}

    public static class QuitCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            context.requestClose();
            return RespValue.OK;
        }
    }

    public static class SelectCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'select' command");
            }
            try {
                int dbIndex = Integer.parseInt(args.get(0).asString());
                context.setCurrentDbIndex(dbIndex);
                return RespValue.OK;
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            } catch (IllegalArgumentException e) {
                return RespValue.error(e.getMessage());
            }
        }
    }

    public static class CommandCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            // Redis clients (like redis-cli or Lettuce/Jedis) send COMMAND or COMMAND DOCS on connect.
            // Returning an empty array is standard for custom/minimal implementations.
            return RespValue.EMPTY_ARRAY;
        }
    }

    public static class ClientCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty()) {
                return RespValue.error("ERR wrong number of arguments for 'client' command");
            }
            String sub = args.get(0).asString().toUpperCase();
            if ("SETNAME".equals(sub)) {
                if (args.size() != 2) {
                    return RespValue.error("ERR syntax error");
                }
                context.setClientName(args.get(1).asString());
                return RespValue.OK;
            } else if ("GETNAME".equals(sub)) {
                String name = context.getClientName();
                return name.isEmpty() ? RespValue.nullValue() : RespValue.bulkString(name);
            } else {
                return RespValue.error("ERR unknown subcommand or wrong number of arguments for 'CLIENT " + sub + "'");
            }
        }
    }

    public static class InfoCommand implements Command {
        private static final long START_TIME_MS = System.currentTimeMillis();

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            long uptimeSeconds = (System.currentTimeMillis() - START_TIME_MS) / 1000;
            long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            StringBuilder sb = new StringBuilder();
            sb.append("# Server\r\n");
            sb.append("redis_version:7.0.0-custom-java\r\n");
            sb.append("redis_mode:standalone\r\n");
            sb.append("os:").append(System.getProperty("os.name")).append("\r\n");
            sb.append("arch_bits:64\r\n");
            sb.append("process_id:").append(ProcessHandle.current().pid()).append("\r\n");
            sb.append("uptime_in_seconds:").append(uptimeSeconds).append("\r\n");
            sb.append("\r\n# Clients\r\n");
            sb.append("connected_clients:1\r\n");
            sb.append("\r\n# Memory\r\n");
            sb.append("used_memory:").append(usedMemory).append("\r\n");
            sb.append("used_memory_human:").append(usedMemory / (1024 * 1024)).append("M\r\n");
            sb.append("\r\n# Persistence\r\n");
            sb.append("aof_enabled:").append(context.getConfig().isPersistenceEnabled() ? "1" : "0").append("\r\n");
            sb.append("\r\n# Keyspace\r\n");
            int totalKeys = context.getStore().dbsize();
            sb.append("db").append(context.getCurrentDbIndex()).append(":keys=").append(totalKeys).append(",expires=0,avg_ttl=0\r\n");

            return RespValue.bulkString(sb.toString());
        }
    }
}
