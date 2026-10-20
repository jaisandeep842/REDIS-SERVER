package redis.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes RespValue objects into RESP2 compliant byte sequences.
 */
public class RespSerializer {

    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] NULL_BULK_STRING = new byte[]{'$', '-', '1', '\r', '\n'};
    private static final byte[] NULL_ARRAY = new byte[]{'*', '-', '1', '\r', '\n'};

    public static byte[] serialize(RespValue value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            write(value, baos);
        } catch (IOException e) {
            // ByteArrayOutputStream should not throw IOException
            throw new RuntimeException("Serialization failure", e);
        }
        return baos.toByteArray();
    }

    public static void write(RespValue value, OutputStream out) throws IOException {
        if (value == null || value.isNull()) {
            out.write(NULL_BULK_STRING);
            return;
        }

        switch (value.getType()) {
            case SIMPLE_STRING -> {
                out.write('+');
                out.write(value.asString().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
            case ERROR -> {
                out.write('-');
                out.write(value.asString().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
            case INTEGER -> {
                out.write(':');
                out.write(Long.toString(value.asLong()).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
            }
            case BULK_STRING -> {
                byte[] bytes = value.asBytes();
                out.write('$');
                out.write(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
                out.write(bytes);
                out.write(CRLF);
            }
            case ARRAY -> {
                List<RespValue> elements = value.asArray();
                out.write('*');
                out.write(Integer.toString(elements.size()).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
                for (RespValue element : elements) {
                    write(element, out);
                }
            }
            case NULL -> out.write(NULL_BULK_STRING);
        }
    }
}
