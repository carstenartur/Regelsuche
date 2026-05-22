package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileProofArtifactRepositoryTest {

    @Test
    void storeAndReadArtifact(@TempDir Path tempDir) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(
            tempDir.resolve("proofs"));
        Path stored = repo.store("job1.lean", "theorem t : 1 + 0 = 1 := by rfl");
        assertTrue(Files.isRegularFile(stored));
        assertEquals("theorem t : 1 + 0 = 1 := by rfl",
            Files.readString(stored, StandardCharsets.UTF_8));

        Optional<String> read = repo.read("job1.lean");
        assertTrue(read.isPresent());
        assertEquals("theorem t : 1 + 0 = 1 := by rfl", read.get());
    }

    @Test
    void listArtifactIdsReturnsAllStoredFiles(@TempDir Path tempDir) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(
            tempDir.resolve("proofs"));
        repo.store("a.lean", "a");
        repo.store("b.smt2", "(check-sat)");

        var ids = repo.listArtifactIds();
        assertTrue(ids.contains("a.lean"));
        assertTrue(ids.contains("b.smt2"));
    }

    @Test
    void deleteRemovesArtifact(@TempDir Path tempDir) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(
            tempDir.resolve("proofs"));
        repo.store("delete-me.lean", "x");
        repo.delete("delete-me.lean");
        assertFalse(repo.read("delete-me.lean").isPresent());
        assertFalse(repo.pathOf("delete-me.lean").isPresent());
    }

    @Test
    void rejectsPathTraversalIds(@TempDir Path tempDir) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(
            tempDir.resolve("proofs"));
        assertThrows(IllegalArgumentException.class, () -> repo.store("../evil.lean", "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.store("sub/dir.lean", "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.store("", "x"));
    }

    @Test
    void missingArtifactReturnsEmpty(@TempDir Path tempDir) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(
            tempDir.resolve("proofs"));
        assertFalse(repo.read("missing.lean").isPresent());
        assertFalse(repo.pathOf("missing.lean").isPresent());
    }
}
