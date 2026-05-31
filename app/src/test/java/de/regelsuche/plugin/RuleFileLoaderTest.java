package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleFileLoaderTest {
    @Test
    void loadsRuleFilesIntoTypedRegistries(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("binomial.regelsuche");
        Files.writeString(file, """
            rule dsl_difference_of_squares:
              pattern: A^2 - B^2
              replace: (A - B) * (A + B)
              direction: forward
              tags:
                - factorization
                - binomial
              explanation: "Erkennt die Differenz zweier Quadrate."
            
            macro expand_square:
              input: (A + B)^2
              output: A^2 + 2*A*B + B^2
              tags:
                - macro
            """);

        RuleRegistry ruleRegistry = new RuleRegistry();
        MacroRegistry macroRegistry = new MacroRegistry();
        PluginRuntime.RuleFileLoadResult result = new PluginRuntime.RuleFileLoader().load(file, ruleRegistry, macroRegistry);

        assertEquals(2, result.loadedEntries());
        assertTrue(ruleRegistry.registrations().stream().anyMatch(rule -> rule.id().equals("dsl_difference_of_squares")));
        assertTrue(macroRegistry.registrations().stream().anyMatch(macro -> macro.id().equals("expand_square")));
    }

    @Test
    void invalidRuleFilesProduceReadableDiagnostics(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("invalid.regelsuche");
        Files.write(file, List.of(
            "rule broken_rule:",
            "  replace: A + B"
        ));

        RuleFileParseException exception = assertThrows(RuleFileParseException.class,
            () -> new PluginRuntime.RuleFileLoader().load(file, new RuleRegistry(), new MacroRegistry()));

        assertTrue(exception.getMessage().contains("Missing required property 'pattern'"));
        assertTrue(exception.getMessage().contains("invalid.regelsuche:1"));
    }

    @Test
    void bundledExampleRulePackageLoadsSuccessfully() {
        Path file = locateRepoRoot().resolve("examples/binomial-formulas.regelsuche");

        RuleRegistry ruleRegistry = new RuleRegistry();
        MacroRegistry macroRegistry = new MacroRegistry();
        PluginRuntime.RuleFileLoadResult result = new PluginRuntime.RuleFileLoader().load(file, ruleRegistry, macroRegistry);

        assertEquals(5, result.loadedEntries());
        assertTrue(ruleRegistry.registrations().stream().anyMatch(rule -> rule.id().equals("dsl_difference_of_squares")));
        assertTrue(macroRegistry.registrations().stream().anyMatch(macro -> macro.id().equals("expand_square")));
    }

    private Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate repository root");
        }
        return current;
    }
}
