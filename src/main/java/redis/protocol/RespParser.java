package redis.protocol;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses RESP2 protocol messages from an InputStream.
 * Reads binary bytes accurately, handles CRLF, validates limits,
 * and supports inline commands (e.g. telnet / netcat clients).
 */
public class RespParser {

    public static final int MAX_LINE_LENGTH = 64 * 1024; // 64 KB max for single line (headers/inline)
    public static final int MAX_BULK_LENGTH = 64 * 1024 * 1024; // 64 MB max bulk string
    public static final int MAX_ARRAY_ELEMENTS = 100_000; // 100k array elements max

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in;
    }

    public static RespValue parse(byte[] data) throws IOException {
        return new RespParser(new ByteArrayInputStream(data)).parseNext();
    }

    /**
     * Parses the next RespValue from the stream.
     * Returns null if clean EOF is reached before any bytes are read.
     *
     * @return parsed RespValue, or null on clean EOF.
     * @throws IOException on I/O error or protocol violation.
     */
    public RespValue parseNext() throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // Clean EOF
        }

        // Check if first byte is a CRLF leftover or whitespace (e.g. empty line)
        while (firstByte == '\r' || firstByte == '\n') {
            firstByte = in.read();
            if (firstByte == -1) {
                return null;
            }
        }

        return switch ((char) firstByte) {
            case '+' -> parseSimpleString();
            case '-' -> parseError();
            case ':' -> parseInteger();
            case '$' -> parseBulkString();
            case '*' -> parseArray();
            default -> parseInlineCommand(firstByte);
        };
    }

    private RespValue parseSimpleString() throws IOException {
        String line = readLine();
        return RespValue.simpleString(line);
    }

    private RespValue parseError() throws IOException {
        String line = readLine();
        return RespValue.error(line);
    }

    private RespValue parseInteger() throws IOException {
        String line = readLine();
        try {
            long val = Long.parseLong(line);
            return RespValue.integer(val);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Invalid integer format: " + line);
        }
    }

    private RespValue parseBulkString() throws IOException {
        String line = readLine();
        long len;
        try {
            len = Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Invalid bulk string length: " + line);
        }

        if (len == -1) {
            return RespValue.nullValue();
        }
        if (len < -1) {
            throw new ProtocolException("Negative bulk string length: " + len);
        }
        if (len > MAX_BULK_LENGTH) {
            throw new ProtocolException("Bulk string length exceeds max limit (" + MAX_BULK_LENGTH + "): " + len);
        }

        int length = (int) len;
        byte[] buffer = new byte[length];
        readFully(buffer, 0, length);

        // Expect CRLF
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new ProtocolException("Expected CRLF after bulk string data");
        }

        return RespValue.bulkString(buffer);
    }

    private RespValue parseArray() throws IOException {
        String line = readLine();
        long count;
        try {
            count = Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Invalid array count: " + line);
        }

        if (count == -1) {
            return RespValue.nullValue();
        }
        if (count < -1) {
            throw new ProtocolException("Negative array count: " + count);
        }
        if (count > MAX_ARRAY_ELEMENTS) {
            throw new ProtocolException("Array length exceeds max limit (" + MAX_ARRAY_ELEMENTS + "): " + count);
        }

        int numElements = (int) count;
        List<RespValue> elements = new ArrayList<>(numElements);
        for (int i = 0; i < numElements; i++) {
            RespValue elem = parseNext();
            if (elem == null) {
                throw new EOFException("Unexpected EOF while parsing array elements (expected " + numElements + ", got " + i + ")");
            }
            elements.add(elem);
        }

        return RespValue.array(elements);
    }

    /**
     * Parses an inline command (e.g. "PING\r\n" or "SET mykey myval\r\n").
     * Supports basic quotes for arguments with spaces.
     */
    private RespValue parseInlineCommand(int firstByte) throws IOException {
        byte[] lineBytes = readLineBytes(firstByte);
        String line = new String(lineBytes, StandardCharsets.UTF_8).trim();
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return RespValue.EMPTY_ARRAY;
        }

        List<String> tokens = splitInlineTokens(line);
        List<RespValue> elements = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            elements.add(RespValue.bulkString(token));
        }
        return RespValue.array(elements);
    }

    private List<String> splitInlineTokens(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else if (c == '\\' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (!current.isEmpty()) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private String readLine() throws IOException {
        byte[] bytes = readLineBytes(-1);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] readLineBytes(int initialByte) throws IOException {
        byte[] buffer = new byte[256];
        int count = 0;

        if (initialByte != -1) {
            if (initialByte == '\n') {
                return new byte[0];
            }
            if (initialByte != '\r') {
                buffer[count++] = (byte) initialByte;
            }
        }

        int prev = (initialByte == '\r') ? '\r' : -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (count == 0 && initialByte == -1) {
                    throw new EOFException("Connection closed by peer");
                }
                // Trailing bytes without CRLF before EOF
                byte[] res = new byte[count];
                System.arraycopy(buffer, 0, res, 0, count);
                return res;
            }

            if (b == '\n') {
                // If previous was '\r', count was already incremented without adding '\r'
                byte[] res = new byte[count];
                System.arraycopy(buffer, 0, res, 0, count);
                return res;
            } else if (b == '\r') {
                prev = '\r';
                continue;
            } else {
                if (prev == '\r') {
                    // Stray \r not followed by \n
                    if (count >= buffer.length) {
                        buffer = expand(buffer);
                    }
                    buffer[count++] = (byte) '\r';
                    prev = -1;
                }
            }

            if (count >= MAX_LINE_LENGTH) {
                throw new ProtocolException("Line length exceeded max limit of " + MAX_LINE_LENGTH + " bytes");
            }

            if (count >= buffer.length) {
                buffer = expand(buffer);
            }
            buffer[count++] = (byte) b;
        }
    }

    private byte[] expand(byte[] buffer) {
        int newCap = Math.min(buffer.length * 2, MAX_LINE_LENGTH + 1);
        byte[] next = new byte[newCap];
        System.arraycopy(buffer, 0, next, 0, buffer.length);
        return next;
    }

    private void readFully(byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int read = in.read(b, off + n, len - n);
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading " + len + " bytes (read " + n + " bytes)");
            }
            n += read;
        }
    }
}
