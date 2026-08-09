package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.CapabilityFrontierExperiment.CaseResult;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.ConnectivityExpectation;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.FrontierCase;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.FrontierOutcome;
import de.regelsuche.benchmark.CapabilityFrontierExperiment.FrontierReport;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityFrontierExperimentTest {
    private final CapabilityFrontierExperiment experiment = new CapabilityFrontierExperiment();

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
    void machineReportIsDeterministicCompleteAndWritable(@TempDir Path directory) throws Exception {
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
        List<RewriteRule> addZeroOnly = AstRewriteTransformationEngine.defaultRules().stream()
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
                ConnectivityExpectation.CONNECTED)
        );
    }
}
