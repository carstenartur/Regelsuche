package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenQualifiedReleaseReadinessAdmissionTest {
    @TempDir Path root;

    @Test
    void missingCurrentProducerCannotFallBackToGradleEvidence() throws Exception {
        Path legacy = root.resolve("app/build/reports/hidden-rule-pilot/report.json");
        write(legacy, "retained Gradle evidence must remain untouched");
        IOException failure = assertThrows(IOException.class, () -> generate());
        assertTrue(failure.getMessage().contains("current Maven hidden-rule report"));
        assertFalse(Files.exists(output()));
        assertEquals("retained Gradle evidence must remain untouched", Files.readString(legacy));
    }

    @Test
    void emptyCurrentReportIsNotProduction() throws Exception {
        write(input(), "");
        assertThrows(IOException.class, () -> generate());
        assertFalse(Files.exists(output()));
    }

    @Test
    void priorQualifiedOutputCannotBeReusedOrOverwritten() throws Exception {
        write(input(), "not consumed before stale-output admission");
        Path retained = output().resolve("release-readiness-run.json");
        write(retained, "prior verified run");
        IOException failure = assertThrows(IOException.class, () -> generate());
        assertTrue(failure.getMessage().contains("already exists"));
        assertEquals("prior verified run", Files.readString(retained));
    }

    @Test
    void linkedProducerCannotSelectOutsideEvidence() throws Exception {
        Path outside = root.resolve("outside.json");
        write(outside, "foreign input");
        Files.createDirectories(input().getParent());
        Files.createSymbolicLink(input(), outside);
        assertThrows(IOException.class, () -> generate());
        assertFalse(Files.exists(output()));
        assertEquals("foreign input", Files.readString(outside));
    }

    @Test
    void linkedOutputAncestorCannotRedirectProduction() throws Exception {
        write(input(), "not consumed before output admission");
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path target = root.resolve("regelsuche-quality-aggregate/target");
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, outside);
        assertThrows(IOException.class, () -> generate());
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    private void generate() throws IOException {
        MavenQualifiedReleaseReadinessIT.generateAndVerify(root);
    }

    private Path input() { return root.resolve(MavenQualifiedReleaseReadinessIT.INPUT); }
    private Path output() { return root.resolve(MavenQualifiedReleaseReadinessIT.OUTPUT); }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
