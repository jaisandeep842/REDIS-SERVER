package redis.storage;

public class WrongTypeException extends RuntimeException {
    public static final String DEFAULT_MESSAGE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    public WrongTypeException() {
        super(DEFAULT_MESSAGE);
    }

    public WrongTypeException(String message) {
        super(message);
    }
}
