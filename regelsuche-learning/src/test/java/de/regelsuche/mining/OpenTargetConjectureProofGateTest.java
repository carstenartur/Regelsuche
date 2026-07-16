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
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SymPySolverBackend;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureProofGateTest {

    @Test
    void emitsAndExecutesOneCanonicalSolverObligation() {
        RecordingOracle oracle = new RecordingOracle(
            true, "matching normalized coefficients");
        OpenTargetConjectureProofGate gate = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(
                oracle, "recording-symbolic-oracle", "1"));

        var report = gate.evaluate(
            conjectureWithAssumptions(List.of()), acceptedEvaluation());

        assertEquals(EligibilityStatus.ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.SYMBOLICALLY_VERIFIED, report.proofStatus());
        assertTrue(report.proofObligationEmitted());
        assertEquals("open-target-factor-common-proof",
            report.obligation().obligationId());
        assertEquals("open-target-factor-common",
            report.obligation().provenance().sourceId());
        assertEquals(ResultStatus.CONFIRMED, report.result().status());
        assertEquals(TranslationStatus.LOSSLESS,
            report.result().translationStatus());
        assertEquals(report.obligation().contentHash(),
            report.result().obligationHash());
        assertEquals(1, oracle.calls());
        assertTrue(report.blockers().isEmpty());
        assertTrue(report.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.open-target-conjecture-proof/v2\""));
        assertFalse(report.toCanonicalJson().contains("targetProvided"));
        assertFalse(report.toCanonicalJson().contains("leftExpression"));
    }

    @Test
    void unsupportedAssumptionsBlockBeforeBackendExecution() {
        RecordingOracle oracle = new RecordingOracle(true, "must not execute");
        OpenTargetConjectureProofGate gate = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(oracle, "recording-symbolic-oracle", "1"));

        var report = gate.evaluate(conjecture(), acceptedEvaluation());

        assertEquals(EligibilityStatus.ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.INCONCLUSIVE, report.proofStatus());
        assertEquals(ResultStatus.UNSUPPORTED, report.result().status());
        assertEquals(TranslationStatus.REJECTED,
            report.result().translationStatus());
        assertTrue(report.result().translationIssues().contains(
            "ASSUMPTIONS_NOT_SUPPORTED"));
        assertTrue(report.blockers().getFirst().contains(
            "ASSUMPTIONS_NOT_SUPPORTED"));
        assertEquals(0, oracle.calls());
    }

    @Test
    void rejectedCandidateCannotEmitAnObligationOrCallBackend() {
        RecordingOracle oracle = new RecordingOracle(true, "must not execute");
        OpenTargetConjectureProofGate gate = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(oracle, "recording-symbolic-oracle", "1"));
        EvaluationReport rejected = evaluation(
            EvaluationStatus.REJECTED, List.of("counterexample found"));

        var report = gate.evaluate(conjecture(), rejected);

        assertEquals(EligibilityStatus.NOT_ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.NOT_RUN, report.proofStatus());
        assertFalse(report.proofObligationEmitted());
        assertNull(report.obligation());
        assertNull(report.result());
        assertEquals(0, oracle.calls());
        assertTrue(report.blockers().contains(
            "candidate is not accepted for proof"));
    }

    @Test
    void distinguishesRefutationFromUnknownResult() {
        var refuted = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(
                new RecordingOracle(
                    false, "not equivalent under deterministic samples"),
                "refuting-oracle", "1"))
            .evaluate(conjectureWithAssumptions(List.of()), acceptedEvaluation());
        var unknown = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(
                new RecordingOracle(false, "no equivalence evidence found"),
                "unknown-oracle", "1"))
            .evaluate(conjectureWithAssumptions(List.of()), acceptedEvaluation());

        assertEquals(ProofStatus.REFUTED, refuted.proofStatus());
        assertEquals(ResultStatus.REFUTED, refuted.result().status());
        assertEquals(ProofStatus.INCONCLUSIVE, unknown.proofStatus());
        assertEquals(ResultStatus.UNKNOWN, unknown.result().status());
    }

    @Test
    void assumptionOrderDoesNotChangeCanonicalObligationOrResult() {
        RecordingOracle firstOracle = new RecordingOracle(true, "must not execute");
        RecordingOracle secondOracle = new RecordingOracle(true, "must not execute");
        var first = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(firstOracle, "stable-oracle", "1"))
            .evaluate(
                conjectureWithAssumptions(List.of("B != 0", "A > 0")),
                acceptedEvaluation());
        var second = new OpenTargetConjectureProofGate(
            new SymPySolverBackend(secondOracle, "stable-oracle", "1"))
            .evaluate(
                conjectureWithAssumptions(List.of("A > 0", "B != 0")),
                acceptedEvaluation());

        assertEquals(first.obligation().contentHash(),
            second.obligation().contentHash());
        assertEquals(first.result().contentHash(), second.result().contentHash());
        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(0, firstOracle.calls());
        assertEquals(0, secondOracle.calls());
    }

    private static OpenTargetConjecture conjecture() {
        return conjectureWithAssumptions(List.of("B != 0", "A > 0"));
    }

    private static OpenTargetConjecture conjectureWithAssumptions(
        List<String> assumptions
    ) {
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
