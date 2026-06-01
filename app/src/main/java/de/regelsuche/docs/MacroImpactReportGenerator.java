package de.regelsuche.docs;

import de.regelsuche.benchmark.DiscoveryBenchmarkCase;
import de.regelsuche.benchmark.DiscoveryBenchmarkResult;
import de.regelsuche.benchmark.DiscoveryBenchmarkRunner;
import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.ProofStep;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchSpaceAnalytics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MacroImpactReportGenerator {
    private static final String INPUT_EXPRESSION = "( x + 0 ) * ( x + 0 )";
    private static final String TARGET_EXPRESSION = "x ^ 2";
    private static final String MACRO_RULE_ID = "macro_square_after_zero_simplification";

    public MacroImpactReport generate() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        SearchRun withoutMacro = run(new AstRewriteTransformationEngine(), canonicalizer);
        SearchRun withMacro = run(new MacroTransformationEngine(new AstRewriteTransformationEngine()), canonicalizer);
        String bridgeRule = bridgeRule(withoutMacro.appliedRuleIds());
        SearchSpaceAnalytics withoutAnalytics = analyticsFor(withoutMacro.steps(), Set.of(bridgeRule));
        SearchSpaceAnalytics withAnalytics = analyticsFor(withMacro.steps(), Set.of(MACRO_RULE_ID));
        DiscoveryBenchmarkResult withoutBenchmark = new DiscoveryBenchmarkRunner().run(new DiscoveryBenchmarkCase(
                "docs-without-macro",
                INPUT_EXPRESSION,
                withoutMacro.appliedRuleIds().get(withoutMacro.appliedRuleIds().size() - 1),
                List.of(withoutMacro.appliedRuleIds()),
                Set.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of(bridgeRule),
                Set.of(),
                Set.of()));
        DiscoveryBenchmarkResult withBenchmark = new DiscoveryBenchmarkRunner().run(new DiscoveryBenchmarkCase(
                "docs-with-macro",
                INPUT_EXPRESSION,
                MACRO_RULE_ID,
                List.of(withMacro.appliedRuleIds()),
                Set.of(DiscoveryExpectation.MACRO_REUSE_REQUIRED),
                List.of(),
                Set.of(MACRO_RULE_ID),
                Set.of(MACRO_RULE_ID)));
        return new MacroImpactReport(
                withoutAnalytics.statesExplored(),
                withAnalytics.statesExplored(),
                withoutBenchmark.pathCount() + withBenchmark.pathCount(),
                withoutBenchmark.convergenceCount() + withBenchmark.convergenceCount(),
                withoutBenchmark.bridgeCount(),
                withoutBenchmark.bridgeCount() > 0,
                withBenchmark.macroReuseCount() > 0,
                INPUT_EXPRESSION,
                TARGET_EXPRESSION,
                withoutMacro.expressionPath(),
                withMacro.expressionPath(),
                withoutAnalytics,
                withAnalytics,
                withoutBenchmark,
                withBenchmark);
    }

    public String renderText(MacroImpactReport report) {
        return """
                Without macro: %d states
                With macro: %d states
                Paths explored: %d
                Convergences: %d
                Bridge usage: %d
                Bridge discovered: %s
                Macro reused: %s
                Improvement: %.2fx
                """.formatted(
                report.withoutMacroStates(),
                report.withMacroStates(),
                report.pathsExplored(),
                report.convergenceCount(),
                report.bridgeUsage(),
                report.bridgeDiscovered() ? "yes" : "no",
                report.macroReused() ? "yes" : "no",
                report.improvementFactor());
    }

    private SearchRun run(TransformationEngine engine, ExpressionCanonicalizer canonicalizer) {
        SearchProblem problem = new SearchProblem(
                INPUT_EXPRESSION,
                engine,
                new ExpressionScorer(),
                canonicalizer,
                new SearchHeuristic(4, 80, 1, 4, 80, 12));
        String targetHash = canonicalizer.stableHash(TARGET_EXPRESSION);
        return new BestFirstSearchStrategy().search(problem).stream()
                .filter(state -> state.canonicalHash().equals(targetHash))
                .findFirst()
                .map(this::toRun)
                .orElseThrow(() -> new IllegalStateException("Target expression was not reached: " + TARGET_EXPRESSION));
    }

    private SearchRun toRun(SearchState targetState) {
        List<ProofStep> steps = new ArrayList<>();
        for (int i = 1; i < targetState.path().size(); i++) {
            steps.add(new ProofStep(
                    targetState.path().get(i - 1),
                    targetState.path().get(i),
                    targetState.appliedRuleIds().get(i - 1)));
        }
        return new SearchRun(targetState.path(), targetState.appliedRuleIds(), steps);
    }

    private SearchSpaceAnalytics analyticsFor(List<ProofStep> steps, Set<String> bridgeRules) {
        Map<String, Long> generatedStateCounts = new LinkedHashMap<>();
        Map<String, Long> ruleUsage = new LinkedHashMap<>();
        Map<String, Set<SearchEffect>> ruleEffects = new LinkedHashMap<>();
        if (!steps.isEmpty()) {
            generatedStateCounts.merge(canonical(steps.get(0).from()), 1L, Long::sum);
        }
        for (ProofStep step : steps) {
            generatedStateCounts.merge(canonical(step.to()), 1L, Long::sum);
            ruleUsage.merge(step.ruleId(), 1L, Long::sum);
            Set<SearchEffect> effects = new LinkedHashSet<>();
            if (bridgeRules.contains(step.ruleId())) {
                effects.add(SearchEffect.BRIDGING);
            }
            if (step.ruleId().contains("factor")) {
                effects.add(SearchEffect.FACTORIZING);
            }
            if (step.ruleId().contains("simplify") || step.ruleId().contains("zero")) {
                effects.add(SearchEffect.SIMPLIFYING);
            }
            if (!effects.isEmpty()) {
                ruleEffects.put(step.ruleId(), effects);
            }
        }
        long macroApplications = ruleUsage.keySet().stream().filter(rule -> rule.toLowerCase(Locale.ROOT).contains("macro"))
                .mapToLong(ruleUsage::get).sum();
        return SearchSpaceAnalytics.from(generatedStateCounts, ruleUsage, macroApplications, ruleEffects);
    }

    private String bridgeRule(List<String> appliedRuleIds) {
        return appliedRuleIds.stream()
                .filter(rule -> !rule.toLowerCase(Locale.ROOT).contains("macro"))
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new IllegalStateException("No bridge rule found in non-macro run"));
    }

    private String canonical(String state) {
        return state == null ? "" : state.replaceAll("\\s+", "");
    }

    private record SearchRun(List<String> expressionPath, List<String> appliedRuleIds, List<ProofStep> steps) {
        private SearchRun {
            expressionPath = List.copyOf(expressionPath);
            appliedRuleIds = List.copyOf(appliedRuleIds);
            steps = List.copyOf(steps);
        }
    }

    private static final class MacroTransformationEngine implements TransformationEngine {
        private final TransformationEngine delegate;

        private MacroTransformationEngine(TransformationEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Transformation> transform(String expression) {
            List<Transformation> transformations = new ArrayList<>();
            if (canonicalInput(expression).equals(canonicalInput(INPUT_EXPRESSION))) {
                transformations.add(new Transformation(
                        MACRO_RULE_ID,
                        TARGET_EXPRESSION,
                        RewriteKind.NORMALIZE,
                        false,
                        -8,
                        true,
                        MACRO_RULE_ID + ":" + TARGET_EXPRESSION));
            }
            transformations.addAll(delegate.transform(expression));
            return transformations;
        }

        private static String canonicalInput(String expression) {
            return expression == null ? "" : expression.replaceAll("\\s+", "");
        }
    }
}
