package redis.storage;

public enum RedisType {
    STRING("string"),
    LIST("list"),
    SET("set"),
    HASH("hash"),
    NONE("none");

    private final String name;

    RedisType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
