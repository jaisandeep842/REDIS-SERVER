package redis.command.impl;

import redis.command.Command;
import redis.command.CommandContext;
import redis.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;

public final class ListCommands {

    private ListCommands() {}

    public static class LpushCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 2) {
                return RespValue.error("ERR wrong number of arguments for 'lpush' command");
            }
            String key = args.get(0).asString();
            List<byte[]> values = new ArrayList<>(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                values.add(args.get(i).asBytes());
            }
            int len = context.getStore().lpush(key, values);
            return RespValue.integer(len);
        }
    }

    public static class RpushCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 2) {
                return RespValue.error("ERR wrong number of arguments for 'rpush' command");
            }
            String key = args.get(0).asString();
            List<byte[]> values = new ArrayList<>(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                values.add(args.get(i).asBytes());
            }
            int len = context.getStore().rpush(key, values);
            return RespValue.integer(len);
        }
    }

    public static class LpopCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty() || args.size() > 2) {
                return RespValue.error("ERR wrong number of arguments for 'lpop' command");
            }
            String key = args.get(0).asString();
            boolean hasCount = args.size() == 2;
            int count = 1;
            if (hasCount) {
                try {
                    count = Integer.parseInt(args.get(1).asString());
                    if (count < 0) {
                        return RespValue.error("ERR value is out of range, must be positive");
                    }
                } catch (NumberFormatException e) {
                    return RespValue.error("ERR value is not an integer or out of range");
                }
            }

            List<byte[]> popped = context.getStore().lpop(key, count);
            if (!hasCount) {
                return popped.isEmpty() ? RespValue.nullValue() : RespValue.bulkString(popped.get(0));
            } else {
                List<RespValue> respList = new ArrayList<>(popped.size());
                for (byte[] b : popped) {
                    respList.add(RespValue.bulkString(b));
                }
                return RespValue.array(respList);
            }
        }
    }

    public static class RpopCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.isEmpty() || args.size() > 2) {
                return RespValue.error("ERR wrong number of arguments for 'rpop' command");
            }
            String key = args.get(0).asString();
            boolean hasCount = args.size() == 2;
            int count = 1;
            if (hasCount) {
                try {
                    count = Integer.parseInt(args.get(1).asString());
                    if (count < 0) {
                        return RespValue.error("ERR value is out of range, must be positive");
                    }
                } catch (NumberFormatException e) {
                    return RespValue.error("ERR value is not an integer or out of range");
                }
            }

            List<byte[]> popped = context.getStore().rpop(key, count);
            if (!hasCount) {
                return popped.isEmpty() ? RespValue.nullValue() : RespValue.bulkString(popped.get(0));
            } else {
                List<RespValue> respList = new ArrayList<>(popped.size());
                for (byte[] b : popped) {
                    respList.add(RespValue.bulkString(b));
                }
                return RespValue.array(respList);
            }
        }
    }

    public static class LlenCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'llen' command");
            }
            int len = context.getStore().llen(args.get(0).asString());
            return RespValue.integer(len);
        }
    }

    public static class LrangeCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 3) {
                return RespValue.error("ERR wrong number of arguments for 'lrange' command");
            }
            int start;
            int stop;
            try {
                start = Integer.parseInt(args.get(1).asString());
                stop = Integer.parseInt(args.get(2).asString());
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            }

            List<byte[]> items = context.getStore().lrange(args.get(0).asString(), start, stop);
            List<RespValue> result = new ArrayList<>(items.size());
            for (byte[] item : items) {
                result.add(RespValue.bulkString(item));
            }
            return RespValue.array(result);
        }
    }
}
