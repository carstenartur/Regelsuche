package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileProofArtifactRepositoryBundleTest {

    @Test
    void storeJobArtifactWritesUnderJobSubdirectory(@TempDir Path tmp) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(tmp);
        Path stored = repo.storeJobArtifact("job-1", "proof.lean", "theorem t : ...");
        assertTrue(stored.toString().endsWith("job-1/proof.lean")
            || stored.toString().endsWith("job-1\\proof.lean"));
        assertEquals("theorem t : ...", Files.readString(stored));
    }

    @Test
    void listAndReadRoundTripPerJobBundle(@TempDir Path tmp) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(tmp);
        repo.storeJobArtifact("abc", "proof.smt2", "(declare-const x Int)");
        repo.storeJobArtifact("abc", "stdout.txt", "sat");
        repo.storeJobArtifact("abc", "metadata.json", "{\"jobId\":\"abc\"}");

        List<String> names = repo.listJobArtifacts("abc");
        assertEquals(List.of("metadata.json", "proof.smt2", "stdout.txt"), names);
        assertEquals("(declare-const x Int)", repo.readJobArtifact("abc", "proof.smt2").orElseThrow());
        assertEquals("sat", repo.readJobArtifact("abc", "stdout.txt").orElseThrow());
        assertTrue(repo.jobArtifactPath("abc", "metadata.json").isPresent());
    }

    @Test
    void perJobBundlesAreIsolated(@TempDir Path tmp) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(tmp);
        repo.storeJobArtifact("a", "proof.lean", "A");
        repo.storeJobArtifact("b", "proof.lean", "B");
        assertEquals("A", repo.readJobArtifact("a", "proof.lean").orElseThrow());
        assertEquals("B", repo.readJobArtifact("b", "proof.lean").orElseThrow());
        assertEquals(List.of("proof.lean"), repo.listJobArtifacts("a"));
        assertEquals(List.of("proof.lean"), repo.listJobArtifacts("b"));
    }

    @Test
    void traversalIsRejectedForJobIdAndName(@TempDir Path tmp) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(tmp);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> repo.storeJobArtifact("../escape", "proof.lean", "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> repo.storeJobArtifact("job", "../escape.lean", "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> repo.storeJobArtifact("job", "subdir/file", "x"));
    }

    @Test
    void missingArtifactsReturnEmpty(@TempDir Path tmp) throws IOException {
        JsonFileProofArtifactRepository repo = new JsonFileProofArtifactRepository(tmp);
        assertFalse(repo.readJobArtifact("nope", "proof.lean").isPresent());
        assertEquals(List.of(), repo.listJobArtifacts("nope"));
    }
}
