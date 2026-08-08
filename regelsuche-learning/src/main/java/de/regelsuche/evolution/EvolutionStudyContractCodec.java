package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Strict JSON codec for preregistered evolution study and split contracts. */
public final class EvolutionStudyContractCodec {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String write(EvolutionSplitManifest manifest) {
        return Objects.requireNonNull(manifest, "manifest").toCanonicalJson();
    }

    public String write(EvolutionStudyPlan plan) {
        return Objects.requireNonNull(plan, "plan").toCanonicalJson();
    }

    public Path write(Path output, EvolutionSplitManifest manifest) {
        return writeFile(output, write(manifest));
    }

    public Path write(Path output, EvolutionStudyPlan plan) {
        return writeFile(output, write(plan));
    }

    public EvolutionSplitManifest readSplitManifest(String json) {
        requireJson(json, "split manifest");
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException(
                    "split manifest JSON must contain one object");
            }
            ObjectNode object = (ObjectNode) parsed;
            JsonNode marker = object.remove("heldOutMaterialization");
            boolean deferredDeclared = marker != null;
            if (deferredDeclared
                    && (!marker.isTextual()
                        || !EvolutionSplitManifest.DEFERRED_HELD_OUT.equals(
                            marker.textValue()))) {
                throw new IllegalArgumentException(
                    "invalid held-out materialization boundary");
            }
            EvolutionSplitManifest manifest = JSON.treeToValue(
                object, EvolutionSplitManifest.class);
            if (manifest.heldOutMaterializationDeferred()
                    != deferredDeclared) {
                throw new IllegalArgumentException(
                    "held-out materialization marker differs from split state");
            }
            return manifest;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid evolution split manifest JSON", exception);
        }
    }

    public EvolutionStudyPlan readStudyPlan(String json) {
        return read(json, EvolutionStudyPlan.class, "study plan");
    }

    public EvolutionSplitManifest readSplitManifest(Path input) {
        return readFile(input, this::readSplitManifest, "split manifest");
    }

    public EvolutionStudyPlan readStudyPlan(Path input) {
        return readFile(input, this::readStudyPlan, "study plan");
    }

    private static Path writeFile(Path output, String json) {
        Objects.requireNonNull(output, "output");
        try {
            Path absolute = output.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(absolute, json, StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to write evolution study contract", exception);
        }
    }

    private static <T> T read(String json, Class<T> type, String name) {
        requireJson(json, name);
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid evolution " + name + " JSON", exception);
        }
    }

    private static void requireJson(String json, String name) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                name + " JSON must not be blank");
        }
    }

    private static <T> T readFile(
        Path input,
        java.util.function.Function<String, T> parser,
        String name
    ) {
        Objects.requireNonNull(input, "input");
        try {
            return parser.apply(Files.readString(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read evolution " + name, exception);
        }
    }
}
