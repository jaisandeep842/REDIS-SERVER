package redis.command.impl;

import redis.command.Command;
import redis.command.CommandContext;
import redis.protocol.RespValue;
import redis.util.ByteArrayKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class SetCommands {

    private SetCommands() {}

    public static class SaddCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 2) {
                return RespValue.error("ERR wrong number of arguments for 'sadd' command");
            }
            String key = args.get(0).asString();
            List<ByteArrayKey> members = new ArrayList<>(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                members.add(ByteArrayKey.of(args.get(i).asBytes()));
            }
            int added = context.getStore().sadd(key, members);
            return RespValue.integer(added);
        }
    }

    public static class SremCommand implements Command {
        @Override
        public boolean isWriteCommand() {
            return true;
        }

        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() < 2) {
                return RespValue.error("ERR wrong number of arguments for 'srem' command");
            }
            String key = args.get(0).asString();
            List<ByteArrayKey> members = new ArrayList<>(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                members.add(ByteArrayKey.of(args.get(i).asBytes()));
            }
            int removed = context.getStore().srem(key, members);
            return RespValue.integer(removed);
        }
    }

    public static class SismemberCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 2) {
                return RespValue.error("ERR wrong number of arguments for 'sismember' command");
            }
            String key = args.get(0).asString();
            ByteArrayKey member = ByteArrayKey.of(args.get(1).asBytes());
            boolean exists = context.getStore().sismember(key, member);
            return exists ? RespValue.ONE : RespValue.ZERO;
        }
    }

    public static class SmembersCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'smembers' command");
            }
            Set<ByteArrayKey> members = context.getStore().smembers(args.get(0).asString());
            List<RespValue> result = new ArrayList<>(members.size());
            for (ByteArrayKey member : members) {
                result.add(RespValue.bulkString(member.getData()));
            }
            return RespValue.array(result);
        }
    }

    public static class ScardCommand implements Command {
        @Override
        public RespValue execute(CommandContext context, List<RespValue> args) {
            if (args.size() != 1) {
                return RespValue.error("ERR wrong number of arguments for 'scard' command");
            }
            int count = context.getStore().scard(args.get(0).asString());
            return RespValue.integer(count);
        }
    }
}
