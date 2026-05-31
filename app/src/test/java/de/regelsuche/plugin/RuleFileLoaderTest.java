package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void parsesProfileEntriesWithEnableAndDisableTags(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("profiles.regelsuche");
        Files.writeString(file, """
            profile school_algebra:
              enable_tags:
                - binomial
                - factorization
              disable_tags:
                - complex_analysis
            """);

        PluginRuntime.RuleFileLoadResult result =
            new PluginRuntime.RuleFileLoader().load(file, new RuleRegistry(), new MacroRegistry());

        assertEquals(1, result.profiles().size());
        RuleProfile profile = result.profiles().get(0);
        assertEquals("school_algebra", profile.id());
        assertTrue(profile.includes(List.of("binomial")));
        assertFalse(profile.includes(List.of("complex_analysis")));
        assertFalse(profile.includes(List.of("trigonometry")));
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

        assertEquals(6, result.loadedEntries());
        assertTrue(ruleRegistry.registrations().stream().anyMatch(rule -> rule.id().equals("dsl_difference_of_squares")));
        assertTrue(macroRegistry.registrations().stream().anyMatch(macro -> macro.id().equals("expand_square")));
        assertTrue(result.profiles().stream().anyMatch(profile -> profile.id().equals("school_algebra")));
    }

    @Test
    void bundledFactorizationPackageLoadsSuccessfully() {
        Path file = locateRepoRoot().resolve("examples/factorization.regelsuche");

        RuleRegistry ruleRegistry = new RuleRegistry();
        MacroRegistry macroRegistry = new MacroRegistry();
        PluginRuntime.RuleFileLoadResult result = new PluginRuntime.RuleFileLoader().load(file, ruleRegistry, macroRegistry);

        assertTrue(result.diagnostics().isEmpty());
        // direction: both expands into .forward/.backward variants for both rules
        assertTrue(ruleRegistry.registrations().stream()
            .anyMatch(rule -> rule.id().equals("extract_common_factor.forward")));
        assertTrue(ruleRegistry.registrations().stream()
            .anyMatch(rule -> rule.id().equals("difference_of_squares.backward")));
        assertTrue(result.profiles().stream().anyMatch(profile -> profile.id().equals("factorization_basics")));
    }

    @Test
    void bundledPowerLawsPackageLoadsSuccessfully() {
        Path file = locateRepoRoot().resolve("examples/power-laws.regelsuche");

        RuleRegistry ruleRegistry = new RuleRegistry();
        PluginRuntime.RuleFileLoadResult result =
            new PluginRuntime.RuleFileLoader().load(file, ruleRegistry, new MacroRegistry());

        assertTrue(result.diagnostics().isEmpty());
        assertTrue(ruleRegistry.registrations().stream().anyMatch(rule -> rule.id().equals("product_of_powers")));
        assertTrue(ruleRegistry.registrations().stream().anyMatch(rule -> rule.id().equals("power_of_power")));
        assertTrue(result.profiles().stream().anyMatch(profile -> profile.id().equals("power_laws")));
    }

    @Test
    void bundledTrigonometryPackageLoadsSuccessfully() {
        Path file = locateRepoRoot().resolve("examples/trig-identities.regelsuche");

        RuleRegistry ruleRegistry = new RuleRegistry();
        PluginRuntime.RuleFileLoadResult result =
            new PluginRuntime.RuleFileLoader().load(file, ruleRegistry, new MacroRegistry());

        assertTrue(result.diagnostics().isEmpty());
        assertTrue(ruleRegistry.registrations().stream().anyMatch(rule -> rule.id().equals("pythagorean_identity")));
        assertTrue(result.profiles().stream().anyMatch(profile -> profile.id().equals("school_trigonometry")));
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
