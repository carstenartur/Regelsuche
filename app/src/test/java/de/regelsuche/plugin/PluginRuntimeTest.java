package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.notify.ConsoleNotifier;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.EnumSet;
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
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("algebra-core")));
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("factorization-pack")));
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("trigonometry-pack")));
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("rational-functions-pack")));
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("discovery-operators-pack")));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("binomial_difference_of_squares")));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("algebra_additive_identity")));
            assertTrue(runtime.transformationRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("factorization_common_factor")));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("trig_pythagorean_identity")));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("rational_fraction_addition")));
            assertTrue(runtime.transformationRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("binomial_square_forward")));
            assertTrue(runtime.astVisitorRegistry().registrations().stream()
                .anyMatch(visitor -> visitor.id().equals("binomial-pattern-visitor")));
            assertTrue(runtime.searchStrategyRegistry().registrations().stream()
                .anyMatch(strategy -> strategy.id().equals("discovery-operator-bias")));
            assertTrue(runtime.searchStrategyRegistry().registrations().stream()
                .anyMatch(strategy -> strategy.id().equals("binomial-guided-search")));
            assertTrue(runtime.heuristicRegistry().registrations().stream()
                .anyMatch(heuristic -> heuristic.id().equals("discovery-operator-heuristic")));
            assertTrue(runtime.heuristicRegistry().registrations().stream()
                .anyMatch(heuristic -> heuristic.id().equals("binomial-pattern-heuristic")));
            assertTrue(runtime.costFunctionRegistry().registrations().stream()
                .anyMatch(costFunction -> costFunction.id().equals("binomial-cost-delta")));
            assertTrue(runtime.rendererRegistry().registrations().stream()
                .anyMatch(renderer -> renderer.id().equals("binomial-text-renderer")));
            assertTrue(runtime.explanationRegistry().registrations().stream()
                .anyMatch(explanation -> explanation.id().equals("binomial-explanations")));
            assertTrue(runtime.parserExtensionRegistry().registrations().stream()
                .anyMatch(parserExtension -> parserExtension.id().equals("unicode-square-parser")));
            assertTrue(runtime.exampleRegistry().registrations().stream()
                .anyMatch(examples -> examples.id().equals("binomial-examples")));
            assertTrue(runtime.exampleRegistry().registrations().stream()
                .anyMatch(examples -> examples.id().equals("discovery-operators-examples")));
        }
    }

    @Test
    void loadedPluginMetadataContainsCompatibilityDependenciesAndTrust(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            PluginRuntime.LoadedPlugin binomial = runtime.loadedPlugins().stream()
                .filter(plugin -> plugin.id().equals("binomial-formulas"))
                .findFirst()
                .orElseThrow();
            assertEquals("compatible", binomial.compatibility());
            assertEquals("1", binomial.apiVersion());
            assertEquals(List.of(
                "cost-functions",
                "examples",
                "explanations",
                "heuristics",
                "macros",
                "parser-extensions",
                "renderers",
                "rules",
                "search-strategies",
                "transformations",
                "visitors"
            ), binomial.capabilities());
            assertTrue(binomial.dependencies().stream()
                .anyMatch(dependency -> dependency.pluginId().equals("algebra-core")));
            assertTrue(binomial.trustedSource());
            assertTrue(binomial.trustWarnings().isEmpty());
        }
    }

    @Test
    void newPluginExtensionRegistriesExposeUsableEnabledContributions(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            assertEquals(10, runtime.heuristicRegistry().enabledExtensions().stream()
                .filter(heuristic -> heuristic.id().equals("binomial-pattern-heuristic"))
                .findFirst()
                .orElseThrow()
                .score("(a + b)^2"));
            assertEquals("x^2", runtime.parserExtensionRegistry().enabledExtensions().stream()
                .filter(parserExtension -> parserExtension.id().equals("unicode-square-parser"))
                .findFirst()
                .orElseThrow()
                .normalize("x²"));
            assertTrue(runtime.explanationRegistry().enabledExtensions().stream()
                .filter(explanation -> explanation.id().equals("binomial-explanations"))
                .findFirst()
                .orElseThrow()
                .supportsRule("binomial_square_forward"));
            assertTrue(runtime.exampleRegistry().enabledExtensions().stream()
                .filter(examples -> examples.id().equals("binomial-examples"))
                .findFirst()
                .orElseThrow()
                .examples().stream().anyMatch(example -> example.input().equals("(a + b)^2")));
        }
    }

    @Test
    void configuredDisabledIdsAlsoDisableNewPluginExtensionContributions(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of("binomial-explanations")
        ))) {
            assertTrue(runtime.explanationRegistry().registrations().stream()
                .anyMatch(explanation -> explanation.id().equals("binomial-explanations") && !explanation.enabled()));
            assertFalse(runtime.explanationRegistry().enabledExtensions().stream()
                .anyMatch(explanation -> explanation.id().equals("binomial-explanations")));
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
    void pluginAwareEngineInvokesAllVisitorHookPhases() {
        AstVisitorRegistry visitorRegistry = new AstVisitorRegistry();
        for (AstVisitorPhase phase : AstVisitorPhase.values()) {
            visitorRegistry.register(new AstVisitorPlugin() {
                @Override
                public String id() {
                    return "capture-" + phase.name().toLowerCase();
                }

                @Override
                public AstVisitorPhase phase() {
                    return phase;
                }

                @Override
                public void visit(de.regelsuche.ast.Expr root, AstVisitorContext context) {
                    context.report(id(), "visited " + phase.name());
                }
            });
        }
        PluginAwareAstRewriteTransformationEngine engine = new PluginAwareAstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules(),
            visitorRegistry
        );

        engine.transform("x + 0");

        EnumSet<AstVisitorPhase> visited = EnumSet.noneOf(AstVisitorPhase.class);
        for (AstVisitorContext.VisitorDiagnostic diagnostic : engine.lastVisitorDiagnostics()) {
            for (AstVisitorPhase phase : AstVisitorPhase.values()) {
                if (diagnostic.message().equals("visited " + phase.name())) {
                    visited.add(phase);
                }
            }
        }
        assertEquals(EnumSet.allOf(AstVisitorPhase.class), visited);
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
    void inverseBinomialRulesAreReportedAsCyclicConflicts(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.cyclicConflicts().stream().anyMatch(cycle ->
                cycle.ruleIds().contains("binomial_square_forward")
                    && cycle.ruleIds().contains("binomial_square_backward")));
            assertTrue(runtime.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.source().equals("rule-cycle")
                    && diagnostic.message().contains("binomial_square_forward")
                    && diagnostic.message().contains("binomial_square_backward")));
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

            rule keep_whitelisted:
              pattern: A * 1
              replace: A
              direction: forward
              tags:
                - trigonometry

            rule drop_blacklisted:
              pattern: A - 0
              replace: A
              direction: forward
              tags:
                - factorization

            macro drop_complex_macro:
              input: A * 1
              output: A
              tags:
                - complex_analysis

            macro drop_blacklisted_macro:
              input: A / 1
              output: A
              tags:
                - factorization

            profile school_algebra:
              enable_tags:
                - factorization
              disable_tags:
                - complex_analysis
              whitelist:
                - keep_whitelisted
              blacklist:
                - drop_blacklisted
                - drop_blacklisted_macro
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
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("keep_whitelisted") && rule.enabled()));
            assertTrue(runtime.ruleRegistry().registrations().stream()
                .anyMatch(rule -> rule.id().equals("drop_blacklisted") && !rule.enabled()));
            assertTrue(runtime.macroRegistry().registrations().stream()
                .anyMatch(macro -> macro.id().equals("drop_blacklisted_macro") && !macro.enabled()));
            assertFalse(runtime.macroTransformations().stream()
                .anyMatch(transformation -> transformation.id().equals("macro.drop_complex_macro")));
            assertFalse(runtime.macroTransformations().stream()
                .anyMatch(transformation -> transformation.id().equals("macro.drop_blacklisted_macro")));
            assertTrue(runtime.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("Activation profile 'school_algebra' applied")));
        }
    }

    @Test
    void activeProfileFiltersAllPluginRegistries(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("profiles.regelsuche"), """
            profile school_algebra:
              enable_tags:
                - algebra
            """);

        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            true,
            Set.of(),
            Set.of(),
            "school_algebra"
        ))) {
            assertTrue(runtime.searchStrategyRegistry().registrations().stream()
                .anyMatch(strategy -> strategy.id().equals("binomial-guided-search") && !strategy.enabled()));
            assertTrue(runtime.heuristicRegistry().registrations().stream()
                .anyMatch(heuristic -> heuristic.id().equals("binomial-pattern-heuristic") && !heuristic.enabled()));
            assertTrue(runtime.costFunctionRegistry().registrations().stream()
                .anyMatch(costFunction -> costFunction.id().equals("binomial-cost-delta") && !costFunction.enabled()));
            assertTrue(runtime.rendererRegistry().registrations().stream()
                .anyMatch(renderer -> renderer.id().equals("binomial-text-renderer") && !renderer.enabled()));
            assertTrue(runtime.explanationRegistry().registrations().stream()
                .anyMatch(explanation -> explanation.id().equals("binomial-explanations") && !explanation.enabled()));
            assertTrue(runtime.parserExtensionRegistry().registrations().stream()
                .anyMatch(parser -> parser.id().equals("unicode-square-parser") && !parser.enabled()));
            assertTrue(runtime.exampleRegistry().registrations().stream()
                .anyMatch(examples -> examples.id().equals("binomial-examples") && !examples.enabled()));
            assertTrue(runtime.astVisitorRegistry().registrations().stream()
                .anyMatch(visitor -> visitor.id().equals("binomial-pattern-visitor") && !visitor.enabled()));
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
            assertTrue(runtime.loadedRuleFiles().stream()
                .anyMatch(ruleFile -> !ruleFile.loaded()
                    && ruleFile.path().endsWith("broken.regelsuche")
                    && ruleFile.diagnostics().stream().anyMatch(message -> message.contains("Missing required property 'pattern'"))));
        }
    }

    @Test
    void ruleFilesExposeLoadedDebugMetadataAndMapPrioritiesToCostDeltas(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("prioritized.regelsuche"), """
            rule high_priority:
              pattern: A + 0
              replace: A
              priority: 7
              tags:
                - simplification

            macro preferred_macro:
              input: A * 1
              output: A
              priority: 3
              tags:
                - simplification
            """);

        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            false,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.loadedRuleFiles().stream()
                .anyMatch(ruleFile -> ruleFile.loaded()
                    && ruleFile.loadedEntries() == 2
                    && ruleFile.path().endsWith("prioritized.regelsuche")));
            assertEquals(-7, runtime.ruleRegistry().enabledRules().stream()
                .filter(rule -> rule.id().equals("high_priority"))
                .findFirst()
                .orElseThrow()
                .estimatedCostDelta());
            assertEquals(-3, runtime.macroTransformations().stream()
                .filter(transformation -> transformation.id().equals("macro.preferred_macro"))
                .findFirst()
                .orElseThrow()
                .estimatedCostDelta());
        }
    }

    @Test
    void invalidPrioritiesAreReportedAsRuleFileDiagnostics(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("bad-priority.regelsuche"), """
            rule bad_priority:
              pattern: A + 0
              replace: A
              priority: urgent
            """);

        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            false,
            Set.of(),
            Set.of()
        ))) {
            assertTrue(runtime.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("Property 'priority' must be an integer")));
            assertTrue(runtime.loadedRuleFiles().stream()
                .anyMatch(ruleFile -> !ruleFile.loaded()
                    && ruleFile.path().endsWith("bad-priority.regelsuche")));
        }
    }
    @Test
    void debugModeProducesRuleAttemptReport(@TempDir Path tempDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of()
        ))) {
            PluginAwareAstRewriteTransformationEngine engine = runtime.createTransformationEngine();
            engine.enableDebugMode();

            engine.transform("(a + b)^2");

            RuleDebugReport report = engine.lastDebugReport();
            assertNotNull(report);
            assertEquals("(a + b)^2", report.expression());
            assertTrue(report.totalAttempts() > 0);
            assertTrue(report.successfulApplications() > 0);
            assertTrue(report.attempts().stream().anyMatch(attempt -> attempt.reason() == RuleRejectionReason.APPLIED));
        }
    }

    @Test
    void externalJarLoadingUsesURLClassLoaderAndServiceLoaderDiscovery(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            pluginsDir, tempDir.resolve("rules"), true, Set.of(), Set.of()
        ))) {
            assertTrue(runtime.loadedPlugins().stream().anyMatch(p -> p.id().equals("binomial-formulas")));
            assertTrue(runtime.loadedPlugins().stream()
                .filter(p -> p.source().equals(pluginsDir.toString()))
                .findFirst().isEmpty());
        }
    }

}
