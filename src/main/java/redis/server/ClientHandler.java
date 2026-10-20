package redis.server;

import redis.command.CommandContext;
import redis.command.CommandHandler;
import redis.protocol.ProtocolException;
import redis.protocol.RespParser;
import redis.protocol.RespSerializer;
import redis.protocol.RespValue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles communication with an individual connected TCP client session.
 * Reads RESP requests, executes commands, and writes RESP responses.
 */
public class ClientHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final CommandHandler commandHandler;
    private final CommandContext context;
    private final Runnable onClosedCallback;

    public ClientHandler(Socket socket, CommandHandler commandHandler, CommandContext context, Runnable onClosedCallback) {
        this.socket = socket;
        this.commandHandler = commandHandler;
        this.context = context;
        this.onClosedCallback = onClosedCallback;
    }

    @Override
    public void run() {
        String remoteAddress = socket.getRemoteSocketAddress().toString();
        LOGGER.info("Accepted connection from client: " + remoteAddress);

        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            RespParser parser = new RespParser(in);

            while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                RespValue request;
                try {
                    request = parser.parseNext();
                } catch (EOFException e) {
                    LOGGER.fine("Client disconnected: " + remoteAddress);
                    break;
                } catch (ProtocolException e) {
                    LOGGER.warning("Protocol error from " + remoteAddress + ": " + e.getMessage());
                    RespValue err = RespValue.error("ERR Protocol error: " + e.getMessage());
                    RespSerializer.write(err, out);
                    out.flush();
                    continue;
                }

                if (request == null) {
                    // Clean EOF from client
                    LOGGER.fine("Client closed connection cleanly: " + remoteAddress);
                    break;
                }

                RespValue response = commandHandler.handle(context, request);
                RespSerializer.write(response, out);
                out.flush();

                if (context.isCloseRequested()) {
                    LOGGER.fine("Connection closed by QUIT request: " + remoteAddress);
                    break;
                }
            }
        } catch (SocketException e) {
            LOGGER.fine("Socket closed or reset for client " + remoteAddress + ": " + e.getMessage());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "I/O error communicating with client " + remoteAddress, e);
        } finally {
            closeSocket();
            if (onClosedCallback != null) {
                onClosedCallback.run();
            }
            LOGGER.info("Client session ended: " + remoteAddress);
        }
    }

    public void closeSocket() {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}
