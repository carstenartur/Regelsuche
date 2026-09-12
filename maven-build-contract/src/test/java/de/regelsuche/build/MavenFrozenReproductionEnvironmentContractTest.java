package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Maintenance ownership must not rewrite a retained scientific environment. */
class MavenFrozenReproductionEnvironmentContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final String DEPENDABOT = ".github/dependabot.yml";
    private static final String IMAGE_POLICY = "config/quality/container-image-policy.json";
    private static final String V1_IMAGE = "eclipse-temurin:25.0.3_9-jdk-noble@sha256:"
        + "3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617";
    private static final Set<String> FROZEN_DOCKERFILES = Set.of(
        "Dockerfile.target-free-held-out-reproduction", "reproduction/Dockerfile.reproduction");
    // Exact v1 bytes retained at 8afeda1a538c6726b0f6b8a6c8014dfcced0fbe2.
    // Future environments add a new version; they never replace these identities.
    private static final Map<String, String> V1_SHA256 = Map.of(
        "Dockerfile.target-free-held-out-reproduction",
        "84bf7e58cc7efe0057bc9eea0fd971dc811dbf52da8d54bd57dfdf40586615e3",
        "reproduction/Dockerfile.reproduction",
        "a7c3724ac5f679be9ae9d90f141da5fe60befd74b768a123d737f0ea56c441cf",
        "docs/schemas/regelsuche-independent-reproduction-artifact-v1.schema.json",
        "e7e353cc1e3ea0996ba4d2e88bdbeb2b6ea123ae8da4bffd7ad209a8741878bd",
        "docs/schemas/regelsuche-independent-reproduction-receipt-v1.schema.json",
        "074943b4dfc5882ff88aec976b2f0018231904d8d9f5256611aa18510de976ea",
        "docs/schemas/regelsuche-target-free-held-out-container-reproduction-v1.schema.json",
        "a7ab72ae66ee96b5f4d98ea71bc0e7bb9b7e20b1a615b107f7157ca0338dcc9a");

    @Test
    void repositorySeparatesRoutineImagesFromFrozenV1Evidence() throws IOException {
        var result = evaluate(repositoryRoot());
        assertTrue(result.violations().isEmpty(), result.violations().toString());
        assertEquals(Set.of("Dockerfile", "Dockerfile.autopilot", "Dockerfile.comparative-benchmarks",
            "Dockerfile.proof", "Dockerfile.release-readiness", "Dockerfile.visual-regression"),
            result.routineDockerfiles());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dockerfile.target-free-held-out-reproduction", "reproduction/Dockerfile.reproduction"})
    void removingEitherFrozenExclusionFailsClosed(String removed, @TempDir Path temporary)
            throws IOException {
        fixture(temporary);
        ObjectNode config = config(temporary);
        ArrayNode excludes = docker(config).putArray("exclude-paths");
        FROZEN_DOCKERFILES.stream().filter(path -> !path.equals(removed)).sorted().forEach(excludes::add);
        YAML.writeValue(temporary.resolve(DEPENDABOT).toFile(), config);

        assertViolation(temporary, "frozen Docker exclusions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dockerfile.target-free-held-out-reproduction", "reproduction/Dockerfile.reproduction"})
    void partialImageAndPolicyUpdateCannotRedefineV1(String changed, @TempDir Path temporary)
            throws IOException {
        fixture(temporary);
        replaceImageAndPolicy(temporary, changed, V1_IMAGE, "example/new-environment:25.0.5");

        assertViolation(temporary, "v1 bytes changed: " + changed);
        assertViolation(temporary, "v1 image policy changed: " + changed);
    }

    @Test
    void updatingBothImagesTogetherStillCannotRedefineV1(@TempDir Path temporary)
            throws IOException {
        fixture(temporary);
        for (String path : FROZEN_DOCKERFILES) {
            replaceImageAndPolicy(temporary, path, V1_IMAGE, "example/new-environment:25.0.5");
        }

        assertViolation(temporary, "v1 bytes changed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"independent-reproduction-artifact", "independent-reproduction-receipt",
        "target-free-held-out-container-reproduction"})
    void evenSemanticallyIdenticalV1SchemaEditsInvalidateTheFrozenBytes(
            String schema, @TempDir Path temporary) throws IOException {
        fixture(temporary);
        String relative = "docs/schemas/regelsuche-" + schema + "-v1.schema.json";
        Path path = temporary.resolve(relative);
        byte[] original = Files.readAllBytes(path);
        Files.writeString(path, Files.readString(path) + "\n");
        assertEquals(JSON.readTree(original), JSON.readTree(path.toFile()));

        assertViolation(temporary, "v1 bytes changed: " + relative);
    }

    @ParameterizedTest
    @ValueSource(strings = {"25.0.4_7", "25.0.5_8"})
    void ordinaryImageAndPolicyUpdatesRemainAllowed(String currentVersion, @TempDir Path temporary)
            throws IOException {
        fixture(temporary);
        String currentImage = "eclipse-temurin:" + currentVersion + "-jdk-noble";
        Files.writeString(temporary.resolve("Dockerfile"), "FROM " + currentImage + "\n");
        ObjectNode policy = (ObjectNode) JSON.readTree(temporary.resolve(IMAGE_POLICY).toFile());
        ((ObjectNode) policy.get("files")).putArray("Dockerfile").add(currentImage);
        JSON.writeValue(temporary.resolve(IMAGE_POLICY).toFile(), policy);
        String beforeUpdate = Files.readString(temporary.resolve("Dockerfile"));
        replaceImageAndPolicy(temporary, "Dockerfile", currentImage,
            "eclipse-temurin:25.0.6_9-jdk-noble");
        assertFalse(Files.readString(temporary.resolve("Dockerfile"))
            .equals(beforeUpdate));

        var result = evaluate(temporary);
        assertTrue(result.violations().isEmpty(), result.violations().toString());
        assertTrue(result.routineDockerfiles().contains("Dockerfile"));
    }

    @Test
    void aSecondDockerJobCannotBypassTheFrozenExclusions(@TempDir Path temporary) throws IOException {
        fixture(temporary);
        ObjectNode config = config(temporary);
        ObjectNode additional = ((ArrayNode) config.get("updates")).addObject();
        additional.put("package-ecosystem", "docker");
        additional.put("directory", "/reproduction");
        additional.putObject("schedule").put("interval", "weekly");
        YAML.writeValue(temporary.resolve(DEPENDABOT).toFile(), config);

        assertViolation(temporary, "one root Docker job");
    }

    @Test
    void blanketTemurinIgnoreCannotDisableOrdinaryImageMaintenance(@TempDir Path temporary)
            throws IOException {
        fixture(temporary);
        ObjectNode config = config(temporary);
        docker(config).putArray("ignore").addObject().put("dependency-name", "eclipse-temurin");
        YAML.writeValue(temporary.resolve(DEPENDABOT).toFile(), config);

        assertViolation(temporary, "routine Temurin updates disabled");
    }

    private static Evaluation evaluate(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        ObjectNode config = config(root);
        List<JsonNode> dockerJobs = new ArrayList<>();
        config.path("updates").forEach(update -> {
            if ("docker".equals(update.path("package-ecosystem").asText())) dockerJobs.add(update);
        });
        Set<String> exclusions = new TreeSet<>();
        if (dockerJobs.size() != 1 || !"/".equals(dockerJobs.getFirst().path("directory").asText())
                || dockerJobs.getFirst().has("directories")) {
            violations.add("routine ownership requires one root Docker job; review new scopes explicitly");
        } else {
            JsonNode job = dockerJobs.getFirst();
            job.path("exclude-paths").forEach(path -> exclusions.add(path.asText()));
            // Deliberately use literal paths: broader globs could silently suppress ordinary images.
            if (!exclusions.equals(FROZEN_DOCKERFILES)) {
                violations.add("frozen Docker exclusions must cover exactly " + FROZEN_DOCKERFILES);
            }
            if (job.has("allow") || job.has("target-branch")
                    || job.path("open-pull-requests-limit").asInt(5) <= 0) {
                violations.add("routine Docker maintenance restricted or redirected");
            }
            for (JsonNode ignore : job.path("ignore")) {
                String dependency = ignore.path("dependency-name").asText();
                String regex = String.join(".*", Arrays.stream(dependency.split("\\*", -1))
                    .map(Pattern::quote).toList());
                if ("eclipse-temurin".matches(regex)
                        && (!ignore.path("update-types").equals(
                            JSON.createArrayNode().add("version-update:semver-major"))
                            || ignore.has("versions"))) {
                    violations.add("routine Temurin updates disabled by ignore rule");
                }
            }
        }
        JsonNode policy = JSON.readTree(root.resolve(IMAGE_POLICY).toFile()).path("files");
        Set<String> routine = new TreeSet<>();
        policy.fieldNames().forEachRemaining(path -> {
            // The Docker fetcher reads matching files directly in its configured directory.
            if (Path.of(path).getParent() == null && !exclusions.contains(path)) routine.add(path);
        });
        for (String path : FROZEN_DOCKERFILES) {
            if (!policy.path(path).equals(JSON.createArrayNode().add(V1_IMAGE))) {
                violations.add("v1 image policy changed: " + path);
            }
        }
        for (var entry : V1_SHA256.entrySet()) {
            Path path = root.resolve(entry.getKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !entry.getValue().equals(sha256(Files.readAllBytes(path)))) {
                violations.add("v1 bytes changed: " + entry.getKey());
            }
        }
        return new Evaluation(Set.copyOf(routine), List.copyOf(violations));
    }

    private static void fixture(Path destination) throws IOException {
        Path root = repositoryRoot();
        Set<String> paths = new TreeSet<>(V1_SHA256.keySet());
        paths.addAll(List.of(DEPENDABOT, IMAGE_POLICY, "Dockerfile"));
        for (String relative : paths) {
            Path target = destination.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(root.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING);
        }
        // A passing ownership control, independent of whether the checkout has been fixed yet.
        ObjectNode config = config(destination);
        ArrayNode exclusions = docker(config).putArray("exclude-paths");
        FROZEN_DOCKERFILES.stream().sorted().forEach(exclusions::add);
        YAML.writeValue(destination.resolve(DEPENDABOT).toFile(), config);
        assertTrue(evaluate(destination).violations().isEmpty());
    }

    private static void replaceImageAndPolicy(Path root, String path, String oldImage, String newImage)
            throws IOException {
        Path dockerfile = root.resolve(path);
        Files.writeString(dockerfile, Files.readString(dockerfile).replace(oldImage, newImage));
        ObjectNode policy = (ObjectNode) JSON.readTree(root.resolve(IMAGE_POLICY).toFile());
        ArrayNode images = (ArrayNode) policy.path("files").path(path);
        for (int index = 0; index < images.size(); index++) {
            images.set(index, JSON.getNodeFactory().textNode(images.get(index).asText().replace(oldImage, newImage)));
        }
        JSON.writeValue(root.resolve(IMAGE_POLICY).toFile(), policy);
    }

    private static ObjectNode config(Path root) throws IOException {
        return (ObjectNode) YAML.readTree(root.resolve(DEPENDABOT).toFile());
    }

    private static ObjectNode docker(ObjectNode config) {
        for (JsonNode update : config.path("updates")) {
            if ("docker".equals(update.path("package-ecosystem").asText())) return (ObjectNode) update;
        }
        throw new IllegalArgumentException("Docker maintenance job missing");
    }

    private static void assertViolation(Path root, String detail) throws IOException {
        var violations = evaluate(root).violations();
        assertTrue(violations.stream().anyMatch(message -> message.contains(detail)), violations.toString());
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("regelsuche.repositoryRoot")).toAbsolutePath().normalize();
    }

    private record Evaluation(Set<String> routineDockerfiles, List<String> violations) { }
}
