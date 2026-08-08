package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared strict JSON, canonicalization and validation support for the showcase. */
final class ProofCarryingShowcaseJsonSupport {
    static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    static final Pattern ENDPOINT = Pattern.compile("[a-z0-9.-]{3,128}");
    static final Pattern CLIENT =
        Pattern.compile("drand-client/[A-Za-z0-9._@:+-]{1,160}");

    private ProofCarryingShowcaseJsonSupport() {
    }

    static <T> T read(String json, Class<T> type, String description) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                description + " JSON must not be blank");
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid " + description + " JSON", exception);
        }
    }

    static <T> T read(Path path, Class<T> type, String description) {
        Objects.requireNonNull(path, "path");
        try {
            return read(
                Files.readString(path, StandardCharsets.UTF_8),
                type,
                description);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "cannot read " + description + " from " + path,
                exception);
        }
    }

    static JsonNode readTree(Path path, String description) {
        Objects.requireNonNull(path, "path");
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "cannot read " + description + " from " + path,
                exception);
        }
    }

    static String toCanonicalJson(Object value) {
        return canonicalJson(JSON.valueToTree(value)) + "\n";
    }

    static String hashPayload(Map<String, ?> value) {
        return EvolutionGenome.hash(canonicalJson(JSON.valueToTree(value)));
    }

    static String hashWithoutContentHash(Object value) {
        JsonNode node = JSON.valueToTree(value);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(
                "content-addressed value must serialize as a JSON object");
        }
        ObjectNode payload = object.deepCopy();
        payload.remove("contentHash");
        return EvolutionGenome.hash(canonicalJson(payload));
    }

    static String canonicalJson(JsonNode node) {
        StringBuilder output = new StringBuilder();
        appendCanonical(output, Objects.requireNonNull(node, "node"));
        return output.toString();
    }

    private static void appendCanonical(StringBuilder output, JsonNode node) {
        if (node.isObject()) {
            output.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                String name = names.get(index);
                appendScalar(output, JSON.valueToTree(name));
                output.append(':');
                appendCanonical(output, node.get(name));
            }
            output.append('}');
            return;
        }
        if (node.isArray()) {
            output.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                appendCanonical(output, node.get(index));
            }
            output.append(']');
            return;
        }
        appendScalar(output, node);
    }

    private static void appendScalar(StringBuilder output, JsonNode node) {
        try {
            output.append(JSON.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize canonical JSON scalar", exception);
        }
    }

    static Map<String, Object> payload(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                "payload requires key/value pairs");
        }
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            String key = requireText(
                (String) keyValues[index],
                "payload key");
            if (result.put(key, keyValues[index + 1]) != null) {
                throw new IllegalArgumentException(
                    "duplicate payload key " + key);
            }
        }
        return result;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank");
        }
        return value.trim();
    }

    static String requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
        return value;
    }

    static String requireHex64(String value, String name) {
        if (value == null || !HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException(
                name + " must contain 64 lowercase hex characters");
        }
        return value;
    }

    static String requireCommit(String value, String name) {
        if (value == null || !COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                name + " must be a lowercase 40-character commit");
        }
        return value;
    }

    static String requireEndpoint(String value) {
        if (value == null || !ENDPOINT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "endpointId has invalid syntax");
        }
        return value;
    }

    static String requireClient(String value) {
        if (value == null || !CLIENT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "verificationClient must identify a pinned drand client");
        }
        return value;
    }

    static String requireBoundedHex(
        String value,
        int minimumLength,
        int maximumLength,
        String name
    ) {
        if (value == null
                || value.length() < minimumLength
                || value.length() > maximumLength
                || value.length() % 2 != 0
                || !value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                name + " must be bounded even-length lowercase hex");
        }
        return value;
    }

    static List<String> immutableStrings(
        List<String> values,
        String name,
        boolean sort,
        boolean requireNonEmpty
    ) {
        Objects.requireNonNull(values, name);
        List<String> checked = values.stream()
            .map(value -> requireText(value, name + " entry"))
            .toList();
        if (checked.stream().distinct().count() != checked.size()) {
            throw new IllegalArgumentException(
                name + " must not contain duplicates");
        }
        if (requireNonEmpty && checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return sort
            ? checked.stream().sorted().toList()
            : List.copyOf(checked);
    }

    static List<String> immutableStringList(
        List<String> values,
        String name,
        boolean requireNonEmpty
    ) {
        Objects.requireNonNull(values, name);
        List<String> checked = values.stream()
            .map(value -> requireText(value, name + " entry"))
            .toList();
        if (requireNonEmpty && checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(checked);
    }

    static List<String> immutableHashes(
        List<String> values,
        String name,
        boolean requireNonEmpty
    ) {
        Objects.requireNonNull(values, name);
        List<String> checked = values.stream()
            .map(value -> requireSha256(value, name + " entry"))
            .toList();
        if (checked.stream().distinct().count() != checked.size()) {
            throw new IllegalArgumentException(
                name + " must not contain duplicates");
        }
        if (requireNonEmpty && checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked.stream().sorted().toList();
    }

    static List<Integer> immutableIntegers(
        List<Integer> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                name + " must not contain null");
        }
        return List.copyOf(values);
    }

    static byte[] sha256Bytes(String material) {
        String hash = EvolutionGenome.hash(material);
        return HexFormat.of().parseHex(hash.substring("sha256:".length()));
    }
}
