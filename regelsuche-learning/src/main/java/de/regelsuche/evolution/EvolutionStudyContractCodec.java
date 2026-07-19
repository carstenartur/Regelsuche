package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Strict JSON codec for preregistered evolution study and split contracts. */
public final class EvolutionStudyContractCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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
        return read(json, EvolutionSplitManifest.class, "split manifest");
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
            throw new IllegalStateException("Unable to write evolution study contract", exception);
        }
    }

    private static <T> T read(String json, Class<T> type, String name) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(name + " JSON must not be blank");
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid evolution " + name + " JSON", exception);
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
            throw new IllegalArgumentException("Unable to read evolution " + name, exception);
        }
    }
}
