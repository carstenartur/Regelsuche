package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyCodeReferencesTest {
    private static final String MARKER =
        "<!-- strategy-code: src/Example.java | final class Example { -->";

    @Test
    void strategyIndexUsesCurrentCodeLocations() throws IOException {
        StrategyCodeReferences.refresh(StrategyCodeReferences.repositoryRoot(Path.of("")), false);
    }

    @Test
    void checksAndRegeneratesTheLinkWhenTheDeclarationMoves(@TempDir Path root) throws IOException {
        var index = write(root, "docs/research/strategies.md", "# Strategy\n\n" + MARKER + "\n");
        var source = write(root, "src/Example.java", "package test;\nfinal class Example {\n}\n");
        assertThrows(IllegalStateException.class, () -> StrategyCodeReferences.refresh(root, false));
        StrategyCodeReferences.refresh(root, true);
        assertEquals("# Strategy\n\n- [`Example.java`](../../src/Example.java#L2) " + MARKER + "\n",
            Files.readString(index));

        Files.writeString(source, "\n\nfinal class Example {\n}\n");
        assertThrows(IllegalStateException.class, () -> StrategyCodeReferences.refresh(root, false));
        StrategyCodeReferences.refresh(root, true);
        StrategyCodeReferences.refresh(root, false);
        assertTrue(Files.readString(index).contains("Example.java#L3"));
    }

    @Test
    void rejectsAmbiguousAndMissingDeclarationsWithoutChangingTheIndex(@TempDir Path root) throws IOException {
        var index = write(root, "docs/research/strategies.md", MARKER + "\n");
        var source = write(root, "src/Example.java", "final class Example {\nfinal class Example {\n");
        assertTrue(assertThrows(IllegalStateException.class, () -> StrategyCodeReferences.refresh(root, true))
            .getMessage().contains("2 matches"));
        Files.writeString(source, "final class Renamed {\n}\n");
        assertTrue(assertThrows(IllegalStateException.class, () -> StrategyCodeReferences.refresh(root, true))
            .getMessage().contains("0 matches"));
        assertEquals(MARKER + "\n", Files.readString(index));
    }

    @Test
    void keepsProseAndOtherLinksOnMalformedMarkerLines(@TempDir Path root) throws IOException {
        var paragraph = "- [`Example.java`](../../src/Example.java#L1) und [Protokoll](report.md) " + MARKER + "\n";
        var index = write(root, "docs/research/strategies.md", paragraph);
        write(root, "src/Example.java", "final class Example {\n}\n");
        assertThrows(IllegalStateException.class, () -> StrategyCodeReferences.refresh(root, true));
        assertEquals(paragraph, Files.readString(index));
    }

    private static Path write(Path root, String relativeName, String text) throws IOException {
        var file = root.resolve(relativeName);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }
}
