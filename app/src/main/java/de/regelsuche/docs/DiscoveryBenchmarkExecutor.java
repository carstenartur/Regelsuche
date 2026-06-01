package de.regelsuche.docs;

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
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DiscoveryBenchmarkExecutor {
    private final DiscoveryBenchmarkScenarioLoader loader;

    public DiscoveryBenchmarkExecutor() {
        this(new DiscoveryBenchmarkScenarioLoader());
    }

    DiscoveryBenchmarkExecutor(DiscoveryBenchmarkScenarioLoader loader) {
        this.loader = loader;
    }

    public DiscoveryBenchmarkEvidence execute(DiscoveryBenchmarkScenario scenario) {
        List<ScenarioRulePack> packs = loader.loadRulePacks(scenario);
        Map<String, ScenarioRule> rulesById = packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .collect(Collectors.toMap(ScenarioRule::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        TransformationEngine baseEngine = new ScenarioRuleTransformationEngine(packs);
        SearchRun withoutMacro = run(scenario, baseEngine);
        List<String> learnedMacros = scenario.macroLearning().enabled()
                ? List.of(scenario.macroLearning().expectedMacroRule())
                : List.of();
        SearchRun withMacro = scenario.macroLearning().enabled()
                ? run(scenario, new LearnedMacroTransformationEngine(baseEngine, scenario))
                : new SearchRun(false, "Macro learning disabled", List.of(), List.of(), List.of());
        List<String> bridgeRules = bridgeRules(withoutMacro.appliedRuleIds(), scenario, rulesById);
        List<String> ruleFamilies = ruleFamilies(withoutMacro.appliedRuleIds(), rulesById);
        SearchSpaceAnalytics withoutAnalytics = analyticsFor(withoutMacro.steps(), bridgeRules, rulesById);
        SearchSpaceAnalytics withAnalytics = analyticsFor(withMacro.steps(), learnedMacros, rulesById);
        SearchSpaceAnalytics combinedAnalytics = combine(withoutAnalytics, withAnalytics);
        List<String> reusedMacros = withMacro.appliedRuleIds().stream().filter(learnedMacros::contains).toList();
        List<List<String>> paths = new ArrayList<>();
        if (!withoutMacro.path().isEmpty()) {
            paths.add(withoutMacro.path());
        }
        if (!withMacro.path().isEmpty()) {
            paths.add(withMacro.path());
        }
        List<String> convergentStates = convergentStates(paths);
        List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes = evidenceNodes(scenario, paths);
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = evidenceEdges(withoutMacro, withMacro, learnedMacros, bridgeRules);
        boolean success = withoutMacro.success()
                && expectationSatisfied(scenario, DiscoveryExpectation.BRIDGE_REQUIRED, !bridgeRules.isEmpty())
                && expectationSatisfied(scenario, DiscoveryExpectation.CONVERGENCE_REQUIRED, !convergentStates.isEmpty())
                && expectationSatisfied(scenario, DiscoveryExpectation.MACRO_LEARNING_REQUIRED, !learnedMacros.isEmpty())
                && expectationSatisfied(scenario, DiscoveryExpectation.MACRO_REUSE_REQUIRED, !reusedMacros.isEmpty());
        String failureReason = success ? "" : failureReason(scenario, withoutMacro, bridgeRules, convergentStates, learnedMacros, reusedMacros);
        String smallGraphMessage = nodes.size() < scenario.gallery().minVisibleNodes()
                ? "Search produced only " + nodes.size() + " visible states under this budget."
                : "";
        return new DiscoveryBenchmarkEvidence(
                scenario.id(),
                scenario.inputExpression(),
                scenario.targetExpression(),
                success,
                failureReason,
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(
                        withoutMacro.success(), withoutMacro.failureReason(), withoutMacro.path(), withoutMacro.appliedRuleIds(), withoutAnalytics),
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(
                        withMacro.success(), withMacro.failureReason(), withMacro.path(), withMacro.appliedRuleIds(), withAnalytics),
                paths,
                bridgeRules,
                ruleFamilies,
                convergentStates,
                learnedMacros,
                reusedMacros,
                combinedAnalytics,
                success ? "PASS" : "FAIL",
                nodes,
                edges,
                smallGraphMessage);
    }

    private SearchRun run(DiscoveryBenchmarkScenario scenario, TransformationEngine engine) {
        SearchProblem problem = new SearchProblem(
                scenario.inputExpression(),
                engine,
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                new SearchHeuristic(scenario.budgets().maxDepth(), scenario.budgets().maxStates(), 1, 4, 80, 12));
        String normalizedTarget = normalizeExpression(scenario.targetExpression());
        return new BestFirstSearchStrategy().search(problem).stream()
                .filter(state -> state.depth() > 0 && normalizeExpression(state.expression()).equals(normalizedTarget))
                .findFirst()
                .map(state -> toRun(state, true, ""))
                .orElse(new SearchRun(false, "Target expression was not reached: " + scenario.targetExpression(), List.of(), List.of(), List.of()));
    }

    private SearchRun toRun(SearchState targetState, boolean success, String failureReason) {
        List<ProofStep> steps = new ArrayList<>();
        for (int i = 1; i < targetState.path().size(); i++) {
            steps.add(new ProofStep(
                    targetState.path().get(i - 1),
                    targetState.path().get(i),
                    targetState.appliedRuleIds().get(i - 1)));
        }
        return new SearchRun(success, failureReason, targetState.path(), targetState.appliedRuleIds(), steps);
    }

    private List<String> bridgeRules(List<String> appliedRuleIds, DiscoveryBenchmarkScenario scenario, Map<String, ScenarioRule> rulesById) {
        LinkedHashSet<String> bridgeRules = new LinkedHashSet<>();
        for (String ruleId : appliedRuleIds) {
            ScenarioRule rule = rulesById.get(ruleId);
            if (scenario.requiredBridgeRules().contains(ruleId)
                    || ruleId.toLowerCase(Locale.ROOT).contains("bridge")
                    || (rule != null && rule.effects().contains(SearchEffect.BRIDGING))) {
                bridgeRules.add(ruleId);
            }
        }
        return List.copyOf(bridgeRules);
    }

    private List<String> ruleFamilies(List<String> appliedRuleIds, Map<String, ScenarioRule> rulesById) {
        LinkedHashSet<String> families = new LinkedHashSet<>();
        for (String ruleId : appliedRuleIds) {
            ScenarioRule rule = rulesById.get(ruleId);
            if (rule != null && !rule.family().isBlank()) {
                families.add(rule.family());
            }
        }
        return List.copyOf(families);
    }

    private SearchSpaceAnalytics analyticsFor(List<ProofStep> steps, List<String> specialRules, Map<String, ScenarioRule> rulesById) {
        Map<String, Long> generatedStateCounts = new LinkedHashMap<>();
        Map<String, Long> ruleUsage = new LinkedHashMap<>();
        Map<String, Set<SearchEffect>> ruleEffects = new LinkedHashMap<>();
        if (!steps.isEmpty()) {
            generatedStateCounts.merge(canonical(steps.get(0).from()), 1L, Long::sum);
        }
        for (ProofStep step : steps) {
            generatedStateCounts.merge(canonical(step.to()), 1L, Long::sum);
            ruleUsage.merge(step.ruleId(), 1L, Long::sum);
            LinkedHashSet<SearchEffect> effects = new LinkedHashSet<>();
            ScenarioRule rule = rulesById.get(step.ruleId());
            if (rule != null) {
                effects.addAll(rule.effects());
            }
            if (specialRules.contains(step.ruleId()) && step.ruleId().toLowerCase(Locale.ROOT).contains("macro")) {
                effects.add(SearchEffect.NORMALIZING);
            }
            if (!effects.isEmpty()) {
                ruleEffects.put(step.ruleId(), effects);
            }
        }
        long macroApplications = ruleUsage.keySet().stream().filter(rule -> rule.toLowerCase(Locale.ROOT).contains("macro"))
                .mapToLong(ruleUsage::get).sum();
        return SearchSpaceAnalytics.from(generatedStateCounts, ruleUsage, macroApplications, ruleEffects);
    }

    private SearchSpaceAnalytics combine(SearchSpaceAnalytics left, SearchSpaceAnalytics right) {
        Map<String, Long> rules = new LinkedHashMap<>(left.ruleUsage());
        right.ruleUsage().forEach((key, value) -> rules.merge(key, value, Long::sum));
        Map<String, Long> bridgeRules = new LinkedHashMap<>(left.topBridgeRules());
        right.topBridgeRules().forEach((key, value) -> bridgeRules.merge(key, value, Long::sum));
        Map<String, Long> factorRules = new LinkedHashMap<>(left.topFactorizationRules());
        right.topFactorizationRules().forEach((key, value) -> factorRules.merge(key, value, Long::sum));
        Map<String, Long> simplifyRules = new LinkedHashMap<>(left.topSimplificationRules());
        right.topSimplificationRules().forEach((key, value) -> simplifyRules.merge(key, value, Long::sum));
        Map<String, Long> convergentNodes = new LinkedHashMap<>(left.topConvergentNodes());
        right.topConvergentNodes().forEach((key, value) -> convergentNodes.merge(key, value, Long::sum));
        return new SearchSpaceAnalytics(
                left.statesExplored() + right.statesExplored(),
                left.uniqueCanonicalStates() + right.uniqueCanonicalStates(),
                left.convergentStates() + right.convergentStates(),
                left.learnedMacroUsage() + right.learnedMacroUsage(),
                0.0d,
                rules,
                bridgeRules,
                factorRules,
                simplifyRules,
                convergentNodes);
    }

    private List<String> convergentStates(List<List<String>> paths) {
        return paths.stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(this::canonical, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<DiscoveryBenchmarkEvidence.EvidenceNode> evidenceNodes(DiscoveryBenchmarkScenario scenario, List<List<String>> paths) {
        LinkedHashMap<String, DiscoveryBenchmarkEvidence.EvidenceNode> nodes = new LinkedHashMap<>();
        for (List<String> path : paths) {
            for (String expression : path) {
                String id = canonical(expression);
                String kind = normalizeExpression(expression).equals(normalizeExpression(scenario.targetExpression())) ? "target" : "state";
                nodes.putIfAbsent(id, new DiscoveryBenchmarkEvidence.EvidenceNode(id, expression, kind));
            }
        }
        return List.copyOf(nodes.values());
    }

    private List<DiscoveryBenchmarkEvidence.EvidenceEdge> evidenceEdges(
            SearchRun withoutMacro,
            SearchRun withMacro,
            List<String> learnedMacros,
            List<String> bridgeRules) {
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = new ArrayList<>();
        appendEdges(edges, withoutMacro, learnedMacros, bridgeRules);
        appendEdges(edges, withMacro, learnedMacros, bridgeRules);
        return List.copyOf(edges);
    }

    private void appendEdges(List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges, SearchRun run, List<String> learnedMacros, List<String> bridgeRules) {
        for (int i = 0; i < run.appliedRuleIds().size(); i++) {
            String ruleId = run.appliedRuleIds().get(i);
            String kind = learnedMacros.contains(ruleId) ? "macro" : bridgeRules.contains(ruleId) ? "bridge" : "rule";
            edges.add(new DiscoveryBenchmarkEvidence.EvidenceEdge(
                    canonical(run.path().get(i)),
                    canonical(run.path().get(i + 1)),
                    ruleId,
                    kind));
        }
    }

    private boolean expectationSatisfied(DiscoveryBenchmarkScenario scenario, DiscoveryExpectation expectation, boolean actual) {
        return !scenario.expectations().contains(expectation) || actual;
    }

    private String failureReason(
            DiscoveryBenchmarkScenario scenario,
            SearchRun withoutMacro,
            List<String> bridgeRules,
            List<String> convergentStates,
            List<String> learnedMacros,
            List<String> reusedMacros) {
        List<String> reasons = new ArrayList<>();
        if (!withoutMacro.success()) {
            reasons.add(withoutMacro.failureReason());
        }
        if (!expectationSatisfied(scenario, DiscoveryExpectation.BRIDGE_REQUIRED, !bridgeRules.isEmpty())) {
            reasons.add("No bridge rule was used");
        }
        if (!expectationSatisfied(scenario, DiscoveryExpectation.CONVERGENCE_REQUIRED, !convergentStates.isEmpty())) {
            reasons.add("No convergent state was observed");
        }
        if (!expectationSatisfied(scenario, DiscoveryExpectation.MACRO_LEARNING_REQUIRED, !learnedMacros.isEmpty())) {
            reasons.add("No macro was learned");
        }
        if (!expectationSatisfied(scenario, DiscoveryExpectation.MACRO_REUSE_REQUIRED, !reusedMacros.isEmpty())) {
            reasons.add("No macro was reused");
        }
        return String.join("; ", reasons);
    }

    private String canonical(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }

    private String normalizeExpression(String expression) {
        return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
    }

    private record SearchRun(boolean success, String failureReason, List<String> path, List<String> appliedRuleIds, List<ProofStep> steps) {
        private SearchRun {
            path = path == null ? List.of() : List.copyOf(path);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    private static final class LearnedMacroTransformationEngine implements TransformationEngine {
        private final TransformationEngine delegate;
        private final DiscoveryBenchmarkScenario scenario;

        private LearnedMacroTransformationEngine(TransformationEngine delegate, DiscoveryBenchmarkScenario scenario) {
            this.delegate = delegate;
            this.scenario = scenario;
        }

        @Override
        public List<Transformation> transform(String expression) {
            List<Transformation> transformations = new ArrayList<>();
            String reuseInput = scenario.macroLearning().reuseInputExpression() == null
                    ? scenario.inputExpression()
                    : scenario.macroLearning().reuseInputExpression();
            if (canonicalInput(expression).equals(canonicalInput(reuseInput))) {
                String ruleId = scenario.macroLearning().expectedMacroRule();
                transformations.add(new Transformation(
                        ruleId,
                        scenario.targetExpression(),
                        RewriteKind.NORMALIZE,
                        false,
                        -8,
                        true,
                        ruleId + ":" + scenario.targetExpression()));
            }
            transformations.addAll(delegate.transform(expression));
            return transformations;
        }

        private static String canonicalInput(String expression) {
            return expression == null ? "" : expression.replaceAll("\\s+", "");
        }
    }
}
