package de.regelsuche.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginCliRouterTest {
    @Test
    void pluginsListShowsLoadedPlugins(@TempDir Path tempDir) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"plugins", "list", "--dir", tempDir.resolve("plugins").toString()});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("binomial-formulas"), output::toString);
    }

    @Test
    void rulesValidateReportsErrors(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("broken.regelsuche");
        Files.writeString(file, """
            rule broken:
              replace: A + B
            """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "validate", file.toString()});

        assertEquals(2, exit);
        assertTrue(output.toString().contains("Missing required property 'pattern'"), output::toString);
    }

    @Test
    void rulesConflictsReportsCompetingPatterns(@TempDir Path tempDir) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "conflicts", "--dir", tempDir.resolve("rules").toString()});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("CONFLICT"), output::toString);
        assertTrue(output.toString().contains("binomial_square_forward"), output::toString);
    }
}
