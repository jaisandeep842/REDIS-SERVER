package redis.command;

import redis.protocol.RespType;
import redis.protocol.RespValue;
import redis.storage.WrongTypeException;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches parsed RESP requests to their corresponding command implementations,
 * manages persistence logging for write commands, and formats error responses.
 */
public class CommandHandler {

    private static final Logger LOGGER = Logger.getLogger(CommandHandler.class.getName());

    private final CommandRegistry registry;

    public CommandHandler(CommandRegistry registry) {
        this.registry = registry;
    }

    public RespValue handle(CommandContext context, RespValue request) {
        if (request == null || request.getType() != RespType.ARRAY) {
            return RespValue.error("ERR protocol error: expected array");
        }

        List<RespValue> elements = request.asArray();
        if (elements.isEmpty()) {
            return RespValue.error("ERR empty command");
        }

        String commandName = elements.get(0).asString();
        if (commandName == null || commandName.trim().isEmpty()) {
            return RespValue.error("ERR unknown command ''");
        }
        commandName = commandName.trim();
        if (commandName.startsWith("\uFEFF")) {
            commandName = commandName.substring(1).trim();
        }
        commandName = commandName.toUpperCase(Locale.ROOT);

        Command command = registry.get(commandName);
        if (command == null) {
            return RespValue.error("ERR unknown command '" + commandName.toLowerCase(Locale.ROOT) + "'");
        }

        List<RespValue> args = elements.subList(1, elements.size());
        context.incrementCommandCount();

        try {
            RespValue response = command.execute(context, args);

            // If write command was successful, record to persistence log
            if (command.isWriteCommand() && response.getType() != RespType.ERROR) {
                if (context.getPersistenceManager() != null && context.getPersistenceManager().isEnabled()) {
                    context.getPersistenceManager().recordCommand(context.getCurrentDbIndex(), elements);
                }
            }

            return response;
        } catch (WrongTypeException e) {
            return RespValue.error(e.getMessage());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "ERR invalid argument";
            return RespValue.error(msg.startsWith("ERR") || msg.startsWith("WRONGTYPE") ? msg : "ERR " + msg);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unexpected error executing command: " + commandName, e);
            return RespValue.error("ERR internal server error: " + e.getMessage());
        }
    }

    public CommandRegistry getRegistry() {
        return registry;
    }
}
