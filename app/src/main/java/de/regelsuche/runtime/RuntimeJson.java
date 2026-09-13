package de.regelsuche.runtime;

import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Canonical observations only: this codec never constructs executable evidence. */
final class RuntimeJson {
    private RuntimeJson() { }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static String canonical(Map<String, ?> values) {
        var writer = new JsonWriter().beginObject();
        write(writer, values);
        return writer.endObject().toString();
    }

    static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Map<String, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("expected JSON object");
        }
        @SuppressWarnings("unchecked") Map<String, ?> result = (Map<String, ?>) map;
        return result;
    }

    static void only(Map<String, ?> value, String... keys) {
        var allowed = Set.of(keys);
        for (String key : value.keySet()) if (!allowed.contains(key)) {
            throw new IllegalArgumentException("unsupported runtime field: " + key);
        }
    }

    static String text(Map<String, ?> value, String key, String fallback) {
        if (!value.containsKey(key)) return fallback;
        if (!(value.get(key) instanceof String text)) throw new IllegalArgumentException(key + " must be text");
        return text;
    }

    static List<String> strings(Map<String, ?> value, String key) {
        if (!value.containsKey(key)) return List.of();
        if (!(value.get(key) instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException(key + " must be a string array");
        }
        return list.stream().map(String.class::cast).toList();
    }

    static boolean bool(Map<String, ?> value, String key, boolean fallback) {
        if (!value.containsKey(key)) return fallback;
        if (!(value.get(key) instanceof Boolean result)) throw new IllegalArgumentException(key + " must be boolean");
        return result;
    }

    static int integer(Map<String, ?> value, String key, int fallback, int min, int max) {
        if (!value.containsKey(key)) return fallback;
        Object number = value.get(key);
        if (!(number instanceof Integer || number instanceof Long)) throw new IllegalArgumentException(key + " must be an integer");
        long result = ((Number) number).longValue();
        if (result < min || result > max) throw new IllegalArgumentException(key + " is outside " + min + ".." + max);
        return (int) result;
    }

    /** Used solely on checkout-owned work/analysis records, never on rule objects. */
    static Map<String, Object> observation(Record record) {
        var values = new LinkedHashMap<String, Object>();
        for (var component : record.getClass().getRecordComponents()) {
            try {
                values.put(component.getName(), component.getAccessor().invoke(record));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("cannot retain runtime receipt", exception);
            }
        }
        return values;
    }

    private static Object normalized(Object value) {
        if (value instanceof Optional<?> optional) return normalized(optional.orElse(null));
        if (value instanceof Record record) return observation(record);
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof Set<?> set) return set.stream().map(Object::toString).sorted().toList();
        return value;
    }

    private static void write(JsonWriter writer, Map<String, ?> values) {
        new TreeMap<>(values).forEach((key, raw) -> {
            Object value = normalized(raw);
            if (value == null) writer.nullProperty(key);
            else if (value instanceof String string) writer.property(key, string);
            else if (value instanceof Boolean bool) writer.property(key, bool);
            else if (value instanceof Number number) writer.property(key, number.longValue());
            else if (value instanceof Map<?, ?>) writer.object(key, child -> write(child, object(value)));
            else if (value instanceof List<?> list) writer.array(key, child -> list.forEach(item -> writeValue(child, item)));
            else throw new IllegalArgumentException("unsupported runtime observation: " + value.getClass());
        });
    }

    private static void writeValue(JsonWriter writer, Object raw) {
        Object value = normalized(raw);
        if (value instanceof String string) writer.value(string);
        else if (value instanceof Map<?, ?>) writer.objectValue(child -> write(child, object(value)));
        else if (value instanceof List<?> list) writer.arrayValue(child -> list.forEach(item -> writeValue(child, item)));
        else throw new IllegalArgumentException("unsupported runtime array observation");
    }
}
