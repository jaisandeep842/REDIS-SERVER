package redis.command.impl;

import redis.command.Command;
import redis.command.CommandContext;
import redis.protocol.RespValue;
import redis.storage.RedisType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class KeyCommands {

    private KeyCommands() {}

    public static class DelCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty()) {
                return RespValue.error("ERR wrong number of arguments for 'del' command");
            }
            List<String> keys = new ArrayList<>(args.size());
            for (RespValue arg : args) {
                keys.add(arg.asString());
            }
            int deleted = context.getStore().del(keys);
            return RespValue.integer(deleted);
        }
    }

    public static class ExistsCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty()) {
                return RespValue.error("ERR wrong number of arguments for 'exists' command");
            }
            List<String> keys = new ArrayList<>(args.size());
            for (RespValue arg : args) {
                keys.add(arg.asString());
            }
            int count = context.getStore().exists(keys);
            return RespValue.integer(count);
        }
    }

    public static class TypeCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'type' command");
            }
            RedisType t = context.getStore().type(args.get(0).asString());
            return RespValue.simpleString(t.getName());
        }
    }

    public static class KeysCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'keys' command");
            }
            Set<String> matched = context.getStore().keys(args.get(0).asString());
            List<RespValue> elements = new ArrayList<>(matched.size());
            for (String key : matched) {
                elements.add(RespValue.bulkString(key));
            }
            return RespValue.array(elements);
        }
    }

    public static class DbsizeCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (!args.isEmpty()) {
                return RespValue.error("ERR wrong number of arguments for 'dbsize' command");
            }
            return RespValue.integer(context.getStore().dbsize());
        }
    }

    public static class FlushdbCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            context.getStore().flushDb();
            return RespValue.OK;
        }
    }

    public static class ExpireCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'expire' command");
            }
            try {
                long seconds = Long.parseLong(args.get(1).asString());
                long epochMs = context.getClock().currentTimeMillis() + (seconds * 1000);
                boolean set = context.getStore().expireAt(args.get(0).asString(), epochMs);
                return set ? RespValue.ONE : RespValue.ZERO;
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static class PexpireCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'pexpire' command");
            }
            try {
                long millis = Long.parseLong(args.get(1).asString());
                long epochMs = context.getClock().currentTimeMillis() + millis;
                boolean set = context.getStore().expireAt(args.get(0).asString(), epochMs);
                return set ? RespValue.ONE : RespValue.ZERO;
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static class TtlCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'ttl' command");
            }
            long ttl = context.getStore().ttl(args.get(0).asString());
            return RespValue.integer(ttl);
        }
    }

    public static class PttlCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'pttl' command");
            }
            long pttl = context.getStore().pttl(args.get(0).asString());
            return RespValue.integer(pttl);
        }
    }

    public static class PersistCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'persist' command");
            }
            boolean persisted = context.getStore().persist(args.get(0).asString());
            return persisted ? RespValue.ONE : RespValue.ZERO;
        }
    }
}
