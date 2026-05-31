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
