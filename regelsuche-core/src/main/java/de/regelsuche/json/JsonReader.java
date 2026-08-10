package de.regelsuche.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small recursive descent JSON reader to keep export round-trips
 * self-contained without pulling in an external dependency.
 */
public final class JsonReader {
    private final String source;
    private int index;

    public JsonReader(String source) {
        this.source = source;
        this.index = 0;
    }

    public Map<String, Object> readObject() {
        Object value = readDocument();
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> result.put((String) key, item));
            return result;
        }
        throw new IllegalArgumentException("Expected JSON object at document root");
    }

    public List<Object> readArray() {
        Object value = readDocument();
        if (value instanceof List<?> raw) {
            return new ArrayList<>(raw);
        }
        throw new IllegalArgumentException("Expected JSON array at document root");
    }

    private Object readDocument() {
        skipWhitespace();
        Object value = readValue();
        skipWhitespace();
        if (index != source.length()) {
            throw new IllegalArgumentException(
                "Unexpected trailing JSON content at position " + index);
        }
        return value;
    }

    private Map<String, Object> readObjectValue() {
        expect('{');
        Map<String, Object> values = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            index++;
            return values;
        }
        do {
            String key = readString();
            if (values.containsKey(key)) {
                throw new IllegalArgumentException(
                    "Duplicate JSON object key '" + key + "'");
            }
            skipWhitespace();
            expect(':');
            values.put(key, readValue());
            skipWhitespace();
        } while (consume(','));
        expect('}');
        return values;
    }

    private List<Object> readArrayValue() {
        expect('[');
        List<Object> values = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            index++;
            return values;
        }
        do {
            values.add(readValue());
            skipWhitespace();
        } while (consume(','));
        expect(']');
        return values;
    }

    private Object readValue() {
        skipWhitespace();
        if (peek('{')) {
            return readObjectValue();
        }
        if (peek('[')) {
            return readArrayValue();
        }
        if (peek('"')) {
            return readString();
        }
        if (source.startsWith("true", index)) {
            index += 4;
            return true;
        }
        if (source.startsWith("false", index)) {
            index += 5;
            return false;
        }
        if (source.startsWith("null", index)) {
            index += 4;
            return null;
        }
        return readNumber();
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (index < source.length()) {
            char current = source.charAt(index++);
            if (current == '"') {
                return builder.toString();
            }
            if (current == '\\') {
                char escaped = source.charAt(index++);
                builder.append(switch (escaped) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'u' -> readUnicodeEscape();
                    default -> escaped;
                });
            } else {
                builder.append(current);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private char readUnicodeEscape() {
        String hex = source.substring(index, index + 4);
        index += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private Number readNumber() {
        int start = index;
        if (peek('-')) {
            index++;
        }
        while (index < source.length()) {
            char current = source.charAt(index);
            if (!Character.isDigit(current) && current != '.' && current != 'e'
                    && current != 'E' && current != '+' && current != '-') {
                break;
            }
            index++;
        }
        String value = source.substring(start, index);
        if (value.contains(".") || value.contains("e") || value.contains("E")) {
            return Double.parseDouble(value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return Long.parseLong(value);
        }
    }

    private boolean consume(char expected) {
        skipWhitespace();
        if (peek(expected)) {
            index++;
            return true;
        }
        return false;
    }

    private boolean peek(char expected) {
        return index < source.length() && source.charAt(index) == expected;
    }

    private void expect(char expected) {
        skipWhitespace();
        if (!peek(expected)) {
            throw new IllegalArgumentException(
                "Expected " + expected + " at position " + index
                    + " in " + source);
        }
        index++;
    }

    private void skipWhitespace() {
        while (index < source.length()
                && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
    }
}
