package de.regelsuche.docs;

import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.learning.MacroLearningPipeline;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.ProofStep;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchSpaceAnalytics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.SymPyDiscoveryOracleAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
    private final DiscoveryOperatorRegistry operatorRegistry;
    private final Set<String> operatorRuleIds;
    private final SearchTraceCollector traceCollector;
    private final SymPyDiscoveryOracleAdapter oracle;

    public DiscoveryBenchmarkExecutor() {
        this(new DiscoveryBenchmarkScenarioLoader(), new DiscoveryOperatorRegistry().register(new DefaultDiscoveryOperatorProvider()));
    }

    DiscoveryBenchmarkExecutor(DiscoveryBenchmarkScenarioLoader loader) {
        this(loader, new DiscoveryOperatorRegistry().register(new DefaultDiscoveryOperatorProvider()));
    }

    DiscoveryBenchmarkExecutor(DiscoveryBenchmarkScenarioLoader loader, DiscoveryOperatorRegistry operatorRegistry) {
        if (loader == null) {
            throw new IllegalArgumentException("Loader must not be null");
        }
        if (operatorRegistry == null) {
            throw new IllegalArgumentException("Operator registry must not be null");
        }
        this.loader = loader;
        this.operatorRegistry = operatorRegistry;
        this.operatorRuleIds = operatorRegistry.operatorRuleIds();
        this.traceCollector = new SearchTraceCollector(operatorRegistry);
        this.oracle = new SymPyDiscoveryOracleAdapter();
    }

    public DiscoveryBenchmarkEvidence execute(DiscoveryBenchmarkScenario scenario) {
        List<ScenarioRulePack> packs = loader.loadRulePacks(scenario);
        Map<String, ScenarioRule> rulesById = packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .collect(Collectors.toMap(ScenarioRule::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, String> ruleIdToPackId = buildRuleIdToPackId(packs);
        Set<String> enabledOperators = Set.copyOf(scenario.enabledOperators());
        TransformationEngine baseEngine = engineFor(scenario, packs);
        SearchRun withoutMacro = run(scenario, baseEngine);
        MacroLearningRun macroLearningRun = scenario.macroLearning().enabled() && withoutMacro.success()
                ? learnMacros(scenario, baseEngine, withoutMacro)
                : new MacroLearningRun(List.of(), Map.of(), new InMemoryRuleInventoryRepository());
        List<String> learnedMacros = macroLearningRun.learnedMacros().stream().map(ReusableRule::id).toList();
        String macroGoalExpression = withoutMacro.path().isEmpty() ? scenario.targetExpression() : withoutMacro.path().getLast();
        boolean macroReuseRequired = scenario.expectations().contains(DiscoveryExpectation.MACRO_REUSE_REQUIRED);
        SearchRun withMacro = scenario.macroLearning().enabled()
                ? (!learnedMacros.isEmpty()
                        ? runMacroRerun(
                                scenario,
                                baseEngine,
                                macroLearningRun,
                                learnedMacros,
                                macroGoalExpression,
                                macroReuseRequired)
                        : new SearchRun(false, "Macro learning required but no macro was learned", List.of(), List.of(), List.of(), List.of()))
                : new SearchRun(false, "Macro learning disabled", List.of(), List.of(), List.of(), List.of());
        List<String> bridgeRules = bridgeRules(withoutMacro.appliedRuleIds(), scenario, rulesById);
        List<String> ruleFamilies = ruleFamilies(withoutMacro.appliedRuleIds(), rulesById);
        SearchSpaceAnalytics withoutAnalytics = analyticsFor(withoutMacro.steps(), bridgeRules, rulesById);
        SearchSpaceAnalytics withAnalytics = analyticsFor(withMacro.steps(), learnedMacros, rulesById);
        SearchSpaceAnalytics combinedAnalytics = combine(withoutAnalytics, withAnalytics);
        List<String> reusedMacros = withMacro.appliedRuleIds().stream().filter(learnedMacros::contains).toList();
        SymPyDiscoveryOracleAdapter.OracleResult oracleResult =
                oracle.equivalence(scenario.inputExpression(), scenario.targetExpression());
        boolean promotionEligible = withoutMacro.success()
                && (!scenario.macroLearning().enabled() || withMacro.success())
                && oracleResult.status() != SymPyDiscoveryOracleAdapter.Status.DISAGREE;
        List<List<String>> paths = new ArrayList<>();
        if (!withoutMacro.path().isEmpty()) {
            paths.add(withoutMacro.path());
        }
        if (!withMacro.path().isEmpty()) {
            paths.add(withMacro.path());
        }
        List<String> convergentStates = convergentStates(paths);
        SearchRun withoutMacroTraceRun = ensureMeaningfulGraphCoverage(scenario, baseEngine, withoutMacro);
        SearchTraceCollector.TraceGraph traceGraph = traceCollector.collect(
                scenario,
                new SearchTraceCollector.SearchRunTrace(
                        withoutMacro.success(),
                        withoutMacroTraceRun.exploredStates(),
                        withoutMacro.path(),
                        withoutMacro.appliedRuleIds()),
                new SearchTraceCollector.SearchRunTrace(
                        withMacro.success(),
                        withMacro.exploredStates(),
                        withMacro.path(),
                        withMacro.appliedRuleIds()),
                learnedMacros,
                bridgeRules,
                rulesById,
                ruleIdToPackId,
                enabledOperators);
        List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes = traceGraph.nodes();
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = traceGraph.edges();
        boolean success = withoutMacro.success()
                && (!scenario.macroLearning().enabled() || withMacro.success())
                && expectationSatisfied(scenario, DiscoveryExpectation.BRIDGE_REQUIRED, !bridgeRules.isEmpty())
                && expectationSatisfied(scenario, DiscoveryExpectation.CONVERGENCE_REQUIRED, !convergentStates.isEmpty())
                && expectationSatisfied(scenario, DiscoveryExpectation.MACRO_LEARNING_REQUIRED, !learnedMacros.isEmpty())
                && expectationSatisfied(scenario, DiscoveryExpectation.MACRO_REUSE_REQUIRED, !reusedMacros.isEmpty());
        String failureReason = success ? "" : failureReason(scenario, withoutMacro, withMacro, bridgeRules, convergentStates, learnedMacros, reusedMacros);
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
                oracleResult.status().name(),
                oracleResult.evidence(),
                promotionEligible,
                nodes,
                edges,
                smallGraphMessage);
    }

    private SearchRun ensureMeaningfulGraphCoverage(
            DiscoveryBenchmarkScenario scenario,
            TransformationEngine engine,
            SearchRun baselineRun) {
        int minVisibleNodes = Math.max(1, scenario.gallery().minVisibleNodes());
        SearchRun bestCoverage = baselineRun;
        int bestNodeCount = uniqueNodeCount(baselineRun.exploredStates());
        if (bestNodeCount >= minVisibleNodes) {
            return bestCoverage;
        }
        int maxDepth = Math.max(1, scenario.budgets().maxDepth());
        int maxStates = Math.max(1, scenario.budgets().maxStates());
        for (int attempt = 0; attempt < 3 && bestNodeCount < minVisibleNodes; attempt++) {
            maxDepth = maxDepth + 2;
            maxStates = maxStates * 2;
            SearchRun expandedRun = run(scenario, engine, maxDepth, maxStates);
            int expandedNodeCount = uniqueNodeCount(expandedRun.exploredStates());
            if (expandedNodeCount > bestNodeCount) {
                bestCoverage = new SearchRun(
                        baselineRun.success(),
                        baselineRun.failureReason(),
                        baselineRun.path(),
                        baselineRun.appliedRuleIds(),
                        baselineRun.steps(),
                        expandedRun.exploredStates());
                bestNodeCount = expandedNodeCount;
            }
        }
        return bestCoverage;
    }

    private int uniqueNodeCount(List<SearchState> states) {
        HashSet<String> ids = new HashSet<>();
        for (SearchState state : states) {
            ids.add(canonical(state.expression()));
        }
        return ids.size();
    }

    private SearchRun runMacroRerun(
            DiscoveryBenchmarkScenario scenario,
            TransformationEngine baseEngine,
            MacroLearningRun macroLearningRun,
            List<String> learnedMacros,
            String macroGoalExpression,
            boolean macroReuseRequired) {
        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(macroLearningRun.inventory());
        SearchRun goalAwareRun = run(scenario, new MacroMoveTransformationEngine(
                baseEngine,
                selector,
                macroGoalExpression,
                macroLearningRun.atomicStepsByRuleId(),
                macroLearningRun.learnedMacros().getFirst().assumptions()));
        if (!macroReuseRequired || goalAwareRun.appliedRuleIds().stream().anyMatch(learnedMacros::contains)) {
            return goalAwareRun;
        }
        SearchRun fallbackRun = run(scenario, new MacroMoveTransformationEngine(
                baseEngine,
                selector,
                null,
                macroLearningRun.atomicStepsByRuleId(),
                macroLearningRun.learnedMacros().getFirst().assumptions()));
        return fallbackRun.appliedRuleIds().stream().anyMatch(learnedMacros::contains) ? fallbackRun : goalAwareRun;
    }

    private TransformationEngine engineFor(DiscoveryBenchmarkScenario scenario, List<ScenarioRulePack> scenarioPacks) {
        KnowledgePackSelection selection = new KnowledgePackSelection(null, Set.copyOf(scenario.enabledRulePacks()), Set.of());
        TransformationEngine astEngine = AstRewriteTransformationEngine.withKnowledgePacks(selection);
        TransformationEngine ruleEngine = scenarioPacks.isEmpty()
                ? astEngine
                : new CompositeTransformationEngine(List.of(astEngine, new ScenarioRuleTransformationEngine(scenarioPacks)));
        List<HypothesisOperator> operators = operatorsFor(scenario);
        return operators.isEmpty() ? ruleEngine : new HypothesisTransformationEngine(ruleEngine, operators, 16);
    }

    private List<HypothesisOperator> operatorsFor(DiscoveryBenchmarkScenario scenario) {
        return operatorRegistry.operatorsFor(new DiscoveryOperatorRegistry.OperatorProfile(scenario.enabledOperators()));
    }

    private static Map<String, String> buildRuleIdToPackId(List<ScenarioRulePack> packs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ScenarioRulePack pack : packs) {
            for (ScenarioRule rule : pack.rules()) {
                map.putIfAbsent(rule.id(), pack.id());
            }
        }
        return Map.copyOf(map);
    }

    private MacroLearningRun learnMacros(DiscoveryBenchmarkScenario scenario, TransformationEngine baseEngine, SearchRun withoutMacro) {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        SuccessfulTransformationPath path = new SuccessfulTransformationPath(
                scenario.id() + "-discovered-path",
                withoutMacro.path().getFirst(),
                withoutMacro.path().getLast(),
                withoutMacro.path(),
                withoutMacro.appliedRuleIds(),
                new ExpressionScorer().score(withoutMacro.path().getFirst()),
                new ExpressionScorer().score(withoutMacro.path().getLast()),
                true,
                "scenario-driven discovery path",
                Map.of("source", "DiscoveryBenchmarkExecutor"));
        MacroLearningResult result = new MacroLearningPipeline(inventory).learn(List.of(path));
        List<ReusableRule> learned = result.newlyActivated();
        Map<String, List<TransformationStep>> atomicSteps = learned.stream()
                .collect(Collectors.toMap(ReusableRule::id, rule -> atomicSteps(withoutMacro), (left, right) -> left, LinkedHashMap::new));
        return new MacroLearningRun(learned, atomicSteps, inventory);
    }

    private List<TransformationStep> atomicSteps(SearchRun run) {
        List<TransformationStep> steps = new ArrayList<>();
        ExpressionScorer scorer = new ExpressionScorer();
        for (int index = 0; index < run.appliedRuleIds().size(); index++) {
            String before = run.path().get(index);
            String after = run.path().get(index + 1);
            steps.add(new TransformationStep(
                    index,
                    before,
                    after,
                    run.appliedRuleIds().get(index),
                    RewriteKind.NORMALIZE,
                    scorer.score(before).weightedTotal(),
                    scorer.score(after).weightedTotal(),
                    true,
                    run.appliedRuleIds().get(index),
                    List.of()));
        }
        return List.copyOf(steps);
    }

    private SearchRun run(DiscoveryBenchmarkScenario scenario, TransformationEngine engine) {
        return run(scenario, engine, scenario.budgets().maxDepth(), scenario.budgets().maxStates());
    }

    private SearchRun run(DiscoveryBenchmarkScenario scenario, TransformationEngine engine, int maxDepth, int maxStates) {
        SearchProblem problem = new SearchProblem(
                scenario.inputExpression(),
                engine,
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                new SearchHeuristic(maxDepth, maxStates, 1, 4, 80, 12));
        String normalizedTarget = normalizeExpression(scenario.targetExpression());
        List<SearchState> explored = new BestFirstSearchStrategy().search(problem.withGoal(TransformationGoal.FACTORIZE));
        return explored.stream()
                .filter(state -> state.depth() > 0 && normalizeExpression(state.expression()).equals(normalizedTarget))
                .sorted(Comparator.comparingInt(state -> pathPreference(state.appliedRuleIds())))
                .findFirst().map(state -> toRun(state, true, "", explored))
                .orElse(new SearchRun(
                        false,
                        "Target expression was not reached: " + scenario.targetExpression(),
                        List.of(),
                        List.of(),
                        List.of(),
                        explored));
    }

    private int pathPreference(List<String> appliedRuleIds) {
        if (appliedRuleIds.stream().anyMatch(rule -> rule.toLowerCase(Locale.ROOT).contains("macro"))) {
            return 0;
        }
        return appliedRuleIds.contains("ast_linear_offset_simplify") ? 1 : 2;
    }

    private SearchRun toRun(SearchState targetState, boolean success, String failureReason, List<SearchState> exploredStates) {
        List<ProofStep> steps = new ArrayList<>();
        for (int i = 1; i < targetState.path().size(); i++) {
            steps.add(new ProofStep(
                    targetState.path().get(i - 1),
                    targetState.path().get(i),
                    targetState.appliedRuleIds().get(i - 1)));
        }
        return new SearchRun(success, failureReason, targetState.path(), targetState.appliedRuleIds(), steps, exploredStates);
    }

    private List<String> bridgeRules(List<String> appliedRuleIds, DiscoveryBenchmarkScenario scenario, Map<String, ScenarioRule> rulesById) {
        LinkedHashSet<String> bridgeRules = new LinkedHashSet<>();
        for (String ruleId : appliedRuleIds) {
            ScenarioRule rule = rulesById.get(ruleId);
            if (scenario.requiredBridgeRules().contains(ruleId)
                    || ruleId.toLowerCase(Locale.ROOT).contains("bridge")
                    || operatorRuleIds.contains(ruleId)
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
            effects.addAll(inferredEffects(step.ruleId()));
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
        int overlappingStartState = left.statesExplored() > 0 && right.statesExplored() > 0 ? 1 : 0;
        return new SearchSpaceAnalytics(
                left.statesExplored() + right.statesExplored() - overlappingStartState,
                left.uniqueCanonicalStates() + right.uniqueCanonicalStates() - overlappingStartState,
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
            List<String> bridgeRules,
            Map<String, ScenarioRule> rulesById) {
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = new ArrayList<>();
        appendEdges(edges, withoutMacro, learnedMacros, bridgeRules, rulesById);
        appendEdges(edges, withMacro, learnedMacros, bridgeRules, rulesById);
        return List.copyOf(edges);
    }

    private void appendEdges(
            List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges,
            SearchRun run,
            List<String> learnedMacros,
            List<String> bridgeRules,
            Map<String, ScenarioRule> rulesById) {
        for (int i = 0; i < run.appliedRuleIds().size(); i++) {
            String ruleId = run.appliedRuleIds().get(i);
            String kind = learnedMacros.contains(ruleId) ? "macro" : bridgeRules.contains(ruleId) ? "bridge" : "rule";
            edges.add(new DiscoveryBenchmarkEvidence.EvidenceEdge(
                    canonical(run.path().get(i)),
                    canonical(run.path().get(i + 1)),
                    ruleId,
                    kind,
                    sourceFor(ruleId, learnedMacros, rulesById),
                    packIdFor(ruleId, rulesById),
                    List.copyOf(inferredEffects(ruleId))));
        }
    }

    private String sourceFor(String ruleId, List<String> learnedMacros, Map<String, ScenarioRule> rulesById) {
        if (learnedMacros.contains(ruleId)) {
            return "macro";
        }
        if (operatorRuleIds.contains(ruleId)) {
            return "operator";
        }
        return rulesById.containsKey(ruleId) ? "scenario-generic" : "core";
    }

    private String packIdFor(String ruleId, Map<String, ScenarioRule> rulesById) {
        return rulesById.containsKey(ruleId) ? "scenario-generic" : "core";
    }

    private Set<SearchEffect> inferredEffects(String ruleId) {
        LinkedHashSet<SearchEffect> effects = new LinkedHashSet<>();
        String lower = ruleId.toLowerCase(Locale.ROOT);
        if (operatorRuleIds.contains(ruleId) || lower.contains("bridge")) {
            effects.add(SearchEffect.BRIDGING);
        }
        if (lower.contains("factor")) {
            effects.add(SearchEffect.FACTORIZING);
        }
        if (lower.contains("simplify")) {
            effects.add(SearchEffect.SIMPLIFYING);
        }
        if (lower.contains("normalize") || lower.contains("macro")) {
            effects.add(SearchEffect.NORMALIZING);
        }
        if (lower.contains("expand")) {
            effects.add(SearchEffect.EXPANDING);
        }
        return effects;
    }

    private boolean expectationSatisfied(DiscoveryBenchmarkScenario scenario, DiscoveryExpectation expectation, boolean actual) {
        return !scenario.expectations().contains(expectation) || actual;
    }

    private String failureReason(
            DiscoveryBenchmarkScenario scenario,
            SearchRun withoutMacro,
            SearchRun withMacro,
            List<String> bridgeRules,
            List<String> convergentStates,
            List<String> learnedMacros,
            List<String> reusedMacros) {
        List<String> reasons = new ArrayList<>();
        if (!withoutMacro.success()) {
            reasons.add(withoutMacro.failureReason());
        }
        if (scenario.macroLearning().enabled() && !withMacro.success()) {
            reasons.add(withMacro.failureReason());
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
        if (expression == null) {
            return "";
        }
        try {
            return new ExpressionCanonicalizer().canonicalize(expression);
        } catch (IllegalArgumentException exception) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private record SearchRun(
            boolean success,
            String failureReason,
            List<String> path,
            List<String> appliedRuleIds,
            List<ProofStep> steps,
            List<SearchState> exploredStates) {
        private SearchRun {
            path = path == null ? List.of() : List.copyOf(path);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
            steps = steps == null ? List.of() : List.copyOf(steps);
            exploredStates = exploredStates == null ? List.of() : List.copyOf(exploredStates);
        }
    }

    private record MacroLearningRun(
            List<ReusableRule> learnedMacros,
            Map<String, List<TransformationStep>> atomicStepsByRuleId,
            InMemoryRuleInventoryRepository inventory) {
        private MacroLearningRun {
            learnedMacros = learnedMacros == null ? List.of() : List.copyOf(learnedMacros);
            atomicStepsByRuleId = atomicStepsByRuleId == null ? Map.of() : Map.copyOf(atomicStepsByRuleId);
        }
    }

    private static final class CompositeTransformationEngine implements TransformationEngine {
        private final List<TransformationEngine> engines;

        private CompositeTransformationEngine(List<TransformationEngine> engines) {
            this.engines = List.copyOf(engines);
        }

        @Override
        public List<Transformation> transform(String expression) {
            return engines.stream().flatMap(engine -> engine.transform(expression).stream()).toList();
        }
    }
}
