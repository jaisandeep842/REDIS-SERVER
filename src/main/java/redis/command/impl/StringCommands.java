package redis.command.impl;

import redis.command.Command;
import redis.command.CommandContext;
import redis.protocol.RespValue;
import redis.storage.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StringCommands {

    private StringCommands() {}

    public static class PingCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty()) {
                return RespValue.PONG;
            } else if (args.size() == 1) {
                return RespValue.bulkString(args.get(0).asBytes());
            } else {
                return RespValue.error("ERR wrong number of arguments for 'ping' command");
            }
        }
    }

    public static class EchoCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'echo' command");
            }
            return RespValue.bulkString(args.get(0).asBytes());
        }
    }

    public static class SetCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 2) {
                return RespValue.error("ERR wrong number of arguments for 'set' command");
            }

            String key = args.get(0).asString();
            byte[] value = args.get(1).asBytes();
            long expireAtEpochMs = -1;

            for (int i = 2; i < args.size(); i++) {
                String opt = args.get(i).asString().toUpperCase();
                if ("EX".equals(opt)) {
                    if (i + 1 >= args.size()) {
                        return RespValue.error("ERR syntax error");
                    }
                    try {
                        long seconds = Long.parseLong(args.get(++i).asString());
                        if (seconds <= 0) {
                            return RespValue.error("ERR invalid expire time in 'set' command");
                        }
                        expireAtEpochMs = context.getClock().currentTimeMillis() + (seconds * 1000);
                    } catch (NumberFormatException e) {
                        return RespValue.error("ERR value is not an integer or out of range");
                    }
                } else if ("PX".equals(opt)) {
                    if (i + 1 >= args.size()) {
                        return RespValue.error("ERR syntax error");
                    }
                    try {
                        long millis = Long.parseLong(args.get(++i).asString());
                        if (millis <= 0) {
                            return RespValue.error("ERR invalid expire time in 'set' command");
                        }
                        expireAtEpochMs = context.getClock().currentTimeMillis() + millis;
                    } catch (NumberFormatException e) {
                        return RespValue.error("ERR value is not an integer or out of range");
                    }
                } else {
                    return RespValue.error("ERR syntax error");
                }
            }

            context.getStore().set(key, value, expireAtEpochMs);
            return RespValue.OK;
        }
    }

    public static class GetCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'get' command");
            }
            byte[] val = context.getStore().get(args.get(0).asString());
            return val == null ? RespValue.nullValue() : RespValue.bulkString(val);
        }
    }

    public static class MgetCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty()) {
                return RespValue.error("ERR wrong number of arguments for 'mget' command");
            }
            List<String> keys = new ArrayList<>(args.size());
            for (RespValue arg : args) {
                keys.add(arg.asString());
            }
            List<byte[]> results = context.getStore().mget(keys);
            List<RespValue> respList = new ArrayList<>(results.size());
            for (byte[] r : results) {
                respList.add(r == null ? RespValue.nullValue() : RespValue.bulkString(r));
            }
            return RespValue.array(respList);
        }
    }

    public static class MsetCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty() || args.size() % 2 != 0) {
                return RespValue.error("ERR wrong number of arguments for 'mset' command");
            }
            Map<String, byte[]> map = new LinkedHashMap<>();
            for (int i = 0; i < args.size(); i += 2) {
                map.put(args.get(i).asString(), args.get(i + 1).asBytes());
            }
            context.getStore().mset(map);
            return RespValue.OK;
        }
    }

    public static class IncrCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'incr' command");
            }
            long res = context.getStore().incrBy(args.get(0).asString(), 1L);
            return RespValue.integer(res);
        }
    }

    public static class DecrCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'decr' command");
            }
            long res = context.getStore().incrBy(args.get(0).asString(), -1L);
            return RespValue.integer(res);
        }
    }

    public static class IncrByCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'incrby' command");
            }
            long delta;
            try {
                delta = Long.parseLong(args.get(1).asString());
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            }
            long res = context.getStore().incrBy(args.get(0).asString(), delta);
            return RespValue.integer(res);
        }
    }

    public static class DecrByCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'decrby' command");
            }
            long delta;
            try {
                delta = Long.parseLong(args.get(1).asString());
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            }
            long res = context.getStore().incrBy(args.get(0).asString(), -delta);
            return RespValue.integer(res);
        }
    }

    public static class AppendCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'append' command");
            }
            int newLen = context.getStore().append(args.get(0).asString(), args.get(1).asBytes());
            return RespValue.integer(newLen);
        }
    }

    public static class StrlenCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'strlen' command");
            }
            int len = context.getStore().strlen(args.get(0).asString());
            return RespValue.integer(len);
        }
    }
}
