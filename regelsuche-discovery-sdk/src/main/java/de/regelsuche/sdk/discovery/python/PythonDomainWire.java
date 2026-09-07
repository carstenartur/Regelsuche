package de.regelsuche.sdk.discovery.python;

import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Narrow canonical data protocol, not a Python-expression parser. */
final class PythonDomainWire {
    private PythonDomainWire() { }

    static Map<String, Object> read(String text, int maxBytes) {
        bytes(text, maxBytes);
        // Bound recursion BEFORE the existing JSON reader sees guest data.
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') require(++depth <= 16, "JSON nesting limit");
            else if (c == ']' || c == '}') require(--depth >= 0, "unbalanced JSON");
        }
        require(depth == 0 && !quoted, "incomplete JSON");
        Map<String, Object> object = new JsonReader(text).readObject();
        // This also rejects permissive reader spellings (unknown escapes,
        // leading signs/zeroes, raw controls), floats, null and trailing space.
        require(text.equals(canonical(object)), "noncanonical callback JSON");
        return object;
    }

    static String canonical(Map<String, ?> object) {
        JsonWriter writer = new JsonWriter().beginObject();
        properties(writer, object, 0);
        return writer.endObject().toString();
    }

    private static void properties(JsonWriter writer, Map<String, ?> object, int depth) {
        require(depth <= 16, "JSON nesting limit");
        new TreeMap<>(object).forEach((key, value) -> {
            require(key.matches("[A-Za-z][A-Za-z0-9]*"), "protocol field name");
            if (value instanceof String string) { validUnicode(string); writer.property(key, string); }
            else if (value instanceof Integer integer) writer.property(key, integer);
            else if (value instanceof Boolean bool) writer.property(key, bool);
            else if (value instanceof Map<?, ?> map) writer.object(key, w -> properties(w, object(map), depth + 1));
            else if (value instanceof List<?> list) writer.array(key, w -> elements(w, list, depth + 1));
            else throw new IllegalArgumentException("unsupported protocol value");
        });
    }

    private static void elements(JsonWriter writer, List<?> list, int depth) {
        require(depth <= 16, "JSON nesting limit");
        for (Object value : list) {
            if (value instanceof String string) { validUnicode(string); writer.value(string); }
            else if (value instanceof Integer integer) writer.value(integer);
            else if (value instanceof Boolean bool) writer.value(bool);
            else if (value instanceof Map<?, ?> map) writer.objectValue(w -> properties(w, object(map), depth + 1));
            else if (value instanceof List<?> nested) writer.arrayValue(w -> elements(w, nested, depth + 1));
            else throw new IllegalArgumentException("unsupported protocol value");
        }
    }

    static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "object required");
        Map<String, Object> copy = new TreeMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> {
            require(key instanceof String, "string field required");
            copy.put((String) key, item);
        });
        return copy;
    }

    static void fields(Map<String, ?> object, String... keys) {
        require(object.keySet().equals(java.util.Set.of(keys)), "callback field set");
    }

    static int integer(Object value, int minimum, int maximum) {
        require(value instanceof Integer, "bounded integer required");
        int integer = (Integer) value;
        require(integer >= minimum && integer <= maximum, "integer range");
        return integer;
    }

    static boolean bool(Object value) {
        require(value instanceof Boolean, "boolean required");
        return (Boolean) value;
    }

    static String text(Object value, int maxBytes, boolean emptyAllowed) {
        require(value instanceof String, "string required");
        String string = (String) value;
        bytes(string, maxBytes);
        require(emptyAllowed || !string.isBlank(), "empty payload");
        return string;
    }

    static List<?> list(Object value, int maximum) {
        require(value instanceof List<?>, "array required");
        List<?> list = (List<?>) value;
        require(list.size() <= maximum, "array count limit");
        return list;
    }

    static int bytes(String text, int maximum) {
        require(text != null && text.length() <= maximum, "payload size limit");
        validUnicode(text);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        require(bytes <= maximum, "UTF-8 payload size limit");
        return bytes;
    }

    static void validUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(++i < text.length() && Character.isLowSurrogate(text.charAt(i)), "unpaired surrogate");
            } else require(!Character.isLowSurrogate(c), "unpaired surrogate");
        }
    }

    static String sha256(String text) {
        validUnicode(text);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
