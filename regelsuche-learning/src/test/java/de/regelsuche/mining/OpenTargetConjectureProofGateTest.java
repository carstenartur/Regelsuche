package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.CounterexampleEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureProofGateTest {

    @Test
    void emitsTargetFreeObligationOnlyAfterCompleteAcceptedEvaluation() {
        RecordingOracle oracle = new RecordingOracle(true, "matching normalized coefficients");
        OpenTargetConjectureProofGate gate = new OpenTargetConjectureProofGate(
            oracle, "recording-symbolic-oracle-v1");

        var report = gate.evaluate(conjecture(), acceptedEvaluation());

        assertEquals(EligibilityStatus.ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.SYMBOLICALLY_VERIFIED, report.proofStatus());
        assertTrue(report.proofObligationEmitted());
        assertFalse(report.obligation().targetProvided());
        assertEquals("A * B + A * C", report.obligation().leftExpression());
        assertEquals("A * (B + C)", report.obligation().rightExpression());
        assertEquals(List.of("A > 0", "B != 0"), report.obligation().assumptions());
        assertTrue(report.obligation().obligationHash().startsWith("sha256:"));
        assertTrue(report.solverObligationHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(report.solverResultHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals("APPROXIMATED", report.solverTranslationStatus());
        assertEquals(1, oracle.calls());
        assertEquals("NOT_EVALUATED", report.formalProofStatus());
        assertTrue(report.blockers().isEmpty());
        assertEquals(report.toCanonicalJson(), report.toCanonicalJson());
        assertTrue(report.toCanonicalJson().contains("\"targetProvided\":false"));
        assertTrue(report.toCanonicalJson().contains("\"solverTranslationStatus\":\"APPROXIMATED\""));
        assertFalse(report.toCanonicalJson().contains("targetExpression"));
    }

    @Test
    void rejectedCandidateCannotEmitAnObligationOrCallTheOracle() {
        RecordingOracle oracle = new RecordingOracle(true, "should not run");
        OpenTargetConjectureProofGate gate = new OpenTargetConjectureProofGate(
            oracle, "recording-symbolic-oracle-v1");
        EvaluationReport rejected = evaluation(EvaluationStatus.REJECTED, List.of("counterexample found"));

        var report = gate.evaluate(conjecture(), rejected);

        assertEquals(EligibilityStatus.NOT_ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.NOT_RUN, report.proofStatus());
        assertFalse(report.proofObligationEmitted());
        assertNull(report.obligation());
        assertEquals("", report.solverObligationHash());
        assertEquals("", report.solverResultHash());
        assertEquals("NOT_EMITTED", report.solverTranslationStatus());
        assertEquals(0, oracle.calls());
        assertTrue(report.blockers().contains("candidate is not accepted for proof"));
        assertTrue(report.blockers().contains("candidate evaluation contains blockers"));
        assertTrue(report.toCanonicalJson().contains("\"obligation\":null"));
    }

    @Test
    void distinguishesRefutationFromInconclusiveOracleResult() {
        var refuted = new OpenTargetConjectureProofGate(
            new RecordingOracle(false, "not equivalent under deterministic samples"),
            "refuting-oracle-v1").evaluate(conjecture(), acceptedEvaluation());
        var inconclusive = new OpenTargetConjectureProofGate(
            new RecordingOracle(false, "no equivalence evidence found"),
            "inconclusive-oracle-v1").evaluate(conjecture(), acceptedEvaluation());

        assertEquals(ProofStatus.REFUTED, refuted.proofStatus());
        assertEquals(List.of("oracle refuted the conjecture"), refuted.blockers());
        assertEquals(ProofStatus.INCONCLUSIVE, inconclusive.proofStatus());
        assertEquals(
            List.of("oracle produced no conclusive equivalence result"),
            inconclusive.blockers());
        assertTrue(refuted.solverResultHash().startsWith("sha256:"));
        assertTrue(inconclusive.solverResultHash().startsWith("sha256:"));
    }

    @Test
    void assumptionOrderDoesNotChangeObligationOrEvidenceHashes() {
        OpenTargetConjecture normal = conjectureWithAssumptions(List.of("B != 0", "A > 0"));
        OpenTargetConjecture reversed = conjectureWithAssumptions(List.of("A > 0", "B != 0"));
        RecordingOracle firstOracle = new RecordingOracle(true, "verified");
        RecordingOracle secondOracle = new RecordingOracle(true, "verified");

        var first = new OpenTargetConjectureProofGate(
            firstOracle, "stable-oracle-v1").evaluate(normal, acceptedEvaluation());
        var second = new OpenTargetConjectureProofGate(
            secondOracle, "stable-oracle-v1").evaluate(reversed, acceptedEvaluation());

        assertEquals(first.obligation().obligationHash(), second.obligation().obligationHash());
        assertEquals(first.solverObligationHash(), second.solverObligationHash());
        assertEquals(first.solverResultHash(), second.solverResultHash());
        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
    }

    private static OpenTargetConjecture conjecture() {
        return conjectureWithAssumptions(List.of("B != 0", "A > 0"));
    }

    private static OpenTargetConjecture conjectureWithAssumptions(List<String> assumptions) {
        PathEvidence path = new PathEvidence(
            "path-1",
            List.of("x * 2 + x * 3", "x * (2 + 3)"),
            List.of("factor-common"),
            assumptions,
            1,
            1);
        ConvergenceEvidence evidence = new ConvergenceEvidence(
            "obs-1",
            "",
            GoalStatus.UNTARGETED,
            "x * 2 + x * 3",
            "x * (2 + 3)",
            "canonical-output",
            5,
            "alpha-pair",
            "value-pair",
            "factor-common||factor-via-padding",
            List.of(path));
        return new OpenTargetConjecture(
            "open-target-factor-common",
            "A * B + A * C",
            "A * (B + C)",
            2,
            2,
            List.of(),
            List.of("obs-1", "obs-2"),
            List.of(evidence),
            List.of(),
            Map.of(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }

    private static EvaluationReport acceptedEvaluation() {
        return evaluation(EvaluationStatus.ACCEPTED_FOR_PROOF, List.of());
    }

    private static EvaluationReport evaluation(
        EvaluationStatus status,
        List<String> blockers
    ) {
        return new EvaluationReport(
            OpenTargetConjectureEvaluator.SCHEMA,
            "open-target-factor-common",
            status,
            "COMPILED",
            "dynamic_hypothesis_open_target_factor_common",
            "sha256:operator",
            1,
            1,
            0,
            1,
            1,
            0,
            List.of(new PositiveHoldoutResult(
                "p-1", 1, true, List.of("m * (4 + 5)"))),
            List.of(new NegativeHoldoutResult(
                "n-1", 0, true, List.of())),
            new CounterexampleEvidence(
                "NO_COUNTEREXAMPLE_FOUND",
                List.of("numeric-boundary-values", "rational-samples"),
                List.of(),
                List.of(),
                "",
                "",
                "no refutation found within configured budget"),
            blockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    private static final class RecordingOracle implements EquivalenceService {
        private final boolean equivalent;
        private final String evidence;
        private int calls;

        private RecordingOracle(boolean equivalent, String evidence) {
            this.equivalent = equivalent;
            this.evidence = evidence;
        }

        @Override
        public boolean areEquivalent(String leftExpression, String rightExpression) {
            calls++;
            return equivalent;
        }

        @Override
        public String evidence(String leftExpression, String rightExpression) {
            return evidence;
        }

        private int calls() {
            return calls;
        }
    }
}
