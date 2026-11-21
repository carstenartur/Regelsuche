package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialComparisonArchiveTest {
    @Test void nonzeroTarExitFailsWithoutCopyingAnArchive(@TempDir Path root) {
        Path archive = root.resolve("evidence.tgz");
        assertThrows(AssertionError.class, () -> PolynomialComparisonContainerIT.copyArchive(2, archive,
            () -> Files.writeString(archive, "partial output")));
        assertFalse(Files.exists(archive));
    }

    @Test void completedCopyMustProduceARealNonemptyArtifact(@TempDir Path root) throws Throwable {
        Path archive = root.resolve("evidence.tgz");
        assertThrows(AssertionError.class, () -> PolynomialComparisonContainerIT.copyArchive(0, archive, () -> {}));
        assertThrows(AssertionError.class, () -> PolynomialComparisonContainerIT.copyArchive(0, archive,
            () -> Files.createFile(archive)));
        PolynomialComparisonContainerIT.copyArchive(0, archive, () -> Files.writeString(archive, "retained output"));
        assertEquals("retained output", Files.readString(archive));
    }

    @Test void archiveFailureIsSuppressedOnTheOriginalPilotFailure() {
        var pilotFailure = new AssertionError("independent pilot replay failed");
        var archiveFailure = new IOException("container archive transfer failed");
        var actual = assertThrows(AssertionError.class, () -> PolynomialComparisonContainerIT.runWithArchive(
            () -> { throw pilotFailure; }, () -> { throw archiveFailure; }));
        assertSame(pilotFailure, actual);
        assertArrayEquals(new Throwable[] {archiveFailure}, actual.getSuppressed());
    }
}
