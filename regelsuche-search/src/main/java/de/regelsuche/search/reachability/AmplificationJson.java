package de.regelsuche.search.reachability;

import de.regelsuche.ast.Expr;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Canonical observations only. Deserialization never creates an executor or proof capability. */
public final class AmplificationJson {
    private AmplificationJson() { }

    public static Map<String, Object> fields(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("unpaired fields");
        Map<String, Object> result = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (result.put((String) pairs[i], pairs[i + 1]) != null) throw new IllegalArgumentException("duplicate field");
        }
        return result;
    }

    public static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            String array = new JsonWriter().beginArray().value(text).endArray().toString();
            return escapeUnpairedCodeunits(array.substring(1, array.length() - 1));
        }
        if (value instanceof Enum<?> item) return canonical(item.name());
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof Expr expression) return canonical(ExpressionFormatter.format(expression));
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            return "{" + String.join(",", sorted.entrySet().stream()
                .map(entry -> canonical(entry.getKey()) + ":" + canonical(entry.getValue())).toList()) + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            var values = new ArrayList<String>();
            iterable.forEach(item -> values.add(canonical(item)));
            if (value instanceof Set<?>) values.sort(String::compareTo);
            return "[" + String.join(",", values) + "]";
        }
        if (value.getClass().isRecord()) {
            var fields = new TreeMap<String, Object>();
            for (var component : value.getClass().getRecordComponents()) {
                try { fields.put(component.getName(), component.getAccessor().invoke(value)); }
                catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("unreadable observation record", failure); }
            }
            return canonical(fields);
        }
        throw new IllegalArgumentException("unsupported observation type: " + value.getClass().getName());
    }

    /** Preserve permitted Java string identities before UTF-8 hashing and transport. */
    private static String escapeUnpairedCodeunits(String quoted) {
        var result = new StringBuilder(quoted.length());
        for (int index = 0; index < quoted.length(); index++) {
            char current = quoted.charAt(index);
            if (Character.isHighSurrogate(current) && index + 1 < quoted.length()
                    && Character.isLowSurrogate(quoted.charAt(index + 1))) {
                result.append(current).append(quoted.charAt(++index));
            } else if (Character.isSurrogate(current)) {
                result.append("\\u").append(HexFormat.of().toHexDigits(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    public static String hash(Object value) { return hashBytes(canonical(value).getBytes(StandardCharsets.UTF_8)); }
    public static String hashBytes(byte[] value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Map<String, Object> read(String canonical) {
        if (canonical == null || canonical.length() > 8_000_000) throw new IllegalArgumentException("observation size exceeded");
        var result = new JsonReader(canonical).readObject();
        if (!canonical(result).equals(canonical)) throw new IllegalArgumentException("noncanonical observation");
        return result;
    }
}
