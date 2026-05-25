package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.learning.HypothesisPromotionPipeline;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.learning.MacroRuleLearningService;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.InMemoryHypothesisRepository;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.DiscoveryDemos;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Discovery integration tests: the system generalises several concrete
 * algebraic examples into reusable hypotheses / macro rules.
 *
 * <p>These tests are deterministic (no Spring, no Testcontainers) and
 * reproduce the "Discovery Demo" scenarios described in the issue:
 * <ul>
 *   <li>Binomial formula from (x+1)^2, (x+2)^2, (x+3)^2</li>
 *   <li>A simple identity pattern generalised over different constants</li>
 * </ul>
 */
class DiscoveryIntegrationTest {

    private static final CounterexampleSearchService NO_CEX = (l, r) -> Optional.empty();

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static SuccessfulTransformationPath path(String id, String left, String right) {
        ExpressionScore before = new ExpressionScore(left.length() + 5, 0, 0, 0, 0);
        ExpressionScore after = new ExpressionScore(right.length(), 0, 0, 0, 0);
        return new SuccessfulTransformationPath(
            id, left, right,
            List.of(left, right),
            List.of("expand_power_to_product", "distribute_multiplication", "combine_like_terms"),
            before, after, true, "SymPy", Map.of("variable", "x")
        );
    }

    private static HypothesisPromotionPipeline buildPipeline(
        InMemoryRuleInventoryRepository inventory
    ) {
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        InMemoryHypothesisRepository hypothesisRepo = new InMemoryHypothesisRepository();
        MacroRuleLearningService learningService = new MacroRuleLearningService(
            inventory, miner, knownRules, 3, 0.0
        );
        return new HypothesisPromotionPipeline(
            miner, hypothesisRepo, NO_CEX, learningService, true
        );
    }

    // ─── Binomial formula ─────────────────────────────────────────────────────

    @Test
    void binomialFormulaHypothesisEmergesFromThreeExamples() {
        // Given three concrete examples of the first binomial formula:
        //   (x+1)^2 = x^2 + 2x + 1
        //   (x+2)^2 = x^2 + 4x + 4
        //   (x+3)^2 = x^2 + 6x + 9
        // The system should mine the hypothesis (x+A)^2 = x^2 + 2Ax + A^2
        List<SuccessfulTransformationPath> paths = List.of(
            path("b1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("b2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("b3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        HypothesisPromotionPipeline pipeline = buildPipeline(inventory);
        HypothesisPromotionPipeline.PromotionResult result = pipeline.run(paths);

        assertFalse(result.newHypotheses().isEmpty(),
            "binomial formula hypothesis must emerge from 3 examples");

        HypothesisCandidate hypothesis = result.newHypotheses().getFirst();
        assertNotNull(hypothesis.id(), "hypothesis id must not be null");
        assertFalse(hypothesis.leftPattern().isBlank(), "left pattern must not be blank");
        assertFalse(hypothesis.rightPattern().isBlank(), "right pattern must not be blank");

        // Verify the parameter relations mention the abstracted variable A
        boolean hasAbstraction = hypothesis.parameterRelations().stream()
            .anyMatch(r -> r.contains("A") || r.contains("\u2208"));
        assertTrue(hasAbstraction,
            "hypothesis must contain parameter relation or expression placeholder: "
                + hypothesis.parameterRelations());
    }

    @Test
    void binomialFormulaIsPromotedToInventoryWithAutoPromote() {
        List<SuccessfulTransformationPath> paths = List.of(
            path("b1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("b2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("b3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        HypothesisPromotionPipeline pipeline = buildPipeline(inventory);
        pipeline.run(paths);

        assertFalse(inventory.findAll().isEmpty(),
            "after auto-promote with 3 examples the inventory must contain the learned rule");
    }

    // ─── Commutativity pattern (structural) ──────────────────────────────────

    @Test
    void commutativityPatternGeneralisesOverDifferentConstants() {
        // a + 1 ↔ 1 + a pattern with different integer constants
        List<SuccessfulTransformationPath> paths = List.of(
            path("c1", "x + 1", "1 + x"),
            path("c2", "x + 3", "3 + x"),
            path("c3", "x + 5", "5 + x")
        );

        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        HypothesisPromotionPipeline pipeline = buildPipeline(inventory);
        HypothesisPromotionPipeline.PromotionResult result = pipeline.run(paths);

        assertFalse(result.newHypotheses().isEmpty(),
            "commutativity pattern must be mined from 3 examples");
    }

    // ─── MacroRuleLearningService direct test ─────────────────────────────────

    @Test
    void learningServiceAccumulatesOccurrencesAcrossRuns() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroRuleLearningService service = new MacroRuleLearningService(
            inventory,
            new RuleCandidateMiner(new KnownRuleRepository()),
            new KnownRuleRepository(),
            3,
            0.0
        );

        List<SuccessfulTransformationPath> run1 = List.of(
            path("r1p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("r1p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("r1p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        MacroLearningResult result = service.learn(run1);
        assertFalse(result.touchedRules().isEmpty(),
            "learning service must produce at least one touched rule");
        assertTrue(result.touchedRules().getFirst().occurrenceCount() >= 3,
            "occurrence count must accumulate all 3 supporting paths");
    }

    @Test
    void rationalSimplificationDemoPromotesMacroRule() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningResult result = DiscoveryDemos.promoteRationalSimplification(inventory);

        assertFalse(result.touchedRules().isEmpty(),
            "rational simplification demo must mine/promote a reusable rule");
        assertFalse(inventory.findAll().isEmpty(),
            "promoted rational rule must be present in the inventory");
    }

    @Test
    void promotedRationalRuleShortensLaterSearchPath() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        DiscoveryDemos.promoteRationalSimplification(inventory);

        String root = "(x * x) / x";
        ExpressionScorer scorer = new ExpressionScorer();
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        SearchProblem atomicOnly = new SearchProblem(
            root,
            new AstRewriteTransformationEngine(),
            scorer,
            canonicalizer,
            new SearchHeuristic(1, 80, 1, 4, 80, 20)
        );
        boolean atomicFindsXAtDepthOne = new BestFirstSearchStrategy().search(atomicOnly).stream()
            .anyMatch(state -> state.expression().equals("x"));
        assertFalse(atomicFindsXAtDepthOne, "atomic-only search should not cancel in one edge");

        MacroMoveTransformationEngine macroEngine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(inventory)
        );
        SearchProblem withMacro = new SearchProblem(
            root,
            macroEngine,
            scorer,
            canonicalizer,
            new SearchHeuristic(1, 80, 1, 4, 80, 20)
        );
        assertTrue(new BestFirstSearchStrategy().search(withMacro).stream()
            .anyMatch(state -> state.expression().equals("x") && state.depth() == 1),
            "promoted rational macro should shorten later search to one edge");
    }

    @Test
    void geometricSeriesDemoCreatesStructuralHypothesisWithWitnesses() {
        HypothesisCandidate hypothesis = DiscoveryDemos.geometricSeriesHypothesis();

        assertEquals("hyp-geometric-series-structural-recurrence", hypothesis.id());
        assertEquals(3, hypothesis.supportingPaths().size());
        assertEquals(3, hypothesis.supportingExpressions().size());
        assertTrue(hypothesis.parameterRelations().stream()
            .anyMatch(relation -> relation.contains("S_(n+1)")));
        assertTrue(hypothesis.assumptions().stream()
            .anyMatch(assumption -> assumption.contains("closed form not derived yet")));
    }
}
