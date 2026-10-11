package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Executes the production app-to-portfolio path against genuine Lean and Z3. */
@EnabledIfEnvironmentVariable(named="REGELSUCHE_REAL_LEAN", matches="true")
class CheckedProofBridgeRealTest {
    private static Path root() { return Path.of("target/checked-proof").toAbsolutePath(); }
    private static RuleCandidate candidate(String left, String right) {
        return new RuleCandidate(left, right, 1, 0, 0, true, true, false, List.of(),
            RuleStatus.NEW, CandidateProofStatus.OBSERVED, "generic-fixture", List.of());
    }
    @Test void productionServiceConfirmsActualRealFunctionProofs() throws Exception {
        record Case(String left, String right, List<Assumption> assumptions) { }
        var service = new ProofBridgeService(new LeanProofBridge(), root().resolve("requests"),
            ProverExecutor.lean(Path.of(System.getenv("REGELSUCHE_LEAN_PROJECT")), root()));
        for (var c : List.of(new Case("x+0", "x", List.of()),
                new Case("a/b*b", "a", List.of(Assumption.nonZero("b"))),
                new Case("log(exp(x))", "x", List.of()),
                new Case("exp(log(x))", "x", List.of(Assumption.positive("x"))),
                new Case("exp(x+y)", "exp(x)*exp(y)", List.of()),
                new Case("x^65", "x^65", List.of()),
                new Case("x^1.5", "x^1.5", List.of(Assumption.positive("x"))))) {
            var outcome = service.attemptWithDetails(candidate(c.left(), c.right()), c.assumptions());
            assertEquals(ProverExecutionResult.Status.PROVER_CONFIRMED, outcome.execution().status(), outcome.toString());
            assertEquals(CandidateProofStatus.FORMALLY_PROVED, outcome.candidate().proofStatus());
            assertTrue(outcome.execution().stdout().contains("TRANSITIVE_STANDARD_AXIOM_AUDIT"));
            assertTrue(Files.isRegularFile(outcome.artifactPath()));
        }
        var falseGoal = service.attemptWithDetails(candidate("x+1", "x"), List.of());
        assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, falseGoal.candidate().proofStatus());
    }
    @Test void inequalityBridgeUsesActualExpPositivity() {
        var request = new SolverObligationFactory().relation("exp-positive",
            SolverIr.Relation.GREATER_THAN, "exp(x)", "0", List.of(),
            SolverIr.RequestedEvidence.FORMAL_PROOF,
            new SolverIr.SourceProvenance("generic-analytic-test", "exp-positive", SolverIr.sha256("exp-positive/v1")));
        var result = ProverExecutor.lean(Path.of(System.getenv("REGELSUCHE_LEAN_PROJECT")), root())
            .execute(new LeanProofBridge().prove(request).artifact());
        assertEquals(ProverExecutionResult.Status.PROVER_CONFIRMED, result.status(), result.toString());
    }
    @Test void z3ConfirmsAndRetainsItsActualProofNotJustAHash() throws Exception {
        var request = ProofObligationAdapter.equality("(x+y)^2", "x^2+2*x*y+y^2", List.of());
        var detection = de.regelsuche.solver.portfolio.Z3SmtSolverBackend.detectSystemZ3();
        assertEquals(de.regelsuche.solver.portfolio.BackendAvailability.AVAILABLE, detection.availability());
        var attempt = detection.backend().executeWithEvidence(request, root());
        assertEquals(SolverIr.ResultStatus.CONFIRMED, attempt.execution().result().status(), attempt.toString());
        String proof = Files.readString(attempt.directory().resolve("invocation-2/proof.txt"));
        assertFalse(proof.isBlank());
        assertEquals(attempt.execution().result().certificateHash(), SolverIr.sha256(proof));
        assertTrue(Files.readString(attempt.directory().resolve("invocation-2/input.smt2")).contains("(get-proof)"));
        var service = new ProofBridgeService(new SmtProofBridge(), root(), ProverExecutor.z3(root()));
        var proved = service.attemptWithDetails(candidate("(x+y)^2", "x^2+2*x*y+y^2"), List.of());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, proved.candidate().proofStatus(), proved.toString());
        var falseGoal = service.attemptWithDetails(candidate("x+1", "x"), List.of());
        assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, falseGoal.candidate().proofStatus());
    }
}
