package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Production entry points must not mistake successful transport for a proof. */
class CheckedProofBridgeTest {
    static RuleCandidate candidate(CandidateProofStatus status) {
        return new RuleCandidate("x + 0", "x", 1, 0, 0, true, true, false,
            List.of(), RuleStatus.NEW, status, "fixture", List.of());
    }
    @Test void leanEmitsAnActualTypedProofNotASkeleton() {
        var a = new LeanProofBridge().prove("x + 0", "x", List.of());
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, a.status());
        assertFalse(a.artifact().contains("sorry"));
        assertTrue(a.artifact().contains("Lean.collectAxioms"));
        assertTrue(a.artifact().contains("regelsuche_bound"));
    }
    @Test void smtDoesNotInventAnalyticFunctionSemantics() {
        for (String e : List.of("log(exp(x))", "exp(x)", "x^y")) {
            var a = new SmtProofBridge().prove(e, "x", List.of());
            assertNotEquals(CandidateProofStatus.FORMALLY_PROVABLE, a.status(), e);
            assertFalse(a.artifact().contains("declare-fun"));
        }
    }
    @Test void opaqueAssumptionsAreNotSilentlyReplacedByTrue() {
        var a = new SmtProofBridge().prove("x", "x",
            List.of(Assumption.customPredicate("x belongs to an unspecified field", List.of("x"))));
        assertNotEquals(CandidateProofStatus.FORMALLY_PROVABLE, a.status());
    }
    @Test void successfulArbitraryCommandCannotAuthorizeMathematicalProof() {
        var e = new ProverExecutor(List.of("true"), "lean4", ".lean",
            Duration.ofSeconds(2), (exit, out, err) -> exit == 0);
        var outcome = new ProofBridgeService(new LeanProofBridge(), null, e)
            .attemptWithDetails(candidate(CandidateProofStatus.OBSERVED), List.of());
        assertNotEquals(ProverExecutionResult.Status.PROVER_CONFIRMED, outcome.execution().status());
        assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, outcome.candidate().proofStatus());
    }
    @Test void incomingUnboundProvedFlagDoesNotProveCurrentAssumptions() {
        var result = new ProofBridgeService(new LeanProofBridge())
            .attempt(candidate(CandidateProofStatus.FORMALLY_PROVED), List.of());
        assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, result.proofStatus());
    }
    @Test void scriptedBridgeStatusWithoutExecutionCannotProveAnything() {
        ProofBridge forged = (l, r, a) -> new ProofBridge.ProofAttempt(
            CandidateProofStatus.FORMALLY_PROVED, "fake", "lean4");
        assertNotEquals(CandidateProofStatus.FORMALLY_PROVED,
            new ProofBridgeService(forged).attempt(candidate(CandidateProofStatus.OBSERVED), List.of()).proofStatus());
    }
}
