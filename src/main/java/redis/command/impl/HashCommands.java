package redis.command.impl;

import redis.command.Command;
import redis.command.CommandContext;
import redis.protocol.RespValue;
import redis.util.ByteArrayKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HashCommands {

    private HashCommands() {}

    public static class HsetCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
                return RespValue.error("ERR wrong number of arguments for 'hset' command");
            }
            String key = args.get(0).asString();
            Map<ByteArrayKey, byte[]> fieldValues = new LinkedHashMap<>();
            for (int i = 1; i < args.size(); i += 2) {
                fieldValues.put(ByteArrayKey.of(args.get(i).asBytes()), args.get(i + 1).asBytes());
            }
            int added = context.getStore().hset(key, fieldValues);
            return RespValue.integer(added);
        }
    }

    public static class HgetCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'hget' command");
            }
            String key = args.get(0).asString();
            ByteArrayKey field = ByteArrayKey.of(args.get(1).asBytes());
            byte[] val = context.getStore().hget(key, field);
            return val == null ? RespValue.nullValue() : RespValue.bulkString(val);
        }
    }

    public static class HdelCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 2) {
                return RespValue.error("ERR wrong number of arguments for 'hdel' command");
            }
            String key = args.get(0).asString();
            List<ByteArrayKey> fields = new ArrayList<>(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                fields.add(ByteArrayKey.of(args.get(i).asBytes()));
            }
            int removed = context.getStore().hdel(key, fields);
            return RespValue.integer(removed);
        }
    }

    public static class HexistsCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'hexists' command");
            }
            String key = args.get(0).asString();
            ByteArrayKey field = ByteArrayKey.of(args.get(1).asBytes());
            boolean exists = context.getStore().hexists(key, field);
            return exists ? RespValue.ONE : RespValue.ZERO;
        }
    }

    public static class HgetallCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'hgetall' command");
            }
            Map<ByteArrayKey, byte[]> all = context.getStore().hgetall(args.get(0).asString());
            List<RespValue> elements = new ArrayList<>(all.size() * 2);
            for (Map.Entry<ByteArrayKey, byte[]> e : all.entrySet()) {
                elements.add(RespValue.bulkString(e.getKey().getData()));
                elements.add(RespValue.bulkString(e.getValue()));
            }
            return RespValue.array(elements);
        }
    }

    public static class HlenCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'hlen' command");
            }
            int len = context.getStore().hlen(args.get(0).asString());
            return RespValue.integer(len);
        }
    }
}
