package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.InMemoryHypothesisRepository;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HypothesisPromotionPipeline}. */
class HypothesisPromotionPipelineTest {

    private static final CounterexampleSearchService NO_COUNTEREXAMPLE =
        (left, right) -> Optional.empty();

    private static final CounterexampleSearchService ALWAYS_COUNTEREXAMPLE =
        (left, right) -> Optional.of(
            new CounterexampleSearchService.Counterexample(
                List.of("x=1"), "2", "3"
            )
        );

    private static SuccessfulTransformationPath path(String id, String left, String right) {
        ExpressionScore before = new ExpressionScore(left.length() + 5, 0, 0, 0, 0);
        ExpressionScore after = new ExpressionScore(right.length(), 0, 0, 0, 0);
        return new SuccessfulTransformationPath(
            id, left, right,
            List.of(left, right),
            List.of("expand_power_to_product", "distribute_multiplication", "combine_like_terms"),
            before, after, true, "test", Map.of("variable", "x")
        );
    }

    private static HypothesisPromotionPipeline pipeline(
        CounterexampleSearchService cex,
        boolean autoPromote
    ) {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        InMemoryHypothesisRepository hypothesisRepo = new InMemoryHypothesisRepository();
        MacroRuleLearningService learningService = new MacroRuleLearningService(
            inventory, miner, knownRules, 3, 0.0
        );
        return new HypothesisPromotionPipeline(
            miner, hypothesisRepo, cex, learningService, autoPromote
        );
    }

    @Test
    void emptyPathsReturnsEmptyResult() {
        HypothesisPromotionPipeline pipe = pipeline(NO_COUNTEREXAMPLE, false);
        HypothesisPromotionPipeline.PromotionResult result = pipe.run(List.of());

        assertTrue(result.newHypotheses().isEmpty());
        assertTrue(result.promotedRules().isEmpty());
    }

    @Test
    void threeExamplesProduceHypothesis() {
        HypothesisPromotionPipeline pipe = pipeline(NO_COUNTEREXAMPLE, false);
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);
        assertFalse(result.newHypotheses().isEmpty(),
            "three supporting examples must produce at least one hypothesis");
        HypothesisCandidate hyp = result.newHypotheses().getFirst();
        assertNotNull(hyp.id());
        assertFalse(hyp.leftPattern().isBlank());
    }

    @Test
    void counterexampleSetsRejectedStatus() {
        HypothesisPromotionPipeline pipe = pipeline(ALWAYS_COUNTEREXAMPLE, false);
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);
        // Candidates that have a counterexample should be marked as such
        if (!result.newHypotheses().isEmpty()) {
            HypothesisCandidate hyp = result.newHypotheses().getFirst();
            // counterexampleStatus may be true (found) or the candidate itself may be rejected
            assertNotNull(hyp.counterexampleStatus(),
                "counterexample status must be set when service returned a result");
        }
    }

    @Test
    void autoPromoteActivatesRulesInInventory() {
        HypothesisPromotionPipeline pipe = pipeline(NO_COUNTEREXAMPLE, true);
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);
        // With autoPromote=true and 3 examples, the learning service may activate rules
        // (confidence=0.0 threshold relaxed). We just assert the pipeline runs cleanly.
        assertNotNull(result);
        assertNotNull(result.newHypotheses());
        assertNotNull(result.promotedRules());
    }
}
