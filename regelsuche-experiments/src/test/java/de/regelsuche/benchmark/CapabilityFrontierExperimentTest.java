package de.regelsuche.benchmark;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.ast.BinaryOperator.SUB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.CapabilityFrontierExperiment.CaseResult;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.ConnectivityExpectation;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.FrontierCase;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.FrontierOutcome;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.FrontierReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AssessmentDecision;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.PrimaryStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.BoundedRewriteReachabilityOracle;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CapabilityFrontierExperimentTest {
    private static final int MAX_RECOGNITION_STATES = 1_000;
    private static final String TEST_SHA256 = "0".repeat(64);

    private final CapabilityFrontierExperiment experiment =
        new CapabilityFrontierExperiment();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void targetGuidanceSolvesARealDistributionCaseUnderTheSameTightBudget() {
        CaseResult result = byId(experiment.run(referenceCases()))
            .get("real-distribution-guided-only");

        assertFalse(result.baseline().reached(),
            "legacy rule ordering spends the single candidate on x+0 -> x");
        assertTrue(result.guided().reached(),
            "target distance must prioritize the real distributivity rule");
        assertEquals(FrontierOutcome.GUIDED_ONLY_REACHED, result.outcome());
        assertTrue(result.materialSuccess());
        assertEquals(GoalStatus.REACHED, result.guided().status());
        assertTrue(result.guided().metrics().candidateBudgetPrunes() >= 1);
    }

    @Test
    void observedSearchStatusSeparatesMissingOperatorsBudgetsAndDeadEnds() {
        Map<String, CaseResult> cases = byId(experiment.run(referenceCases()));

        CaseResult missing = cases.get("distribution-operator-removed");
        assertEquals(GoalStatus.FRONTIER_EXHAUSTED, missing.guided().status());
        assertEquals(FrontierOutcome.MISSING_OPERATOR, missing.outcome());

        CaseResult depth = cases.get("syntax-target-depth-limited");
        assertEquals(GoalStatus.DEPTH_BUDGET, depth.guided().status());
        assertEquals(FrontierOutcome.DEPTH_BUDGET, depth.outcome());

        CaseResult deadEnd = cases.get("no-outgoing-transformations");
        assertEquals(GoalStatus.NO_TRANSFORMATIONS, deadEnd.guided().status());
        assertEquals(FrontierOutcome.NO_TRANSFORMATIONS, deadEnd.outcome());
    }

    @Test
    void explicitBridgeMovesTheSophieGermainCapabilityFrontier() {
        SearchHeuristic heuristic = new SearchHeuristic(4, 200, 1, 10, 200, 200);
        AstRewriteTransformationEngine primitive = new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules(), 128, 160);
        HypothesisTransformationEngine withBridge = new HypothesisTransformationEngine(
            primitive,
            List.of(new DifferenceOfSquaresPreparationOperator()),
            16);
        String root = "x^4 + 4*y^4";
        String target = "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)";

        FrontierReport report = experiment.run(List.of(
            new FrontierCase(
                "sophie-germain-without-bridge", root,
                SearchTarget.valueEquivalent(target), primitive, heuristic,
                ConnectivityExpectation.MISSING_OPERATOR),
            new FrontierCase(
                "sophie-germain-with-bridge", root,
                SearchTarget.valueEquivalent(target), withBridge, heuristic,
                ConnectivityExpectation.CONNECTED)));
        Map<String, CaseResult> cases = byId(report);

        assertFalse(cases.get("sophie-germain-without-bridge").guided().reached());
        CaseResult enabled = cases.get("sophie-germain-with-bridge");
        assertTrue(enabled.guided().reached(), enabled.toString());
        assertTrue(enabled.guided().ruleIds().contains(
            DifferenceOfSquaresPreparationOperator.RULE_ID));
        assertTrue(enabled.guided().ruleIds().contains("ast_square_difference_factor"));
    }

    @Test
    void machineReportIsDeterministicCompleteAndWritable(@TempDir Path directory)
            throws Exception {
        FrontierReport first = experiment.run(referenceCases());
        FrontierReport second = experiment.run(referenceCases());

        assertEquals(first.toJson(), second.toJson());
        assertEquals(1, first.materialSuccesses());
        assertTrue(first.toJson().startsWith(
            "{\"schema\":\"regelsuche.capability-frontier/v1\""));
        assertTrue(first.toJson().contains("\"generatedTransformations\""));
        assertTrue(first.toJson().contains("\"transpositionPrunes\""));
        assertTrue(first.toJson().contains("\"identityCacheMisses\""));
        assertTrue(first.cases().stream()
            .allMatch(result -> result.guided().metrics().internedValues() > 0));

        Path output = experiment.write(directory.resolve("capability-frontier.json"), first);
        assertEquals(first.toJson(), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void broaderRecognitionStrictlyIncreasesKnownDerivationCoverage() {
        List<HistoricalRediscoveryCorpus.Case> corpus =
            HistoricalRediscoveryCorpus.load().cases().stream()
                .filter(value -> Set.of(
                    "complete-square",
                    "reordered-square",
                    "scaled-square",
                    "inconsistent-near-miss").contains(value.id()))
                .toList();
        Map<RecognitionLayer, RecognitionSummary> summaries =
            new EnumMap<>(RecognitionLayer.class);
        for (RecognitionLayer layer : RecognitionLayer.values()) {
            summaries.put(layer, runRecognitionCorpus(
                corpus,
                layer.profile));
        }

        RecognitionSummary exact = summaries.get(RecognitionLayer.EXACT);
        RecognitionSummary ac = summaries.get(RecognitionLayer.AC);
        RecognitionSummary algebraic = summaries.get(RecognitionLayer.ALGEBRAIC_AC);

        assertRecognitionSuperset(ac, exact);
        assertRecognitionSuperset(algebraic, ac);
        assertRecognitionCase(exact, "reordered-square", false);
        assertRecognitionCase(ac, "reordered-square", true);
        assertRecognitionCase(ac, "scaled-square", false);
        assertRecognitionCase(algebraic, "scaled-square", true);
        assertTrue(exact.solved() < ac.solved(), summaries.toString());
        assertTrue(ac.solved() < algebraic.solved(), summaries.toString());
        assertRecognitionCase(exact, "inconsistent-near-miss", false);
        assertRecognitionCase(ac, "inconsistent-near-miss", false);
        assertRecognitionCase(algebraic, "inconsistent-near-miss", false);
    }

    @Test
    void boundedOracleDistinguishesWitnessClosureAndBudget() {
        TransformationEngine diamond = graph(Map.of(
            "A", List.of(edge("z_to_c", "C"), edge("a_to_b", "B")),
            "B", List.of(edge("b_to_d", "D")),
            "C", List.of(edge("c_to_d", "D"))));
        BoundedRewriteReachabilityOracle.Result reachable =
            new BoundedRewriteReachabilityOracle(diamond).search(
                "A", "D",
                new BoundedRewriteReachabilityOracle.Budget(4, 20));
        assertEquals(BoundedRewriteReachabilityOracle.Status.REACHABLE,
            reachable.status());
        assertEquals(List.of("a_to_b", "b_to_d"),
            reachable.witness().stream()
                .map(BoundedRewriteReachabilityOracle.Step::rule)
                .toList());
        assertEquals(4, reachable.visitedStates());
        assertEquals(3, reachable.generatedTransitions());

        TransformationEngine finiteCycle = graph(Map.of(
            "A", List.of(edge("a_to_b", "B")),
            "B", List.of(edge("b_to_a", "A"))));
        BoundedRewriteReachabilityOracle.Result exhausted =
            new BoundedRewriteReachabilityOracle(finiteCycle).search(
                "A", "Z",
                new BoundedRewriteReachabilityOracle.Budget(10, 20));
        assertEquals(
            BoundedRewriteReachabilityOracle.Status
                .UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE,
            exhausted.status());
        assertFalse(exhausted.depthLimitReached());
        assertFalse(exhausted.stateLimitReached());

        TransformationEngine deeper = graph(Map.of(
            "A", List.of(edge("a_to_b", "B")),
            "B", List.of(edge("b_to_c", "C"))));
        BoundedRewriteReachabilityOracle.Result inconclusive =
            new BoundedRewriteReachabilityOracle(deeper).search(
                "A", "Z",
                new BoundedRewriteReachabilityOracle.Budget(1, 20));
        assertEquals(
            BoundedRewriteReachabilityOracle.Status.BUDGET_INCONCLUSIVE,
            inconclusive.status());
        assertTrue(inconclusive.depthLimitReached());

        BoundedRewriteReachabilityOracle.Result canonical =
            new BoundedRewriteReachabilityOracle(
                graph(Map.of("A", List.of(edge("a_to_b", " B ")))),
                value -> value.trim().toLowerCase())
                .search(" A ", "b",
                    new BoundedRewriteReachabilityOracle.Budget(1, 2));
        assertEquals(BoundedRewriteReachabilityOracle.Status.REACHABLE,
            canonical.status());

        assertThrows(IllegalArgumentException.class,
            () -> new BoundedRewriteReachabilityOracle.Budget(-1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new BoundedRewriteReachabilityOracle.Budget(1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new BoundedRewriteReachabilityOracle.Step(
                "A", "B", "rule", List.of(), " ",
                List.of("rule"), 1));
    }

    @Test
    void targetBlindStructuralCellsRetainAStarvedExpansion() {
        String root = "x + 0";
        String target = "x + y - y";
        TransformationEngine engine = expression -> switch (expression) {
            case "x + 0" -> List.of(
                transformation("simplify-root", "x",
                    RewriteKind.SIMPLIFY, false, -2),
                transformation("expand-root", target,
                    RewriteKind.EXPAND, true, 5));
            case "x" -> List.of(
                transformation("reintroduce-neutral", "x * 1",
                    RewriteKind.NORMALIZE, false, 0));
            default -> List.of();
        };
        SearchProblem problem = new SearchProblem(
            root,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 3, 1, 2, 8, 2));

        List<SearchState> scalar = new BestFirstSearchStrategy().search(problem);
        List<SearchState> diverse =
            new StructuralDiversitySearchStrategy().search(problem);

        assertFalse(containsExpression(scalar, target), scalar.toString());
        assertTrue(containsExpression(diverse, target), diverse.toString());
        assertEquals(diverse,
            new StructuralDiversitySearchStrategy().search(problem));
        assertInstanceOf(StructuralDiversitySearchStrategy.class,
            SearchProfile.DIVERSITY_DISCOVERY.newStrategy());
        assertNull(problem.target(), "the diversity control must remain target-blind");
    }

    @Test
    void frozenHistoricalCorpusFailsClosed() {
        Corpus corpus = HistoricalRediscoveryCorpus.load();
        assertEquals(HistoricalRediscoveryCorpus.SCHEMA, corpus.schema());
        assertEquals("FROZEN_DIAGNOSTIC_CORPUS", corpus.evidenceStatus());
        assertEquals(14, corpus.cases().size());
        assertEquals(corpus.cases().size(),
            new HashSet<>(corpus.cases().stream()
                .map(HistoricalRediscoveryCorpus.Case::id)
                .toList()).size());
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.id().equals("sophie-germain")));
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.role() == Role.SEARCH_POLICY_CONTROL));
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.role() == Role.NEGATIVE_CONTROL
                && value.relation() == Relation.NOT_EQUIVALENT));

        String valid = minimalCorpusCase(
            "NOT_EQUIVALENT", "NEGATIVE_CONTROL", "1");
        HistoricalRediscoveryCorpus.parse(valid, TEST_SHA256);
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                valid.replace("\"claimBoundary\":\"bounded\",", ""),
                TEST_SHA256));
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                valid.replace("\"family\":\"TEST\",",
                    "\"family\":\"TEST\",\"unknown\":true,"),
                TEST_SHA256));
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                minimalCorpusCase("EQUIVALENT", "NEGATIVE_CONTROL", "1"),
                TEST_SHA256));
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                minimalCorpusCase("NOT_EQUIVALENT", "NEGATIVE_CONTROL", "1.5"),
                TEST_SHA256));
    }

    @Test
    @Timeout(240)
    void historicalAtlasSeparatesMechanismsAndWritesStableEvidence(
            @TempDir Path directory) throws Exception {
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        HistoricalRediscoveryAtlas.AtlasReport report =
            atlas.run(historicalSubset());
        Map<String, HistoricalRediscoveryAtlas.CaseResult> cases =
            atlasById(report);

        assertTrue(cases.get("difference-of-squares-powers")
            .production().oracle().reachable());
        assertTrue(cases.get("complete-square")
            .curatedControl().oracle().reachable());
        assertTrue(cases.get("sophie-germain")
            .genericBridge().guided().reached());

        HistoricalRediscoveryAtlas.CaseResult policy =
            cases.get("distribution-fitness-valley-control");
        assertTrue(policy.production().oracle().reachable());
        assertFalse(policy.production().scalar().reached(), policy.toString());
        assertTrue(policy.production().diversity().reached(), policy.toString());
        assertTrue(policy.production().guided().reached(), policy.toString());

        assertEquals(PrimaryStatus.NEGATIVE_CONTROL_CONFIRMED,
            cases.get("inconsistent-near-miss").status());
        assertEquals(AssessmentDecision.USEFUL_DIAGNOSTIC_STEP,
            report.assessment().decision());
        assertEquals(report.toJson(), report.toJson());
        assertTrue(report.toJson().startsWith(
            "{\"schema\":\"regelsuche.historical-rediscovery-atlas/v1\""));
        assertTrue(report.toMarkdown().contains(
            "Historical rediscovery and reachability atlas"));

        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            atlas.write(directory, report);
        assertEquals(report.toJson(),
            Files.readString(artifacts.json(), StandardCharsets.UTF_8));
        assertEquals(report.toMarkdown(),
            Files.readString(artifacts.markdown(), StandardCharsets.UTF_8));
    }

    private RecognitionSummary runRecognitionCorpus(
        List<HistoricalRediscoveryCorpus.Case> corpus,
        RecognitionProfile profile
    ) {
        List<RecognitionResult> results = new ArrayList<>();
        for (HistoricalRediscoveryCorpus.Case benchmarkCase : corpus) {
            RecognitionDerivation derivation = derive(
                benchmarkCase.source(),
                benchmarkCase.target(),
                profile,
                benchmarkCase.oracleMaxDepth());
            results.add(new RecognitionResult(
                benchmarkCase.id(),
                derivation.found(),
                derivation.rules().size(),
                derivation.visitedStates()));
        }
        return new RecognitionSummary(results);
    }

    private RecognitionDerivation derive(
        String source,
        String target,
        RecognitionProfile profile,
        int maxDepth
    ) {
        AstRewriteTransformationEngine engine =
            new AstRewriteTransformationEngine(curatedRules(profile), 12, 100);
        String normalizedSource = format(source);
        String normalizedTarget = format(target);
        ArrayDeque<RecognitionNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new RecognitionNode(normalizedSource, List.of()));
        visited.add(normalizedSource);

        while (!queue.isEmpty() && visited.size() <= MAX_RECOGNITION_STATES) {
            RecognitionNode current = queue.removeFirst();
            if (current.expression().equals(normalizedTarget)) {
                return new RecognitionDerivation(
                    true, current.rules(), visited.size());
            }
            if (current.rules().size() >= maxDepth) {
                continue;
            }
            for (Transformation transformation :
                    engine.transform(current.expression())) {
                String next = transformation.transformedExpression();
                if (!visited.add(next)) {
                    continue;
                }
                List<String> rules = new ArrayList<>(current.rules());
                rules.add(transformation.rule());
                queue.addLast(new RecognitionNode(next, List.copyOf(rules)));
            }
        }
        return new RecognitionDerivation(false, List.of(), visited.size());
    }

    private List<RewriteRule> curatedRules(RecognitionProfile profile) {
        PatternExpr value = PatternExpr.var("V");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr b = PatternExpr.var("B");
        PatternExpr x = PatternExpr.var("X");
        PatternExpr y = PatternExpr.var("Y");
        return List.of(
            new PatternRewriteRule(
                "product-to-square",
                PatternExpr.op(MUL, value, value),
                PatternExpr.op(POW, value, PatternExpr.num(2))),
            completeSquare(profile),
            new PatternRewriteRule(
                "difference-of-squares",
                PatternExpr.op(SUB,
                    PatternExpr.op(POW, a, PatternExpr.num(2)),
                    PatternExpr.op(POW, b, PatternExpr.num(2))),
                PatternExpr.op(MUL,
                    PatternExpr.op(SUB, a, b),
                    PatternExpr.op(ADD, a, b))),
            new PatternRewriteRule(
                "factor-common-left",
                PatternExpr.op(ADD,
                    PatternExpr.op(MUL, a, x),
                    PatternExpr.op(MUL, a, y)),
                PatternExpr.op(MUL, a, PatternExpr.op(ADD, x, y))),
            new PatternRewriteRule(
                "factor-common-right",
                PatternExpr.op(ADD,
                    PatternExpr.op(MUL, x, a),
                    PatternExpr.op(MUL, y, a)),
                PatternExpr.op(MUL, PatternExpr.op(ADD, x, y), a)));
    }

    private PatternRewriteRule completeSquare(RecognitionProfile profile) {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr source = PatternExpr.op(
            ADD,
            PatternExpr.op(
                ADD,
                PatternExpr.op(POW, x, PatternExpr.num(2)),
                PatternExpr.op(MUL,
                    PatternExpr.op(MUL, PatternExpr.num(2), x), a)),
            PatternExpr.op(POW, a, PatternExpr.num(2)));
        PatternExpr target = PatternExpr.op(
            POW,
            PatternExpr.op(ADD, x, a),
            PatternExpr.num(2));
        return new PatternRewriteRule(
            "complete-square", source, target, profile);
    }

    private void assertRecognitionSuperset(
        RecognitionSummary broader,
        RecognitionSummary narrower
    ) {
        for (RecognitionResult narrow : narrower.results()) {
            if (!narrow.solved()) {
                continue;
            }
            assertTrue(broader.result(narrow.id()).solved(),
                "broader recognition lost " + narrow.id()
                    + "; narrower=" + narrower.describe()
                    + "; broader=" + broader.describe());
        }
    }

    private void assertRecognitionCase(
        RecognitionSummary summary,
        String id,
        boolean expected
    ) {
        if (expected) {
            assertTrue(summary.result(id).solved(), summary.describe());
        } else {
            assertFalse(summary.result(id).solved(), summary.describe());
        }
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static TransformationEngine graph(
        Map<String, List<Transformation>> transitions
    ) {
        return expression -> transitions.getOrDefault(expression, List.of());
    }

    private static Transformation edge(String rule, String target) {
        return new Transformation(rule, target);
    }

    private static Transformation transformation(
        String rule,
        String target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta
    ) {
        return new Transformation(
            rule,
            target,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            true,
            rule + ":root");
    }

    private static boolean containsExpression(
        List<SearchState> states,
        String expression
    ) {
        return states.stream().anyMatch(state ->
            state.expression().equals(expression));
    }

    private static String minimalCorpusCase(
        String relation,
        String role,
        String maxDepth
    ) {
        return """
            {
              "schema":"regelsuche.historical-rediscovery-corpus/v1",
              "evidenceStatus":"FROZEN_DIAGNOSTIC_CORPUS",
              "inventoryRevision":"test/v1",
              "claimBoundary":"bounded",
              "cases":[{
                "id":"case",
                "family":"TEST",
                "source":"x",
                "target":"y",
                "relation":"%s",
                "role":"%s",
                "diagnosticPurpose":"CONTROL",
                "provenance":"TEST_FIXTURE",
                "targetRelation":"SYNTAX_EXACT",
                "oracleMaxDepth":%s,
                "oracleMaxVisitedStates":2,
                "searchMaxDepth":1,
                "searchMaxVisitedStates":2,
                "maxCandidatesPerState":1,
                "maxExpandingSteps":1,
                "beamWidth":1
              }]
            }
            """.formatted(relation, role, maxDepth);
    }

    private static Corpus historicalSubset() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        Set<String> selected = Set.of(
            "complete-square",
            "expand-binomial-square",
            "difference-of-squares",
            "reverse-difference-of-squares",
            "difference-of-squares-powers",
            "sophie-germain",
            "distribution-fitness-valley-control",
            "inconsistent-near-miss");
        List<HistoricalRediscoveryCorpus.Case> cases = full.cases().stream()
            .filter(value -> selected.contains(value.id()))
            .toList();
        assertEquals(selected.size(), cases.size());
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            cases);
    }

    private static Map<String, HistoricalRediscoveryAtlas.CaseResult> atlasById(
        HistoricalRediscoveryAtlas.AtlasReport report
    ) {
        return report.cases().stream().collect(Collectors.toMap(
            result -> result.benchmarkCase().id(),
            Function.identity()));
    }

    private static Map<String, CaseResult> byId(FrontierReport report) {
        return report.cases().stream()
            .collect(Collectors.toMap(CaseResult::id, Function.identity()));
    }

    private static List<FrontierCase> referenceCases() {
        SearchHeuristic oneCandidate = new SearchHeuristic(1, 8, 1, 2, 1, 8);
        SearchHeuristic openBudget = new SearchHeuristic(4, 50, 1, 10, 50, 50);
        SearchHeuristic oneLevel = new SearchHeuristic(1, 50, 1, 10, 50, 50);
        String root = "(x + 0) * (a + b)";
        String distributed = "(x + 0) * a + (x + 0) * b";
        List<RewriteRule> addZeroOnly = AstRewriteTransformationEngine
            .defaultRules().stream()
            .filter(rule -> rule.id().equals("ast_add_zero_right"))
            .toList();

        return List.of(
            new FrontierCase(
                "real-distribution-guided-only", root,
                SearchTarget.valueEquivalent(distributed),
                new AstRewriteTransformationEngine(), oneCandidate,
                ConnectivityExpectation.CONNECTED),
            new FrontierCase(
                "distribution-operator-removed", root,
                SearchTarget.valueEquivalent(distributed),
                new AstRewriteTransformationEngine(addZeroOnly), openBudget,
                ConnectivityExpectation.MISSING_OPERATOR),
            new FrontierCase(
                "syntax-target-depth-limited", root,
                SearchTarget.syntaxExact("x * a + x * b"),
                new AstRewriteTransformationEngine(), oneLevel,
                ConnectivityExpectation.CONNECTED),
            new FrontierCase(
                "no-outgoing-transformations", "x",
                SearchTarget.valueEquivalent("y"),
                new AstRewriteTransformationEngine(List.of()), openBudget,
                ConnectivityExpectation.CONNECTED));
    }

    private enum RecognitionLayer {
        EXACT(RecognitionProfile.exact()),
        AC(RecognitionProfile.arithmeticAc()),
        ALGEBRAIC_AC(RecognitionProfile.algebraicAc());

        private final RecognitionProfile profile;

        RecognitionLayer(RecognitionProfile profile) {
            this.profile = profile;
        }
    }

    private record RecognitionNode(String expression, List<String> rules) {
    }

    private record RecognitionDerivation(
        boolean found,
        List<String> rules,
        int visitedStates
    ) {
    }

    private record RecognitionResult(
        String id,
        boolean solved,
        int pathLength,
        int visitedStates
    ) {
    }

    private record RecognitionSummary(List<RecognitionResult> results) {
        private int solved() {
            return (int) results.stream()
                .filter(RecognitionResult::solved)
                .count();
        }

        private RecognitionResult result(String id) {
            return results.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        }

        private String describe() {
            return "solved=" + solved() + "/" + results.size()
                + ", results=" + results;
        }
    }
}
