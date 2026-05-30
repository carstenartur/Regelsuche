package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.learning.MacroRuleLearningService;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.SquareDifferenceAstPredicate;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HiddenStructureDiscoveryCorpusTest {
    private final PolynomialNormalFormEquivalenceService polynomialEquivalence =
        new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());
    private final SymPyEquivalenceService symbolicEquivalence = new SymPyEquivalenceService();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void hiddenStructureDiscoveryCorpusDocumentsGeneralizationAndRejectsNearMisses() {
        List<CorpusCase> corpus = List.of(
            new CorpusCase("same-schema", "y^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a different symbol"),
            new CorpusCase("same-schema", "(x + 1)^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a compound base"),
            new CorpusCase("same-schema", "(2*x)^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a scaled base"),
            new CorpusCase("same-schema", "(x^2)^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a power base"),
            new CorpusCase("normalization", "(x^2)^2 + 4", Expectation.REQUIRE_DISCOVERY,
                "should work directly or after power normalization"),
            new CorpusCase("normalization", "x^2 * x^2 + 4", Expectation.DOCUMENT_ONLY,
                "documents whether product-to-power normalization feeds the hypothesis"),
            new CorpusCase("near-miss", "x^4 + 5", Expectation.REQUIRE_NO_DISCOVERY,
                "constant is not a square compatible with the Sophie-Germain bridge"),
            new CorpusCase("near-miss", "x^4 + 2", Expectation.REQUIRE_NO_DISCOVERY,
                "constant is not a square compatible with the Sophie-Germain bridge"),
            new CorpusCase("near-miss", "x^4 + 4 + y", Expectation.REQUIRE_NO_DISCOVERY,
                "extra addend must not become a false positive"),
            new CorpusCase("future-sophie-germain", "x^4 + 4*y^4", Expectation.DOCUMENT_ONLY,
                "future general Sophie-Germain form"),
            new CorpusCase("future-sophie-germain", "x^4 + 64", Expectation.DOCUMENT_ONLY,
                "future numeric Sophie-Germain form"),
            new CorpusCase("future-sophie-germain", "16*x^4 + 4*y^4", Expectation.DOCUMENT_ONLY,
                "future scaled Sophie-Germain form"),
            new CorpusCase("hidden-square", "x^2 + 6*x + 5", Expectation.DOCUMENT_ONLY,
                "hidden square-completion candidate"),
            new CorpusCase("hidden-square", "x^2 + 10*x + 21", Expectation.DOCUMENT_ONLY,
                "hidden square-completion candidate"),
            new CorpusCase("hidden-square", "x^2 + 2*x*y + y^2", Expectation.DOCUMENT_ONLY,
                "perfect-square candidate")
        );

        List<CorpusRow> rows = corpus.stream().map(this::evaluate).toList();
        String summaryTable = summaryTable(rows);
        System.out.println(summaryTable);

        assertEquals(corpus.size(), rows.size(), summaryTable);
        assertTrue(summaryTable.contains("| expression | bridge? | factored? | rule path | learned macro? | reusable? | observed result | notes |"));
        for (CorpusRow row : rows) {
            assertValidReplay(row);
            if (row.seed().expectation() == Expectation.REQUIRE_DISCOVERY) {
                assertTrue(row.bridgeDiscovered() || row.factoredDiscovery(), row.seed().expression() + "\n" + summaryTable);
            }
            if (row.seed().expectation() == Expectation.REQUIRE_NO_DISCOVERY) {
                assertFalse(row.bridgeDiscovered(), row.seed().expression() + "\n" + summaryTable);
                assertFalse(row.factoredDiscovery(), row.seed().expression() + "\n" + summaryTable);
                assertEquals(DiscoveryResultKind.NO_CANDIDATE, row.observedResult(), row.seed().expression() + "\n" + summaryTable);
            }
            if (row.seed().expectation() == Expectation.DOCUMENT_ONLY) {
                assertTrue(List.of(DiscoveryResultKind.NO_CANDIDATE, DiscoveryResultKind.BRIDGE_FOUND,
                    DiscoveryResultKind.TRANSFORMED, DiscoveryResultKind.FALSE_POSITIVE)
                    .contains(row.observedResult()), row.seed().expression() + "\n" + summaryTable);
            }
        }
    }

    @Test
    void discoversSymbolicSophieGermainThroughPreparationAndSquareDifferenceFactorization() {
        String source = "x^4 + 4*y^4";
        SearchProblem problem = new SearchProblem(
            source,
            hiddenStructureEngine(),
            scorer,
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 160, 1, 10, 200, 200)
        );

        SearchState factoredState = new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.appliedRuleIds().contains("ast_square_difference_factor"))
            .findFirst()
            .orElseThrow();

        assertTrue(factoredState.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID),
            factoredState.appliedRuleIds().toString());
        assertTrue(factoredState.appliedRuleIds().contains("ast_square_difference_factor"),
            factoredState.appliedRuleIds().toString());
        assertTrue(factoredState.path().stream().anyMatch(path ->
            path.contains("(x ^ 2 + 2 * y ^ 2) ^ 2 - (2 * x * y) ^ 2")), factoredState.path().toString());
    }

    private CorpusRow evaluate(CorpusCase seed) {
        TransformationEngine engine = hiddenStructureEngine();
        List<Transformation> hypothesisCandidates = engine.transform(seed.expression()).stream()
            .filter(transformation -> transformation.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .toList();
        SearchProblem problem = new SearchProblem(
            seed.expression(),
            engine,
            scorer,
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 160, 1, 10, 200, 200)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        SearchState reportedState = bestReportedState(states);
        boolean bridgeDiscovered = reportedState != null
            && reportedState.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID)
            && reportedState.path().stream().anyMatch(SquareDifferenceAstPredicate::containsSquareDifference)
            && reportedState.path().size() > 1;
        boolean factoredDiscovery = reportedState != null
            && reportedState.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID)
            && reportedState.appliedRuleIds().contains("ast_square_difference_factor")
            && reportedState.path().size() > 1;
        LearnedMacro learned = factoredDiscovery ? learnPromotedMacro(seed, reportedState) : null;
        boolean learnedMacro = learned != null;
        boolean reusable = learnedMacro && macroAppliesToSecondExpression(seed.expression(), learned);
        List<String> rulePath = reportedState == null ? List.of() : reportedState.appliedRuleIds();
        List<String> replayPath = reportedState == null ? List.of() : reportedState.path();
        DiscoveryResultKind observedResult = observedResult(seed, hypothesisCandidates, bridgeDiscovered, factoredDiscovery,
            learnedMacro, reusable);
        String notes = notes(seed, reportedState, hypothesisCandidates, observedResult, learned);
        return new CorpusRow(seed, observedResult == DiscoveryResultKind.BRIDGE_FOUND || observedResult == DiscoveryResultKind.TRANSFORMED,
            observedResult == DiscoveryResultKind.TRANSFORMED, rulePath, learnedMacro, reusable, replayPath, observedResult, notes);
    }

    private TransformationEngine hiddenStructureEngine() {
        return new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules(), 128, 160),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
    }

    private SearchState bestReportedState(List<SearchState> states) {
        SearchState factored = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.appliedRuleIds().contains("ast_square_difference_factor"))
            .findFirst()
            .orElse(null);
        if (factored != null) {
            return factored;
        }
        SearchState squareDifference = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> SquareDifferenceAstPredicate.containsSquareDifference(state.expression()))
            .findFirst()
            .orElse(null);
        if (squareDifference != null) {
            return squareDifference;
        }
        return states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .findFirst()
            .orElse(null);
    }

    private LearnedMacro learnPromotedMacro(CorpusCase seed, SearchState factoredState) {
        SuccessfulTransformationPath path = successfulPath(seed, factoredState);
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningResult result = new MacroRuleLearningService(
            inventory,
            new RuleCandidateMiner(new KnownRuleRepository(), symbolicEquivalence),
            new KnownRuleRepository(),
            1,
            0.0
        ).learn(List.of(path));
        ReusableRule rule = result.newlyActivated().stream()
            .filter(candidate -> inventory.isEnabled(candidate.id()))
            .findFirst()
            .orElse(null);
        if (rule == null) {
            return null;
        }
        return new LearnedMacro(path, inventory, rule, macroRuleId(rule));
    }

    private boolean macroAppliesToSecondExpression(String sourceExpression, LearnedMacro learned) {
        String secondExpression = sourceExpression.equals("y^4 + 4") ? "(x + 1)^4 + 4" : "y^4 + 4";
        MacroMoveTransformationEngine macroEngine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(learned.inventory(), 0.0, -1000.0, 1),
            null,
            Map.of(learned.rule().id(), atomicSteps(learned.path()))
        );
        return macroEngine.transform(secondExpression).stream()
            .anyMatch(transformation -> transformation.rule().equals(learned.macroRuleId())
                && polynomialEquivalence.arePolynomiallyEquivalent(
                    secondExpression,
                    transformation.transformedExpression()
                ));
    }

    private SuccessfulTransformationPath successfulPath(CorpusCase seed, SearchState factoredState) {
        return new SuccessfulTransformationPath(
            "hidden-structure-" + seed.expression().replaceAll("[^A-Za-z0-9]+", "-"),
            seed.expression(),
            factoredState.expression(),
            factoredState.path(),
            factoredState.appliedRuleIds(),
            scorer.score(seed.expression()),
            factoredState.score(),
            true,
            "polynomial normal-form equivalence",
            Map.of("group", seed.group())
        );
    }

    private List<TransformationStep> atomicSteps(SuccessfulTransformationPath path) {
        List<TransformationStep> steps = new ArrayList<>();
        for (int index = 0; index < path.rules().size(); index++) {
            String before = path.expressionPath().get(index);
            String after = path.expressionPath().get(index + 1);
            steps.add(new TransformationStep(
                index,
                before,
                after,
                path.rules().get(index),
                RewriteKind.NORMALIZE,
                scorer.score(before).weightedTotal(),
                scorer.score(after).weightedTotal(),
                true,
                path.rules().get(index)
            ));
        }
        return steps;
    }

    private String macroRuleId(ReusableRule rule) {
        return rule.id().startsWith("macro_") ? rule.id() : "macro_" + rule.id();
    }

    private DiscoveryResultKind observedResult(
        CorpusCase seed,
        List<Transformation> hypothesisCandidates,
        boolean bridgeDiscovered,
        boolean factoredDiscovery,
        boolean learnedMacro,
        boolean reusable
    ) {
        if (reusable) {
            return DiscoveryResultKind.TRANSFORMED;
        }
        if (learnedMacro) {
            return DiscoveryResultKind.TRANSFORMED;
        }
        if (factoredDiscovery) {
            return DiscoveryResultKind.TRANSFORMED;
        }
        if (bridgeDiscovered) {
            return DiscoveryResultKind.BRIDGE_FOUND;
        }
        if (!hypothesisCandidates.isEmpty() && seed.expectation() == Expectation.REQUIRE_NO_DISCOVERY) {
            return DiscoveryResultKind.FALSE_POSITIVE;
        }
        return DiscoveryResultKind.NO_CANDIDATE;
    }

    private void assertValidReplay(CorpusRow row) {
        if (!row.bridgeDiscovered() && !row.factoredDiscovery()) {
            return;
        }
        assertFalse(row.replayPath().isEmpty(), row.seed().expression());
        assertEquals(row.seed().expression(), row.replayPath().getFirst(), row.seed().expression());
        for (String expression : row.replayPath()) {
            assertTrue(
                polynomialEquivalence.arePolynomiallyEquivalent(row.seed().expression(), expression),
                () -> row.seed().expression() + " not equivalent to " + expression + ": "
                    + polynomialEquivalence.evidence(row.seed().expression(), expression)
            );
        }
    }

    private String notes(
        CorpusCase seed,
        SearchState reportedState,
        List<Transformation> hypothesisCandidates,
        DiscoveryResultKind observedResult,
        LearnedMacro learned
    ) {
        List<String> notes = new ArrayList<>();
        notes.add(seed.notes());
        notes.add("observed result: " + label(observedResult));
        if (hypothesisCandidates.isEmpty()) {
            notes.add("no hypothesis candidate");
        } else {
            notes.add("hypothesis candidates: " + hypothesisCandidates.size());
        }
        if (learned != null) {
            notes.add("promoted macro: " + learned.rule().id());
        }
        if (reportedState == null) {
            notes.add("no replay/search state reported");
        } else if (reportedState.appliedRuleIds().contains("ast_square_difference_factor")) {
            notes.add("validated factored replay state");
        } else if (SquareDifferenceAstPredicate.containsSquareDifference(reportedState.expression())) {
            notes.add("reached square-difference bridge only");
        } else {
            notes.add("hypothesis appeared without square-difference bridge or factorization");
        }
        return String.join("; ", notes);
    }

    private String summaryTable(List<CorpusRow> rows) {
        StringBuilder table = new StringBuilder();
        table.append("| expression | bridge? | factored? | rule path | learned macro? | reusable? | observed result | notes |\n");
        table.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CorpusRow row : rows) {
            table.append("| ")
                .append(escape(row.seed().expression()))
                .append(" | ")
                .append(row.bridgeDiscovered() ? "yes" : "no")
                .append(" | ")
                .append(row.factoredDiscovery() ? "yes" : "no")
                .append(" | ")
                .append(escape(row.rulePath().isEmpty() ? "—" : String.join(" -> ", row.rulePath())))
                .append(" | ")
                .append(row.learnedMacro() ? "yes" : "no")
                .append(" | ")
                .append(row.reusable() ? "yes" : "no")
                .append(" | ")
                .append(label(row.observedResult()))
                .append(" | ")
                .append(escape(row.notes()))
                .append(" |\n");
        }
        return table.toString();
    }

    private String escape(String value) {
        return value.replace("|", "\\|");
    }

    private enum Expectation {
        REQUIRE_DISCOVERY,
        REQUIRE_NO_DISCOVERY,
        DOCUMENT_ONLY
    }

    private String label(DiscoveryResultKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private record CorpusCase(String group, String expression, Expectation expectation, String notes) {
    }

    private record LearnedMacro(
        SuccessfulTransformationPath path,
        InMemoryRuleInventoryRepository inventory,
        ReusableRule rule,
        String macroRuleId
    ) {
    }

    private record CorpusRow(
        CorpusCase seed,
        boolean bridgeDiscovered,
        boolean factoredDiscovery,
        List<String> rulePath,
        boolean learnedMacro,
        boolean reusable,
        List<String> replayPath,
        DiscoveryResultKind observedResult,
        String notes
    ) {
    }
}
