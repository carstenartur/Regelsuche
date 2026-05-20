package de.regelsuche.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-written JSON reader used to re-hydrate
 * {@link InMemoryRuleInventoryRepository} snapshots without pulling in a
 * full JSON library.
 *
 * <p>It supports the very narrow subset emitted by
 * {@link InMemoryRuleInventoryRepository#persistTo(java.nio.file.Path)}:
 * an object with one named array of flat objects whose values are strings,
 * numbers, booleans, {@code null} or arrays of strings. Anything else will
 * be ignored or produce best-effort string values.</p>
 */
final class MiniJson {
    private MiniJson() {
    }

    /** Parse the named top-level array of objects into a list of key/value maps. */
    static List<Map<String, String>> parseObjectArray(String json, String key) {
        Cursor cursor = new Cursor(json);
        cursor.expect('{');
        List<Map<String, String>> result = new ArrayList<>();
        while (true) {
            cursor.skipWhitespace();
            if (cursor.peek('}')) {
                cursor.advance();
                break;
            }
            String fieldName = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            if (fieldName.equals(key)) {
                cursor.skipWhitespace();
                cursor.expect('[');
                while (true) {
                    cursor.skipWhitespace();
                    if (cursor.peek(']')) {
                        cursor.advance();
                        break;
                    }
                    result.add(parseFlatObject(cursor));
                    cursor.skipWhitespace();
                    if (cursor.peek(',')) {
                        cursor.advance();
                    }
                }
            } else {
                cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.peek(',')) {
                cursor.advance();
            }
        }
        return result;
    }

    /** Parse a JSON array of strings into a Java list (e.g. {@code ["a","b"]}). */
    static List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Cursor cursor = new Cursor(json);
        cursor.skipWhitespace();
        cursor.expect('[');
        List<String> out = new ArrayList<>();
        while (true) {
            cursor.skipWhitespace();
            if (cursor.peek(']')) {
                cursor.advance();
                break;
            }
            out.add(cursor.readString());
            cursor.skipWhitespace();
            if (cursor.peek(',')) {
                cursor.advance();
            }
        }
        return out;
    }

    private static Map<String, String> parseFlatObject(Cursor cursor) {
        Map<String, String> result = new LinkedHashMap<>();
        cursor.skipWhitespace();
        cursor.expect('{');
        while (true) {
            cursor.skipWhitespace();
            if (cursor.peek('}')) {
                cursor.advance();
                return result;
            }
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            cursor.skipWhitespace();
            result.put(key, cursor.readRawValue());
            cursor.skipWhitespace();
            if (cursor.peek(',')) {
                cursor.advance();
            }
        }
    }

    private static final class Cursor {
        private final String source;
        private int position;

        Cursor(String source) {
            this.source = source;
            this.position = 0;
        }

        void expect(char expected) {
            skipWhitespace();
            if (position >= source.length() || source.charAt(position) != expected) {
                throw new IllegalArgumentException(
                    "Expected '" + expected + "' at position " + position);
            }
            position++;
        }

        boolean peek(char c) {
            skipWhitespace();
            return position < source.length() && source.charAt(position) == c;
        }

        void advance() {
            position++;
        }

        void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
        }

        String readString() {
            skipWhitespace();
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\' && position < source.length()) {
                    char escaped = source.charAt(position++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case '/' -> builder.append('/');
                        case 'u' -> {
                            if (position + 4 > source.length()) {
                                throw new IllegalArgumentException("Truncated unicode escape");
                            }
                            int codePoint = Integer.parseInt(source.substring(position, position + 4), 16);
                            builder.append((char) codePoint);
                            position += 4;
                        }
                        default -> builder.append(escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        /** Read the raw value text (string contents, number, boolean, null, or nested array). */
        String readRawValue() {
            skipWhitespace();
            if (position >= source.length()) {
                return "";
            }
            char c = source.charAt(position);
            if (c == '"') {
                return readString();
            }
            if (c == '[') {
                int start = position;
                skipArray();
                return source.substring(start, position);
            }
            if (c == '{') {
                int start = position;
                skipObject();
                return source.substring(start, position);
            }
            int start = position;
            while (position < source.length()
                && ",}]\n\r\t ".indexOf(source.charAt(position)) < 0) {
                position++;
            }
            return source.substring(start, position);
        }

        void skipValue() {
            readRawValue();
        }

        private void skipArray() {
            expect('[');
            int depth = 1;
            while (position < source.length() && depth > 0) {
                char c = source.charAt(position++);
                if (c == '"') {
                    skipStringFromInside();
                } else if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                }
            }
        }

        private void skipObject() {
            expect('{');
            int depth = 1;
            while (position < source.length() && depth > 0) {
                char c = source.charAt(position++);
                if (c == '"') {
                    skipStringFromInside();
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }

        private void skipStringFromInside() {
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '\\' && position < source.length()) {
                    position++;
                } else if (c == '"') {
                    return;
                }
            }
        }
    }
}
