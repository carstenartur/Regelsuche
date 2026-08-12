package de.regelsuche.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ByteIdenticalDirectoriesVerifierTest {

    @TempDir
    Path temporary;

    @Test
    void acceptsRootAndNestedFilesWithIdenticalBytes() throws Exception {
        Path left = temporary.resolve("left");
        Path right = temporary.resolve("right");
        Files.createDirectories(left.resolve("nested"));
        Files.createDirectories(right.resolve("nested"));
        Files.writeString(left.resolve("root.json"), "{\"value\":1}\n");
        Files.writeString(right.resolve("root.json"), "{\"value\":1}\n");
        Files.writeString(left.resolve("nested/evidence.json"), "[]\n");
        Files.writeString(right.resolve("nested/evidence.json"), "[]\n");
        Files.writeString(left.resolve("ignored.txt"), "left");
        Files.writeString(right.resolve("ignored.txt"), "right");

        ByteIdenticalDirectoriesVerifier.Comparison comparison =
            ByteIdenticalDirectoriesVerifier.compare(left, right, "*.json");

        assertTrue(comparison.identical());
        assertEquals(2, comparison.comparedFiles());
        assertTrue(comparison.describe(left, right, "*.json")
            .contains("byte-identical=2 files"));
    }

    @Test
    void reportsMissingExtraAndChangedFilesWithHashes() throws Exception {
        Path left = temporary.resolve("left-different");
        Path right = temporary.resolve("right-different");
        Files.createDirectories(left);
        Files.createDirectories(right);
        Files.writeString(left.resolve("missing.json"), "missing\n");
        Files.writeString(right.resolve("extra.json"), "extra\n");
        Files.writeString(left.resolve("changed.json"), "left\n");
        Files.writeString(right.resolve("changed.json"), "right\n");

        ByteIdenticalDirectoriesVerifier.Comparison comparison =
            ByteIdenticalDirectoriesVerifier.compare(left, right, "*.json");

        assertFalse(comparison.identical());
        assertEquals(java.util.List.of("missing.json"), comparison.missing());
        assertEquals(java.util.List.of("extra.json"), comparison.extra());
        assertEquals(1, comparison.changed().size());
        String description = comparison.describe(left, right, "*.json");
        assertTrue(description.contains("missing=missing.json"));
        assertTrue(description.contains("extra=extra.json"));
        assertTrue(description.contains("changed=changed.json"));
        assertTrue(description.matches("(?s).*\\([0-9a-f]{64} != [0-9a-f]{64}\\).*"));
    }

    @Test
    void rejectsMissingDirectoriesAndEmptySelections() throws Exception {
        Path existing = temporary.resolve("existing");
        Files.createDirectories(existing);

        IllegalArgumentException missing = assertThrows(
            IllegalArgumentException.class,
            () -> ByteIdenticalDirectoriesVerifier.compare(
                temporary.resolve("absent"), existing, "*.json"));
        assertTrue(missing.getMessage().contains("missing directory"));

        IllegalArgumentException empty = assertThrows(
            IllegalArgumentException.class,
            () -> ByteIdenticalDirectoriesVerifier.compare(
                existing, existing, "*.json"));
        assertTrue(empty.getMessage().contains("no files matching"));
    }
}
