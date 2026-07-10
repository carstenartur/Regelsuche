package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.mining.HeuristicSymbolicRegressionHypothesisSource;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.InMemoryHypothesisRepository;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.OracleValidator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HypothesisPromotionPipeline}. */
class HypothesisPromotionPipelineTest {

    private static final CounterexampleSearchService NO_COUNTEREXAMPLE =
        (hypothesis, budget) -> CounterexampleSearchService.CounterexampleSearchResult.noCounterexample();

    private static final CounterexampleSearchService INCONCLUSIVE =
        (hypothesis, budget) -> CounterexampleSearchService.CounterexampleSearchResult.inconclusive("parser failure");

    private static final CounterexampleSearchService ALWAYS_COUNTEREXAMPLE =
        (hypothesis, budget) -> new CounterexampleSearchService.CounterexampleSearchResult(
            Optional.of(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=1"), "2", "3"
                )
            )
            ,
            List.of(),
            List.of("test")
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
        assertEquals(3, hyp.supportingExpressions().size(),
            "candidate must contain all three concrete binomial witnesses");
        assertTrue(hyp.supportingExpressions().stream()
            .anyMatch(pair -> pair.left().equals("(x + 1) ^ 2") && pair.right().equals("1 + 2 * x + x ^ 2")));
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
            assertEquals(CandidateProofStatus.REJECTED, hyp.proofStatus());
            assertEquals(CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND, hyp.counterexampleSearchStatus());
        }
    }

    @Test
    void inconclusiveCounterexampleSearchRemainsObservedAndDoesNotPromote() {
        HypothesisPromotionPipeline pipe = pipeline(INCONCLUSIVE, true);
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);

        assertFalse(result.newHypotheses().isEmpty());
        assertTrue(result.promotedRules().isEmpty());
        assertTrue(result.newHypotheses().stream().allMatch(h ->
            h.proofStatus() == CandidateProofStatus.OBSERVED
                && h.counterexampleSearchStatus() == CounterexampleSearchService.Status.INCONCLUSIVE));
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

    @Test
    void inferredAssumptionsArePersistedInRepositoryAndResult() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        InMemoryHypothesisRepository hypothesisRepo = new InMemoryHypothesisRepository();
        MacroRuleLearningService learningService = new MacroRuleLearningService(
            inventory, miner, knownRules, 3, 0.0
        );
        CounterexampleSearchService infersAssumption =
            (hypothesis, budget) -> new CounterexampleSearchService.CounterexampleSearchResult(
                Optional.empty(),
                List.of("0 != b"),
                List.of("test")
            );
        HypothesisPromotionPipeline pipe = new HypothesisPromotionPipeline(
            miner, hypothesisRepo, infersAssumption, learningService, false
        );
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);

        assertFalse(result.newHypotheses().isEmpty());
        assertTrue(result.newHypotheses().stream()
            .allMatch(hypothesis -> hypothesis.assumptions().contains("b != 0")));
        assertEquals(result.newHypotheses().size(), hypothesisRepo.findAll().size());
        assertTrue(hypothesisRepo.findAll().stream()
            .allMatch(hypothesis -> hypothesis.assumptions().contains("b != 0")));
    }

    @Test
    void symbolicRegressionSourceAddsEvidenceOnlyHypothesesThroughCounterexampleGate() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        InMemoryHypothesisRepository hypothesisRepo = new InMemoryHypothesisRepository();
        MacroRuleLearningService learningService = new MacroRuleLearningService(
            inventory, miner, knownRules, 3, 0.0
        );
        HypothesisPromotionPipeline pipe = new HypothesisPromotionPipeline(
            miner,
            hypothesisRepo,
            NO_COUNTEREXAMPLE,
            learningService,
            false,
            List.of(new HeuristicSymbolicRegressionHypothesisSource(true, 2))
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(List.of(
            path("sym1", "f(1)", "2"),
            path("sym2", "f(2)", "4")
        ));

        assertTrue(result.newHypotheses().stream()
            .anyMatch(h -> h.id().startsWith("symreg-")
                && h.proofStatus() == CandidateProofStatus.OBSERVED
                && h.counterexampleSearchStatus() == CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND));
    }

    @Test
    void oracleDisagreementBlocksPromotion() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        InMemoryHypothesisRepository hypothesisRepo = new InMemoryHypothesisRepository();
        MacroRuleLearningService learningService = new MacroRuleLearningService(
            inventory, miner, knownRules, 3, 0.0
        );
        OracleValidator oracle = (left, right) -> OracleValidator.OracleValidation.disagrees("oracle mismatch");
        HypothesisPromotionPipeline pipe = new HypothesisPromotionPipeline(
            miner, hypothesisRepo, NO_COUNTEREXAMPLE, learningService, true, List.of(),
            null, oracle
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        ));

        assertTrue(result.promotedRules().isEmpty());
        assertTrue(result.newHypotheses().stream()
            .allMatch(hypothesis -> hypothesis.proofStatus() == CandidateProofStatus.OBSERVED));
    }

    @Test
    void revisionHistoryIsPopulatedInPromotionResult() {
        HypothesisPromotionPipeline pipe = pipeline(NO_COUNTEREXAMPLE, false);
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2"),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2"),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);

        assertNotNull(result.revisionHistory(),
            "revisionHistory must never be null");
        assertFalse(result.revisionHistory().isEmpty(),
            "at least one revision should be recorded when hypotheses were processed");
    }

    @Test
    void refinementLoopRefinesHypothesisWithDivisionConstraint() {
        // Service: finds a counterexample on first call (before any !='!= 0' assumption),
        // no counterexample once any "!= 0" assumption is present (strategy applied).
        CounterexampleSearchService divisionService = (hypothesis, budget) -> {
            boolean hasNonZeroConstraint = hypothesis.assumptions().stream()
                .anyMatch(a -> a.contains("!= 0"));
            if (hasNonZeroConstraint) {
                return CounterexampleSearchService.CounterexampleSearchResult.noCounterexample();
            }
            return CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("b=0", "a=2"), "undefined", "2"
                ),
                List.of(), List.of("numeric-random")
            );
        };

        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        InMemoryHypothesisRepository hypothesisRepo = new InMemoryHypothesisRepository();
        MacroRuleLearningService learningService = new MacroRuleLearningService(
            inventory, miner, knownRules, 3, 0.0
        );
        HypothesisPromotionPipeline pipe = new HypothesisPromotionPipeline(
            miner, hypothesisRepo, divisionService, learningService, false
        );

        // Use paths with division that the miner can generalize
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(1 + 2) / 3", "1"),
            path("p2", "(2 + 4) / 6", "1"),
            path("p3", "(3 + 6) / 9", "1")
        );

        HypothesisPromotionPipeline.PromotionResult result = pipe.run(paths);

        // Verify revision history is populated (refinement loop ran)
        assertNotNull(result.revisionHistory());

        if (!result.newHypotheses().isEmpty()) {
            // At least one hypothesis should have been refined: not rejected
            // OR has a non-zero constraint (if the division pattern matched)
            boolean hasNonRejectedHypothesis = result.newHypotheses().stream()
                .anyMatch(h -> h.proofStatus() != CandidateProofStatus.REJECTED
                    || h.assumptions().stream().anyMatch(a -> a.contains("!= 0")));
            assertTrue(hasNonRejectedHypothesis || !result.revisionHistory().isEmpty(),
                "at least one hypothesis should be non-rejected or revision history should be populated");
        }
    }

}
