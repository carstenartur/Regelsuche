package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleCandidateMinerFormationRoutingTest {
    @Test
    void singleCandidateIsObservedExactlyOnceAfterFormation() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            observer);
        SuccessfulTransformationPath path = path(
            "path:single",
            "x + 0",
            "x",
            "add_zero");

        RuleCandidate candidate = miner
            .mineFromSinglePathForValidatedSchema(path)
            .orElseThrow();

        assertEquals(1, observer.calls.size());
        assertEquals(candidate, observer.calls.getFirst().candidate());
        assertEquals(
            List.of("add_zero"),
            observer.calls.getFirst().evidence().appliedRuleIds());
        assertEquals(
            List.of("path:single"),
            observer.calls.getFirst().evidence().sourceProvenance());
        assertEquals(
            List.of("test-equivalence"),
            observer.calls.getFirst().evidence().validationEvidence());
    }

    @Test
    void bulkSinglePathMiningRoutesDeduplicatedCandidateOnce() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            observer);
        SuccessfulTransformationPath first = path(
            "path:first",
            "x + 0",
            "x",
            "add_zero");
        SuccessfulTransformationPath second = path(
            "path:second",
            "y + 0",
            "y",
            "neutral_addition");

        List<RuleCandidate> candidates =
            miner.mineFromSinglePathForValidatedSchema(
                List.of(first, second));

        assertEquals(1, candidates.size());
        assertEquals(1, observer.calls.size());
        assertEquals(
            candidates.getFirst(),
            observer.calls.getFirst().candidate());
        assertEquals(
            List.of("add_zero", "neutral_addition"),
            observer.calls.getFirst().evidence().appliedRuleIds());
        assertEquals(
            List.of("path:first", "path:second"),
            observer.calls.getFirst().evidence().sourceProvenance());
        assertEquals(
            List.of("test-equivalence"),
            observer.calls.getFirst().evidence().validationEvidence());
    }

    @Test
    void bulkSinglePathMiningRejectsHashCollisionBeforeObservation() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = collisionHashMiner(observer);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> miner.mineFromSinglePathForValidatedSchema(List.of(
                path(
                    "path:additive",
                    "x + 0",
                    "x",
                    "add_zero"),
                path(
                    "path:multiplicative",
                    "x * 1",
                    "x",
                    "multiply_one"))));

        assertTrue(failure.getMessage().contains("collision"));
        assertTrue(observer.calls.isEmpty());
    }

    @Test
    void clusteredMiningObservesEachReturnedCandidateOnce() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            observer);
        SuccessfulTransformationPath first = path(
            "path:cluster-first",
            "(x + 1) ^ 2",
            "x ^ 2 + 2 * x + 1",
            "expand_square_one");
        SuccessfulTransformationPath second = path(
            "path:cluster-second",
            "(x + 2) ^ 2",
            "x ^ 2 + 4 * x + 4",
            "expand_square_two");
        SuccessfulTransformationPath third = path(
            "path:cluster-third",
            "(x + 3) ^ 2",
            "x ^ 2 + 6 * x + 9",
            "expand_square_three");

        List<RuleCandidate> candidates = miner.mine(
            List.of(first, second, third));

        assertEquals(1, candidates.size());
        assertEquals(1, observer.calls.size());
        assertEquals(
            candidates.getFirst(),
            observer.calls.getFirst().candidate());
        assertEquals(
            List.of(
                "path:cluster-first",
                "path:cluster-second",
                "path:cluster-third"),
            observer.calls.getFirst().evidence().sourceProvenance());
    }

    @Test
    void clusteredMiningRejectsHashCollisionBeforeObservation() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = collisionHashMiner(observer);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> miner.mine(List.of(
                path(
                    "path:add-x",
                    "x + 0",
                    "x",
                    "add_zero_x"),
                path(
                    "path:add-y",
                    "y + 0",
                    "y",
                    "add_zero_y"),
                path(
                    "path:add-z",
                    "z + 0",
                    "z",
                    "add_zero_z"),
                path(
                    "path:square-one",
                    "(x + 1) ^ 2",
                    "x ^ 2 + 2 * x + 1",
                    "expand_square_one"),
                path(
                    "path:square-two",
                    "(x + 2) ^ 2",
                    "x ^ 2 + 4 * x + 4",
                    "expand_square_two"),
                path(
                    "path:square-three",
                    "(x + 3) ^ 2",
                    "x ^ 2 + 6 * x + 9",
                    "expand_square_three"))));

        assertTrue(failure.getMessage().contains("collision"));
        assertTrue(observer.calls.isEmpty());
    }

    @Test
    void defaultNoOpDoesNotImposeUnusedEvidenceValidation() {
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true);

        assertTrue(miner.mineFromSinglePathForValidatedSchema(path(
            "path:no-op",
            "x + 0",
            "x",
            "")).isPresent());
    }

    @Test
    void configuredObserverRejectsBlankAppliedRuleEvidence() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            observer);

        assertThrows(
            IllegalArgumentException.class,
            () -> miner.mineFromSinglePathForValidatedSchema(path(
                "path:blank-rule",
                "x + 0",
                "x",
                "")));
        assertTrue(observer.calls.isEmpty());
    }

    @Test
    void configuredObserverFailureIsFailClosed() {
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            (candidate, evidence) -> {
                throw new IllegalStateException("observer rejected evidence");
            });

        assertThrows(
            IllegalStateException.class,
            () -> miner.mineFromSinglePathForValidatedSchema(path(
                "path:observer-failure",
                "x + 0",
                "x",
                "add_zero")));
    }

    @Test
    void unverifiedSinglePathDoesNotReachTheObserver() {
        RecordingObserver observer = new RecordingObserver();
        RuleCandidateMiner miner = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            observer);
        SuccessfulTransformationPath rejected =
            new SuccessfulTransformationPath(
                "path:rejected",
                "x + 0",
                "x",
                List.of("x + 0", "x"),
                List.of("add_zero"),
                new ExpressionScore(10, 4, 1, 2, 0),
                new ExpressionScore(1, 1, 0, 1, 0),
                false,
                "not verified",
                Map.of(),
                List.of());

        assertTrue(
            miner.mineFromSinglePathForValidatedSchema(rejected).isEmpty());
        assertTrue(observer.calls.isEmpty());
    }

    private RuleCandidateMiner collisionHashMiner(
        RecordingObserver observer
    ) {
        return new RuleCandidateMiner(
            new KnownRuleRepository(),
            (left, right) -> true,
            observer,
            (left, right) -> "forced-collision");
    }

    private SuccessfulTransformationPath path(
        String id,
        String source,
        String target,
        String rule
    ) {
        return new SuccessfulTransformationPath(
            id,
            source,
            target,
            List.of(source, target),
            List.of(rule),
            new ExpressionScore(10, 4, 1, 2, 0),
            new ExpressionScore(1, 1, 0, 1, 0),
            true,
            "test-equivalence",
            Map.of(),
            List.of());
    }

    private static final class RecordingObserver
            implements RuleCandidateFormationObserver {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public void onCandidateFormed(
            RuleCandidate candidate,
            Evidence evidence
        ) {
            calls.add(new Call(candidate, evidence));
        }
    }

    private record Call(
        RuleCandidate candidate,
        RuleCandidateFormationObserver.Evidence evidence
    ) {
    }
}
