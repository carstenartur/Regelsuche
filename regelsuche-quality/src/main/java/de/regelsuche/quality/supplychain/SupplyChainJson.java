package de.regelsuche.quality.supplychain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;

/** Strict wire format and checkout-contained file operations for this authority. */
final class SupplyChainJson {
    static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final long MAX_JSON_BYTES = 64L * 1024 * 1024;

    private SupplyChainJson() {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank(), "missing text: " + field);
        return value.textValue();
    }

    static ArrayNode array(JsonNode node, String field) {
        require(node.path(field).isArray(), "missing array: " + field);
        return (ArrayNode) node.get(field);
    }

    static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong(), "invalid integer: " + field);
        return value.longValue();
    }

    static void fields(JsonNode node, Set<String> allowed) {
        require(node.isObject(), "JSON object required");
        node.fieldNames().forEachRemaining(name -> require(allowed.contains(name), "unknown field: " + name));
    }

    static Set<String> stringSet(JsonNode array, String label) {
        require(array.isArray(), label + " must be an array");
        Set<String> values = new HashSet<>();
        for (JsonNode item : array) {
            require(item.isTextual() && !item.textValue().isBlank(), "invalid " + label);
            require(values.add(item.textValue()), "duplicate " + label + ": " + item.textValue());
        }
        return Set.copyOf(values);
    }

    static Path checked(Path root, Path input) throws IOException {
        Path base = root.toAbsolutePath();
        Path absolute = input.isAbsolute() ? input : base.resolve(input);
        for (Path part : absolute) require(!part.toString().equals(".."), "parent path is forbidden");
        absolute = absolute.normalize();
        base = base.normalize();
        require(absolute.startsWith(base) && !absolute.equals(base), "path must be inside checkout: " + input);
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            require(!Files.isSymbolicLink(current), "symbolic path is forbidden: " + current);
        }
        return absolute;
    }

    static Path file(Path root, Path path) throws IOException {
        Path result = checked(root, path);
        require(Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS), "regular file required: " + path);
        return result;
    }

    static ObjectNode read(Path path) throws IOException {
        require(Files.size(path) <= MAX_JSON_BYTES, "JSON exceeds byte limit: " + path);
        return parse(Files.readAllBytes(path));
    }

    static ObjectNode parse(byte[] bytes) throws IOException {
        require(bytes.length <= MAX_JSON_BYTES, "JSON exceeds byte limit");
        JsonNode result = JSON.readTree(bytes);
        require(result != null && result.isObject(), "JSON object required");
        return (ObjectNode) result;
    }

    static byte[] canonical(JsonNode value) throws IOException {
        return (JSON.writeValueAsString(ordered(value)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Object ordered(JsonNode node) {
        if (node.isObject()) {
            var map = new TreeMap<String, Object>();
            node.properties().forEach(entry -> map.put(entry.getKey(), ordered(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            node.forEach(item -> list.add(ordered(item)));
            return list;
        }
        return node;
    }

    static void write(Path root, Path path, byte[] bytes) throws IOException {
        Path output = checked(root, path);
        Files.createDirectories(output.getParent());
        checked(root, output);
        require(!Files.exists(output) || Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS),
            "output must be a regular file: " + output);
        Files.write(output, bytes);
    }

    static String hash(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] block = new byte[65536];
                int count;
                while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Path boundFile(Path root, JsonNode reference) throws IOException {
        String expected = text(reference, "sha256");
        require(expected.matches("sha256:[0-9a-f]{64}"), "invalid SHA-256");
        Path path = file(root, Path.of(text(reference, "path")));
        require(hash(path).equals(expected), "SHA-256 mismatch: " + root.relativize(path));
        return path;
    }
}
