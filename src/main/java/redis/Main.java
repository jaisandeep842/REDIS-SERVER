package redis;

import redis.config.ServerConfig;
import redis.server.RedisServer;

import java.io.IOException;
import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.createDefault();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (("--port".equals(arg) || "-p".equals(arg)) && i + 1 < args.length) {
                config.setPort(Integer.parseInt(args[++i]));
            } else if (("--host".equals(arg) || "-h".equals(arg)) && i + 1 < args.length) {
                config.setHost(args[++i]);
            } else if (("--threads".equals(arg) || "-t".equals(arg)) && i + 1 < args.length) {
                config.setWorkerThreads(Integer.parseInt(args[++i]));
            } else if (("--dir".equals(arg) || "-d".equals(arg)) && i + 1 < args.length) {
                config.setDataDirectory(Paths.get(args[++i]));
            } else if ("--aof".equals(arg) && i + 1 < args.length) {
                config.setPersistenceEnabled(Boolean.parseBoolean(args[++i]));
            } else if ("--help".equals(arg)) {
                printHelp();
                return;
            }
        }

        printBanner(config);

        RedisServer server = new RedisServer(config);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start Redis server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Keep process running
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Main thread interrupted, exiting...");
            server.stop();
        }
    }

    private static void printBanner(ServerConfig config) {
        System.out.println("""
                 ______          _ _            _
                 | ___ \\        | (_)          | |
                 | |_/ /___   __| |_ ___ ______| | __ ___   ____ _
                 |    // _ \\ / _` | / __|______| |/ // _ \\ / _` |
                 | |\\ \\  __/| (_| | \\__ \\      |   <|  __/| (_| |
                 \\_| \\_\\___| \\__,_|_|___/      |_|\\_\\\\___| \\__,_|
                """);
        System.out.println("  Redis-Compatible Server (Java 21)");
        System.out.println("  Port:        " + config.getPort());
        System.out.println("  Host:        " + config.getHost());
        System.out.println("  Workers:     " + config.getWorkerThreads());
        System.out.println("  Persistence: " + (config.isPersistenceEnabled() ? "AOF (" + config.getAofPath() + ")" : "Disabled"));
        System.out.println("  Ready to accept connections.\n");
    }

    private static void printHelp() {
        System.out.println("""
            Usage: java -jar redis-server.jar [options]

            Options:
              -p, --port <port>       Port to listen on (default: 6379)
              -h, --host <host>       Bind host address (default: 127.0.0.1)
              -t, --threads <num>     Worker thread pool size (default: 16)
              -d, --dir <path>        Data directory for persistence (default: data)
                  --aof <true|false>  Enable/disable AOF persistence (default: true)
                  --help              Show this help message
            """);
    }
}
