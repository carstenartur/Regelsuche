package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import de.regelsuche.solver.ir.PolynomialNormalFormSolverBackend;
import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureProofGateTest {

    @Test
    void defaultGateExecutesExactPolynomialNormalFormProof() {
        var report = new OpenTargetConjectureProofGate().evaluate(
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
        assertEquals("polynomial-normal-form", report.result().backendId());
        assertEquals("matching deterministic polynomial normal form",
            report.result().message());
        assertTrue(report.result().usedCapabilities().contains(
            "DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"));
        assertEquals(report.obligation().contentHash(),
            report.result().obligationHash());
        assertTrue(report.blockers().isEmpty());
        assertTrue(report.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.open-target-conjecture-proof/v2\""));
        assertFalse(report.toCanonicalJson().contains("targetProvided"));
        assertFalse(report.toCanonicalJson().contains("leftExpression"));
    }

    @Test
    void unsupportedAssumptionsBlockBeforeNormalFormExecution() {
        var report = new OpenTargetConjectureProofGate(
            new PolynomialNormalFormSolverBackend())
            .evaluate(conjecture(), acceptedEvaluation());

        assertEquals(EligibilityStatus.ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.INCONCLUSIVE, report.proofStatus());
        assertEquals(ResultStatus.UNSUPPORTED, report.result().status());
        assertEquals(TranslationStatus.REJECTED,
            report.result().translationStatus());
        assertTrue(report.result().translationIssues().contains(
            "ASSUMPTIONS_NOT_SUPPORTED"));
        assertTrue(report.blockers().getFirst().contains(
            "ASSUMPTIONS_NOT_SUPPORTED"));
        assertTrue(report.result().message().contains("before execution"));
    }

    @Test
    void rejectedCandidateCannotEmitAnObligationOrCallBackend() {
        RecordingBackend backend = new RecordingBackend(ResultStatus.CONFIRMED);
        EvaluationReport rejected = evaluation(
            EvaluationStatus.REJECTED, List.of("counterexample found"));

        var report = new OpenTargetConjectureProofGate(backend)
            .evaluate(conjecture(), rejected);

        assertEquals(EligibilityStatus.NOT_ELIGIBLE, report.eligibility());
        assertEquals(ProofStatus.NOT_RUN, report.proofStatus());
        assertFalse(report.proofObligationEmitted());
        assertNull(report.obligation());
        assertNull(report.result());
        assertEquals(0, backend.calls());
        assertTrue(report.blockers().contains(
            "candidate is not accepted for proof"));
    }

    @Test
    void distinguishesLosslessRefutationFromUnknownResult() {
        var refuted = new OpenTargetConjectureProofGate(
            new RecordingBackend(ResultStatus.REFUTED))
            .evaluate(conjectureWithAssumptions(List.of()), acceptedEvaluation());
        var unknown = new OpenTargetConjectureProofGate(
            new RecordingBackend(ResultStatus.UNKNOWN))
            .evaluate(conjectureWithAssumptions(List.of()), acceptedEvaluation());

        assertEquals(ProofStatus.REFUTED, refuted.proofStatus());
        assertEquals(ResultStatus.REFUTED, refuted.result().status());
        assertEquals(ProofStatus.INCONCLUSIVE, unknown.proofStatus());
        assertEquals(ResultStatus.UNKNOWN, unknown.result().status());
    }

    @Test
    void assumptionOrderDoesNotChangeCanonicalObligationOrRejection() {
        var first = new OpenTargetConjectureProofGate(
            new PolynomialNormalFormSolverBackend())
            .evaluate(
                conjectureWithAssumptions(List.of("B != 0", "A > 0")),
                acceptedEvaluation());
        var second = new OpenTargetConjectureProofGate(
            new PolynomialNormalFormSolverBackend())
            .evaluate(
                conjectureWithAssumptions(List.of("A > 0", "B != 0")),
                acceptedEvaluation());

        assertEquals(first.obligation().contentHash(),
            second.obligation().contentHash());
        assertEquals(first.result().contentHash(), second.result().contentHash());
        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(ResultStatus.UNSUPPORTED, first.result().status());
        assertEquals(TranslationStatus.REJECTED,
            first.result().translationStatus());
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

    private static final class RecordingBackend implements SolverBackend {
        private final ResultStatus status;
        private int calls;

        private RecordingBackend(ResultStatus status) {
            this.status = status;
        }

        @Override
        public BackendDescriptor descriptor() {
            return new BackendDescriptor(
                "recording-backend",
                "1",
                List.of(Theory.REAL_ARITHMETIC),
                List.of(Relation.EQUALS),
                List.of(RequestedEvidence.SYMBOLIC_CERTIFICATE),
                true);
        }

        @Override
        public SolverResult solve(Obligation obligation) {
            calls++;
            return SolverResult.create(
                obligation,
                descriptor(),
                status,
                TranslationStatus.LOSSLESS,
                List.of("RECORDING_BACKEND"),
                List.of(),
                status.name(),
                Map.of(),
                status == ResultStatus.CONFIRMED
                    || status == ResultStatus.REFUTED
                    ? SolverIr.sha256("certificate-" + status.name())
                    : "");
        }

        private int calls() {
            return calls;
        }
    }
}
