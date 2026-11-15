package de.regelsuche.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.plugin.RuleFileParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertTrue(output.toString().contains("name=\"Binomial Formulas\""), output::toString);
        assertTrue(output.toString().contains("capabilities=[cost-functions"), output::toString);
        assertTrue(output.toString().contains("compatibility=not-checked"), output::toString);
        assertTrue(output.toString().contains("compatibilityIssues=[Dependency version not checked: algebra-core"), output::toString);
        assertTrue(output.toString().contains("dependencies=[algebra-core >=1.0.0 [version-not-checked]]"), output::toString);
        assertTrue(output.toString().contains("provenance=https://github.com/carstenartur/Regelsuche"), output::toString);
        assertTrue(output.toString().contains("signaturePresent=false"), output::toString);
        assertTrue(output.toString().contains("signatureVerified=false"), output::toString);
        assertTrue(output.toString().contains("trustedSource=true"), output::toString);
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
    void rulesImportCopiesRuleFileToTargetDir(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("algebra.regelsuche"), """
            rule additive_identity:
              pattern: A + 0
              replace: A
            """);
        Path targetDir = tempDir.resolve("rules");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "import", sourceDir.toString(), "--into", targetDir.toString()});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("Imported"), output::toString);
        assertTrue(Files.exists(targetDir.resolve("algebra.regelsuche")));
    }

    @Test
    void rulesImportSingleFileToTargetDir(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("trig.rules");
        Files.writeString(sourceFile, """
            rule sin_zero:
              pattern: sin(0)
              replace: 0
            """);
        Path targetDir = tempDir.resolve("rules");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "import", sourceFile.toString(), "--into", targetDir.toString()});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("Imported"), output::toString);
        assertTrue(Files.exists(targetDir.resolve("trig.rules")));
    }

    @Test
    void rulesImportSingleFileRejectsUnsupportedExtension(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("notes.txt");
        Files.writeString(sourceFile, "not a rule package");
        Path targetDir = tempDir.resolve("rules");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "import", sourceFile.toString(), "--into", targetDir.toString()});

        assertEquals(1, exit);
        assertTrue(output.toString().contains("Unsupported rule file"), output::toString);
        assertFalse(Files.exists(targetDir.resolve("notes.txt")));
    }

    @Test
    void rulesExportWritesActiveRulesToFile(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("custom.regelsuche"), """
            rule multiply_by_one:
              pattern: A * 1
              replace: A
              tags:
                - algebra
            """);
        Path outputDir = tempDir.resolve("exports");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );

        int exit = router.run(new String[]{"rules", "export",
            "--dir", rulesDir.toString(),
            "--out", outputDir.toString()});

        assertEquals(0, exit);
        assertTrue(output.toString().contains("Exported"), output::toString);
        assertTrue(Files.exists(outputDir.resolve("exported-rules.regelsuche")));
        String exported = Files.readString(outputDir.resolve("exported-rules.regelsuche"));
        assertTrue(exported.contains("multiply_by_one"), () -> "exported content: " + exported);
    }

    @Test
    void portableFactorizationExampleExportsAndReloadsOnlyItsOwnRules(@TempDir Path tempDir) throws Exception {
        Path examples = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."), "examples");
        Path rules = tempDir.resolve("rules");
        Path exports = tempDir.resolve("exports");
        Path restored = tempDir.resolve("restored");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );
        for (String example : List.of("factorization.regelsuche", "factorization-export-profile.rules")) {
            assertEquals(0, router.run(new String[]{"rules", "import", examples.resolve(example).toString(),
                "--into", rules.toString()}), output::toString);
        }
        assertEquals(0, router.run(new String[]{"rules", "export", "--dir", rules.toString(),
            "--profile", "factorization_export", "--out", exports.toString()}), output::toString);
        Path exported = exports.resolve("exported-rules-factorization_export.regelsuche");
        var parsed = new RuleFileParser().parse(exported);
        assertFalse(parsed.hasErrors(), () -> parsed.diagnostics().toString());
        var ids = parsed.entries().stream().map(RuleFileParser.Entry::id).sorted().toList();
        assertEquals(List.of("difference_of_squares.backward", "difference_of_squares.forward",
            "extract_common_factor.backward", "extract_common_factor.forward"), ids);

        assertEquals(0, router.run(new String[]{"rules", "import", exported.toString(),
            "--into", restored.toString()}), output::toString);
        Path imported = restored.resolve(exported.getFileName());
        assertEquals(-1L, Files.mismatch(exported, imported));
        output.reset();
        assertEquals(0, router.run(new String[]{"rules", "list", "--dir", restored.toString()}), output::toString);
        String report = output.toString();
        for (String id : ids) {
            assertTrue(report.contains("rule " + id + " (" + imported + ", tier=plugin, enabled)"), () -> report);
        }
        assertFalse(report.contains("Duplicate rule id"), () -> report);

        output.reset();
        assertEquals(0, router.run(new String[]{"rules", "debug", "2*x+3*x", "--dir", restored.toString()}),
            output::toString);
        assertTrue(output.toString().lines().map(String::stripLeading).anyMatch(line ->
            line.matches("extract_common_factor\\.forward\\s+APPLIED\\s+APPLIED.*")), output::toString);
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
