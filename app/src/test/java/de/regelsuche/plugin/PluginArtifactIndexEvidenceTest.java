package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactIndexEvidenceTest {
    private static final String LEFT_PROPERTY =
        "regelsuche.byteIdentity.left";
    private static final String RIGHT_PROPERTY =
        "regelsuche.byteIdentity.right";
    private static final String INCLUDE_PROPERTY =
        "regelsuche.byteIdentity.include";
    private static final String CONFIGURATION_MESSAGE =
        "The standalone evidence contract is configured by its Gradle task";

    @Test
    void writesCanonicalIndexAndResolutionReceipts() throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        PluginArtifactResolver resolver = new PluginArtifactResolver();
        var resolved = resolver.resolve(index, ResolutionRequest.latestCompatible(
            "reference-latest-compatible",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.5.0",
            "1",
            List.of("transformations")));
        var unresolved = resolver.resolve(index, ResolutionRequest.exact(
            "reference-incompatible-exact",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.1.0",
            "1.5.0",
            "1",
            List.of()));

        Path output = Path.of("build", "reports", "plugin-artifact-index");
        Files.createDirectories(output);
        write(output.resolve("index.json"), index.toCanonicalJson());
        write(output.resolve("resolved.json"), resolved.toCanonicalJson());
        write(output.resolve("unresolved.json"), unresolved.toCanonicalJson());

        assertEquals(index.toCanonicalJson(),
            Files.readString(output.resolve("index.json")));
        assertEquals(resolved.toCanonicalJson(),
            Files.readString(output.resolve("resolved.json")));
        assertEquals(unresolved.toCanonicalJson(),
            Files.readString(output.resolve("unresolved.json")));
    }

    @Test
    void configuredEvidenceDirectoriesAreByteIdentical() throws Exception {
        String left = System.getProperty(LEFT_PROPERTY, "");
        String right = System.getProperty(RIGHT_PROPERTY, "");
        String include = System.getProperty(INCLUDE_PROPERTY, "");

        assumeFalse(left.isBlank(), CONFIGURATION_MESSAGE);
        assumeFalse(right.isBlank(), CONFIGURATION_MESSAGE);
        assumeFalse(include.isBlank(), CONFIGURATION_MESSAGE);

        assertByteIdentical(
            Path.of(left).toAbsolutePath().normalize(),
            Path.of(right).toAbsolutePath().normalize(),
            include
        );
    }

    @Test
    void byteIdentityComparisonCoversNestedAndDifferentEvidence(
            @TempDir Path temporary) throws Exception {
        Path left = temporary.resolve("left");
        Path right = temporary.resolve("right");
        Files.createDirectories(left.resolve("nested"));
        Files.createDirectories(right.resolve("nested"));
        write(left.resolve("root.json"), "{\"value\":1}\n");
        write(right.resolve("root.json"), "{\"value\":1}\n");
        write(left.resolve("nested/evidence.json"), "[]\n");
        write(right.resolve("nested/evidence.json"), "[]\n");
        write(left.resolve("ignored.txt"), "left");
        write(right.resolve("ignored.txt"), "right");

        assertDoesNotThrow(() -> assertByteIdentical(left, right, "*.json"));

        write(left.resolve("missing.json"), "missing\n");
        write(right.resolve("extra.json"), "extra\n");
        write(right.resolve("nested/evidence.json"), "[1]\n");
        AssertionError difference = assertThrows(
            AssertionError.class,
            () -> assertByteIdentical(left, right, "*.json")
        );

        assertTrue(difference.getMessage().contains("missing.json"));
        assertTrue(difference.getMessage().contains("extra.json"));
        assertTrue(difference.getMessage().contains("nested/evidence.json"));
        assertTrue(difference.getMessage().matches(
            "(?s).*[0-9a-f]{64} != [0-9a-f]{64}.*"));
    }

    private static void assertByteIdentical(
            Path leftRoot, Path rightRoot, String pattern) throws IOException {
        Map<String, byte[]> left = collect(leftRoot, pattern);
        Map<String, byte[]> right = collect(rightRoot, pattern);
        Set<String> missing = new TreeSet<>(left.keySet());
        missing.removeAll(right.keySet());
        Set<String> extra = new TreeSet<>(right.keySet());
        extra.removeAll(left.keySet());
        List<Executable> checks = new ArrayList<>();

        checks.add(() -> assertTrue(missing.isEmpty(),
            () -> "missing=" + String.join(", ", missing)));
        checks.add(() -> assertTrue(extra.isEmpty(),
            () -> "extra=" + String.join(", ", extra)));
        left.keySet().stream()
            .filter(right::containsKey)
            .forEach(name -> checks.add(() -> assertArrayEquals(
                left.get(name),
                right.get(name),
                () -> name + " (" + sha256(left.get(name))
                    + " != " + sha256(right.get(name)) + ")"
            )));

        assertAll("directory evidence differs", checks);
        System.out.println("byte-identical=" + left.size() + " files; left="
            + leftRoot + "; right=" + rightRoot + "; pattern=" + pattern);
    }

    private static Map<String, byte[]> collect(Path root, String pattern)
            throws IOException {
        assertTrue(Files.isDirectory(root), () -> "missing directory: " + root);
        assertFalse(pattern == null || pattern.isBlank(),
            "include pattern must not be blank");

        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + pattern);
        List<Path> matchingPaths;
        try (var paths = Files.walk(root)) {
            matchingPaths = paths
                .filter(Files::isRegularFile)
                .filter(path -> matches(root, matcher, path))
                .sorted()
                .toList();
        }
        assertFalse(matchingPaths.isEmpty(),
            () -> "no files matching '" + pattern + "' under " + root);

        Map<String, byte[]> files = new TreeMap<>();
        for (Path path : matchingPaths) {
            Path relative = root.relativize(path);
            files.put(relative.toString().replace('\\', '/'),
                Files.readAllBytes(path));
        }
        return files;
    }

    private static boolean matches(
            Path root, PathMatcher matcher, Path path) {
        Path relative = root.relativize(path);
        return matcher.matches(relative)
            || matcher.matches(relative.getFileName());
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
