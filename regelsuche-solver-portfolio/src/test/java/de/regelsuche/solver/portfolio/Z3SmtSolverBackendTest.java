package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import de.regelsuche.solver.ir.SolverIr.SymbolDeclaration;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class Z3SmtSolverBackendTest {

    @Test
    void unsatIsConfirmedOnlyAfterProofObjectRetrieval() {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                calls.incrementAndGet();
                if (stdin.contains("(get-proof)")) {
                    return new Z3SmtSolverBackend.ProcessOutput(
                        true, false, 0, "unsat\n(proof (asserted false))\n", "");
                }
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "unsat\n", "");
            });

        SolverExecution execution = backend.execute(referenceObligation());

        assertEquals(2, calls.get());
        assertEquals(ResultStatus.CONFIRMED, execution.result().status());
        assertEquals(TranslationStatus.LOSSLESS, execution.translation().status());
        assertTrue(execution.result().certificateHash().startsWith("sha256:"));
        assertTrue(execution.result().usedCapabilities().contains(
            "SMT_UNSAT_PROOF_OBJECT"));
    }

    @Test
    void unsupportedCallIsRejectedBeforeProcessInvocation() {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                calls.incrementAndGet();
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "unsat", "");
            });
        Obligation obligation = Obligation.create(
            "unsupported-call",
            List.of(new SymbolDeclaration("x", Sort.REAL)),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(),
            new Goal(Relation.EQUALS,
                new Call("sin", List.of(new Symbol("x"))), new Literal("0")),
            RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance(
                "z3-test", "unsupported-call",
                SolverIr.sha256("unsupported-call/v1")));

        SolverExecution execution = backend.execute(obligation);

        assertEquals(0, calls.get());
        assertEquals(ResultStatus.UNSUPPORTED, execution.result().status());
        assertEquals(TranslationStatus.REJECTED, execution.translation().status());
        assertTrue(execution.translation().issues().contains("UNSUPPORTED_CALL:sin"));
    }

    @Test
    void satProducesRefutationAndModelWhenAvailable() {
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                if (stdin.contains("(get-model)")) {
                    return new Z3SmtSolverBackend.ProcessOutput(
                        true, false, 0, "sat\n(model (define-fun x () Real 1.0))\n", "");
                }
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "sat\n", "");
            });
        Obligation falseIdentity = new SolverObligationFactory().equality(
            "false-identity", "x + 1", "x", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance(
                "z3-test", "false-identity",
                SolverIr.sha256("false-identity/v1")));

        SolverExecution execution = backend.execute(falseIdentity);

        assertEquals(ResultStatus.REFUTED, execution.result().status());
        assertFalse(execution.result().counterexample().isEmpty());
        assertTrue(execution.result().counterexample().get("smtModel")
            .contains("define-fun x"));
    }

    @Test
    @Tag("external-prover")
    void systemZ3ReturnsRealProofObject() {
        Z3SmtSolverBackend.Detection detection =
            Z3SmtSolverBackend.detectSystemZ3();
        assertEquals(BackendAvailability.AVAILABLE, detection.availability(),
            detection.detail());

        SolverExecution execution = detection.backend().execute(referenceObligation());

        assertEquals(ResultStatus.CONFIRMED, execution.result().status(),
            execution.result().message());
        assertTrue(execution.result().certificateHash()
            .matches("sha256:[0-9a-f]{64}"));
    }

    private static Obligation referenceObligation() {
        return new SolverObligationFactory().equality(
            "z3-x-plus-zero", "x + 0", "x", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance(
                "z3-test", "x-plus-zero",
                SolverIr.sha256("z3-x-plus-zero/v1")));
    }
}
