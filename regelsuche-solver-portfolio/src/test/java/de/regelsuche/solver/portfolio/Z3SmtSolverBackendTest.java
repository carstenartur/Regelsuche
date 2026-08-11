package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.Test;

class Z3SmtSolverBackendTest {

    @Test
    void constructorsAndValueObjectsEnforceDeterministicConfiguration() {
        Z3SmtSolverBackend defaultBackend = new Z3SmtSolverBackend("test");
        Z3SmtSolverBackend customBackend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofMillis(1));

        assertTrue(defaultBackend.configurationHash().startsWith("sha256:"));
        assertTrue(customBackend.configurationHash().startsWith("sha256:"));
        assertTrue(customBackend.descriptor().supportedTheories().contains(
            Theory.REAL_ARITHMETIC));
        assertThrows(IllegalArgumentException.class, () ->
            new Z3SmtSolverBackend(
                " ", List.of("z3"), Duration.ofSeconds(1), noOpRunner()));
        assertThrows(IllegalArgumentException.class, () ->
            new Z3SmtSolverBackend(
                "test", List.of(), Duration.ofSeconds(1), noOpRunner()));

        Z3SmtSolverBackend.Detection detection =
            new Z3SmtSolverBackend.Detection(
                customBackend, BackendAvailability.UNAVAILABLE, null);
        assertEquals("", detection.detail());

        Z3SmtSolverBackend.ProcessOutput normalized =
            new Z3SmtSolverBackend.ProcessOutput(
                true, false, 0, null, null);
        assertEquals("", normalized.stdout());
        assertEquals("", normalized.stderr());
    }

    @Test
    void checkSatTransportFailuresRemainTyped() {
        assertCheckResult(
            new Z3SmtSolverBackend.ProcessOutput(
                false, false, -1, "", "not installed"),
            ResultStatus.ERROR,
            "unavailable");
        assertCheckResult(
            new Z3SmtSolverBackend.ProcessOutput(
                true, true, -1, "", ""),
            ResultStatus.TIMEOUT,
            "timed out");
        assertCheckResult(
            new Z3SmtSolverBackend.ProcessOutput(
                true, false, 7, "bad output", "bad error"),
            ResultStatus.ERROR,
            "failed");
    }

    @Test
    void unknownAndUnrecognizedCheckSatOutputsRemainDistinct() {
        assertCheckResult(
            new Z3SmtSolverBackend.ProcessOutput(
                true, false, 0, "unknown\n", ""),
            ResultStatus.UNKNOWN,
            "returned unknown");
        assertCheckResult(
            new Z3SmtSolverBackend.ProcessOutput(
                true, false, 0, "success\n", ""),
            ResultStatus.ERROR,
            "unrecognized Z3 output");
    }

    @Test
    void proofRetrievalAvailabilityAndTimeoutRemainTyped() {
        SolverExecution unavailable = executeWithProofOutput(
            new Z3SmtSolverBackend.ProcessOutput(
                false, false, -1, "", "not installed"));
        assertEquals(ResultStatus.ERROR, unavailable.result().status());
        assertTrue(unavailable.result().message().contains(
            "proof retrieval unavailable"));

        SolverExecution timedOut = executeWithProofOutput(
            new Z3SmtSolverBackend.ProcessOutput(
                true, true, -1, "", ""));
        assertEquals(ResultStatus.TIMEOUT, timedOut.result().status());
        assertTrue(timedOut.result().message().contains(
            "proof retrieval timed out"));
    }

    @Test
    void missingModelDoesNotInventCounterexampleEvidence() {
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> stdin.contains("(get-model)")
                ? new Z3SmtSolverBackend.ProcessOutput(
                    false, false, -1, "", "not installed")
                : new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "sat\n", ""));

        SolverExecution execution = backend.execute(referenceObligation());

        assertEquals(ResultStatus.REFUTED, execution.result().status());
        assertTrue(execution.result().counterexample().isEmpty());
    }

    @Test
    void unsatIsConfirmedOnlyAfterProofObjectRetrieval() {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = backendReturningProof(calls);

        SolverExecution execution = backend.execute(referenceObligation());

        assertEquals(2, calls.get());
        assertEquals(ResultStatus.CONFIRMED, execution.result().status());
        assertEquals(TranslationStatus.LOSSLESS, execution.translation().status());
        assertTrue(execution.result().certificateHash().startsWith("sha256:"));
        assertTrue(execution.result().usedCapabilities().contains(
            "SMT_UNSAT_PROOF_OBJECT"));
    }

    @Test
    void solverErrorPayloadCannotAuthorizeProof() {
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> stdin.contains("(get-proof)")
                ? new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 1,
                    "unsat\n(error \"proof is not available\")\n", "")
                : new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "unsat\n", ""));

        SolverExecution execution = backend.execute(referenceObligation());

        assertEquals(ResultStatus.ERROR, execution.result().status());
        assertTrue(execution.result().certificateHash().isEmpty());
        assertTrue(execution.result().message().contains("no valid proof object"));
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
    void divisionWithoutNonZeroAssumptionIsRejectedBeforeInvocation() {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                calls.incrementAndGet();
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "unsat", "");
            });
        Obligation obligation = new SolverObligationFactory().equality(
            "unbounded-division", "p / q", "p / q", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            provenance("unbounded-division"));

        SolverExecution execution = backend.execute(obligation);

        assertEquals(0, calls.get());
        assertEquals(ResultStatus.UNSUPPORTED, execution.result().status());
        assertTrue(execution.translation().issues().stream().anyMatch(issue ->
            issue.startsWith("DIVISION_DOMAIN_NOT_ENCODED:")));
    }

    @Test
    void explicitNonZeroAssumptionMakesDivisionTranslationLossless() {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = backendReturningProof(calls);
        Obligation obligation = new SolverObligationFactory().equality(
            "bounded-division", "p / q", "p / q", List.of("q != 0"),
            RequestedEvidence.FORMAL_PROOF,
            provenance("bounded-division"));

        SolverExecution execution = backend.execute(obligation);

        assertEquals(2, calls.get());
        assertEquals(TranslationStatus.LOSSLESS, execution.translation().status());
        assertEquals(ResultStatus.CONFIRMED, execution.result().status());
    }

    @Test
    void satProducesRefutationAndModelWhenAvailable() {
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                if (stdin.contains("(get-model)")) {
                    return new Z3SmtSolverBackend.ProcessOutput(
                        true, false, 0,
                        "sat\n(model (define-fun x () Real 1.0))\n", "");
                }
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "sat\n", "");
            });
        Obligation falseIdentity = new SolverObligationFactory().equality(
            "false-identity", "x + 1", "x", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            provenance("false-identity"));

        SolverExecution execution = backend.execute(falseIdentity);

        assertEquals(ResultStatus.REFUTED, execution.result().status());
        assertFalse(execution.result().counterexample().isEmpty());
        assertTrue(execution.result().counterexample().get("smtModel")
            .contains("define-fun x"));
    }

    private static void assertCheckResult(
        Z3SmtSolverBackend.ProcessOutput checkOutput,
        ResultStatus expectedStatus,
        String expectedMessage
    ) {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                calls.incrementAndGet();
                return checkOutput;
            });

        SolverExecution execution = backend.execute(referenceObligation());

        assertEquals(1, calls.get());
        assertEquals(expectedStatus, execution.result().status());
        assertTrue(execution.result().message().contains(expectedMessage));
    }

    private static SolverExecution executeWithProofOutput(
        Z3SmtSolverBackend.ProcessOutput proofOutput
    ) {
        AtomicInteger calls = new AtomicInteger();
        Z3SmtSolverBackend backend = new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                calls.incrementAndGet();
                if (stdin.contains("(get-proof)")) {
                    return proofOutput;
                }
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "unsat\n", "");
            });

        SolverExecution execution = backend.execute(referenceObligation());
        assertEquals(2, calls.get());
        return execution;
    }

    private static Z3SmtSolverBackend.ProcessRunner noOpRunner() {
        return (command, stdin, timeout) ->
            new Z3SmtSolverBackend.ProcessOutput(
                true, false, 0, "unknown\n", "");
    }

    private static Z3SmtSolverBackend backendReturningProof(AtomicInteger calls) {
        return new Z3SmtSolverBackend(
            "test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> {
                calls.incrementAndGet();
                if (stdin.contains("(get-proof)")) {
                    return new Z3SmtSolverBackend.ProcessOutput(
                        true, false, 0,
                        "unsat\n(proof (asserted false))\n", "");
                }
                return new Z3SmtSolverBackend.ProcessOutput(
                    true, false, 0, "unsat\n", "");
            });
    }

    private static Obligation referenceObligation() {
        return new SolverObligationFactory().equality(
            "z3-x-plus-zero", "x + 0", "x", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            provenance("x-plus-zero"));
    }

    private static SourceProvenance provenance(String sourceId) {
        return new SourceProvenance(
            "z3-test", sourceId,
            SolverIr.sha256("z3-" + sourceId + "/v1"));
    }
}
