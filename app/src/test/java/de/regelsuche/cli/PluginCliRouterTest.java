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

    @Test
    void rulesListShowsProfileFilteredRuleAndMacroStatus(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("profiles.regelsuche"), """
            rule keep_rule:
              pattern: A + 0
              replace: A
              tags:
                - algebra

            rule blocked_rule:
              pattern: A * 1
              replace: A
              tags:
                - algebra

            macro blocked_macro:
              input: A / 1
              output: A
              tags:
                - algebra

            profile school_algebra:
              enable_tags:
                - algebra
              blacklist:
                - blocked_rule
                - blocked_macro
            """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "list", "--dir", rulesDir.toString(), "--profile", "school_algebra"});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("rule keep_rule"), output::toString);
        assertTrue(output.toString().contains("rule blocked_rule"), output::toString);
        assertTrue(output.toString().contains("blocked_rule") && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("macro blocked_macro") && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("search-strategy binomial-guided-search")
            && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("heuristic binomial-pattern-heuristic")
            && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("cost-function binomial-cost-delta")
            && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("renderer binomial-text-renderer")
            && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("explanation binomial-explanations")
            && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("parser-extension unicode-square-parser")
            && output.toString().contains("disabled"), output::toString);
        assertTrue(output.toString().contains("example binomial-examples")
            && output.toString().contains("disabled"), output::toString);
    }

    @Test
    void rulesProfilesMarksActiveProfileAndShowsLists(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("profiles.regelsuche"), """
            profile school_algebra:
              enable_tags:
                - algebra
              whitelist:
                - keep_rule
              blacklist:
                - blocked_rule
            """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "profiles", "--dir", rulesDir.toString(), "--profile", "school_algebra"});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("profile school_algebra [active]"), output::toString);
        assertTrue(output.toString().contains("whitelist: keep_rule"), output::toString);
        assertTrue(output.toString().contains("blacklist: blocked_rule"), output::toString);
    }
}
