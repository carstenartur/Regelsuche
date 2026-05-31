package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.notify.ConsoleNotifier;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.Transformation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void discoversExamplePluginAndExtendsRegistries(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("binomial-formulas")));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("binomial_difference_of_squares")));
            assertTrue(runtime.transformationRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("binomial_square_forward")));
            assertTrue(runtime.astVisitorRegistry().registrations().stream()
                .anyMatch(visitor -> visitor.id().equals("binomial-pattern-visitor")));
        }
    }

    @Test
    void pluginTransformationsAppearInSearchGraphAndVisitorsProduceDiagnostics(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            PluginAwareAstRewriteTransformationEngine engine = runtime.createTransformationEngine();

            List<Transformation> transformations = engine.transform("(a + b)^2");
            assertTrue(transformations.stream().anyMatch(transformation ->
                transformation.rule().equals("binomial_square_forward")
                    && canonicalizer.canonicalize(transformation.transformedExpression())
                    .equals(canonicalizer.canonicalize("a^2 + 2*a*b + b^2"))
            ));
            assertTrue(engine.lastVisitorDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("binomial square candidate")));

            InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
            TransformationSearchService service = new TransformationSearchService(
                engine,
                store,
                new SearchHeuristic(4, 40, 1),
                new ConsoleNotifier()
            );
            service.submit(new InputRequest(InputType.TERM, "(a + b)^2")).join();
            GraphSnapshot snapshot = service.getGraphSnapshot();
            assertTrue(snapshot.edges().stream()
                .anyMatch(edge -> edge.transformationRule().equals("binomial_square_forward")));
            service.shutdown();
        }
    }

    @Test
    void enabledMacrosAppearAsTransformationEdgesInSearch(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.macroTransformations().stream()
                .anyMatch(transformation -> transformation.id().equals("macro.expand_square")));

            List<Transformation> transformations = runtime.createTransformationEngine().transform("(a + b)^2");
            assertTrue(transformations.stream().anyMatch(transformation ->
                transformation.rule().equals("macro.expand_square")
                    && canonicalizer.canonicalize(transformation.transformedExpression())
                    .equals(canonicalizer.canonicalize("a^2 + 2*a*b + b^2"))
            ));
        }
    }

    @Test
    void disabledMacrosDoNotProduceTransformationEdges(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of("expand_square")
        ))) {
            assertFalse(runtime.macroTransformations().stream()
                .anyMatch(transformation -> transformation.id().equals("macro.expand_square")));

            List<Transformation> transformations = runtime.createTransformationEngine().transform("(a + b)^2");
            assertFalse(transformations.stream().anyMatch(transformation ->
                transformation.rule().equals("macro.expand_square")));
        }
    }

    @Test
    void competingMacroAndTransformationSourcePatternsAreReportedAsConflicts(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.conflicts().stream().anyMatch(conflict ->
                conflict.ruleIds().contains("binomial_square_forward")
                    && conflict.ruleIds().contains("macro.expand_square")));
            assertTrue(runtime.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.source().equals("rule-conflict")
                    && diagnostic.message().contains("binomial_square_forward")));
        }
    }

    @Test
    void disabledRulesAreNotApplied(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of("binomial_square_forward")
        ))) {
            List<Transformation> transformations = runtime.createTransformationEngine().transform("(a + b)^2");

            assertFalse(transformations.stream().anyMatch(transformation ->
                transformation.rule().equals("binomial_square_forward")));
        }
    }

    @Test
    void activeProfileDisablesRulesAndMacrosOutsideEnabledTags(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("algebra.regelsuche"), """
            rule keep_factorization:
              pattern: A^2 - B^2
              replace: (A - B) * (A + B)
              direction: forward
              tags:
                - factorization

            rule drop_complex:
              pattern: A + 0
              replace: A
              direction: forward
              tags:
                - complex_analysis

            macro drop_complex_macro:
              input: A * 1
              output: A
              tags:
                - complex_analysis

            profile school_algebra:
              enable_tags:
                - factorization
              disable_tags:
                - complex_analysis
            """);

        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            false,
            Set.of(),
            Set.of(),
            "school_algebra"
        ))) {
            assertTrue(runtime.profiles().stream().anyMatch(profile -> profile.id().equals("school_algebra")));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("keep_factorization") && rule.enabled()));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("drop_complex") && !rule.enabled()));
            assertFalse(runtime.macroTransformations().stream()
                .anyMatch(transformation -> transformation.id().equals("macro.drop_complex_macro")));
            assertTrue(runtime.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("Activation profile 'school_algebra' applied")));
        }
    }

    @Test
    void unknownActiveProfileIsReportedAsDiagnostic(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            false,
            Set.of(),
            Set.of(),
            "does_not_exist"
        ))) {
            assertTrue(runtime.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("Unknown activation profile 'does_not_exist'")));
        }
    }

    @Test
    void invalidRuleFilesAreReportedWithoutBreakingPluginStartup(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("broken.regelsuche"), """
            rule broken:
              replace: A + B
            """);

        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            true,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("binomial-formulas")));
            assertTrue(runtime.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("Missing required property 'pattern'")));
        }
    }
}
