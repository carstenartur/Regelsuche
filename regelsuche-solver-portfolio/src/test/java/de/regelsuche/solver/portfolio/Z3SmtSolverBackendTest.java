package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Predicate;
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
import de.regelsuche.solver.ir.SolverObligationVerifier;
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
    void checkSatRejectsMixedResponsesBeforeRetrievingEvidence() {
        for (String response : List.of("(error \"bad input\")\nsat\n", "unsat\nsat\n")) {
            assertCheckResult(
                new Z3SmtSolverBackend.ProcessOutput(true, false, 0, response, ""),
                ResultStatus.ERROR, "unrecognized Z3 output");
        }
        assertCheckResult(
            new Z3SmtSolverBackend.ProcessOutput(
                true, false, 0, "sat\n", "(error \"bad input\")\n"),
            ResultStatus.ERROR, "unrecognized Z3 output");
    }

    @Test
    void realZ3ChecksExactPremisesIndependentlyOfTheGoal() {
        var detection = Z3SmtSolverBackend.detectSystemZ3();
        assumeTrue(detection.availability() == BackendAvailability.AVAILABLE, detection.detail());
        var analyzer = new AssumptionConsistencyAnalyzer(detection, PortfolioBudget.standard());
        var x = new Symbol("x");
        var zero = new Literal("0");
        var positive = new Predicate("positive", Relation.GREATER_THAN, x, zero);
        var nonpositive = new Predicate("nonpositive", Relation.LESS_OR_EQUAL, x, zero);
        var unsupportedGoal = new Goal(Relation.EQUALS, new Call("sin", List.of(x)), zero);
        var arbitraryGoal = new Goal(Relation.EQUALS, x, zero);
        Obligation satisfiable = premiseTestObligation(List.of(positive), unsupportedGoal);
        Obligation contradictory = premiseTestObligation(List.of(positive, nonpositive), arbitraryGoal);

        assertEquals(ResultStatus.UNSUPPORTED, detection.backend().execute(satisfiable).result().status());
        assertEquals(ResultStatus.CONFIRMED, detection.backend().execute(contradictory).result().status());
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.SAT, analyzer.analyze(satisfiable).verdict());
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.SAT,
            analyzer.analyze(premiseTestObligation(List.of(positive), arbitraryGoal)).verdict());
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.SAT,
            analyzer.analyze(premiseTestObligation(List.of(), arbitraryGoal)).verdict());

        var analysis = analyzer.analyze(contradictory);
        var query = analysis.request().obligation();
        var result = analysis.run().selectedExecution().result();
        var verifier = new SolverObligationVerifier();
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.UNSAT, analysis.verdict());
        assertEquals(contradictory, analysis.original());
        assertEquals(contradictory.assumptions(), query.assumptions());
        assertEquals(contradictory.assumptionsHash(), query.assumptionsHash());
        assertEquals(contradictory.theories(), query.theories());
        assertEquals(contradictory.declarations(), query.declarations());
        assertEquals(contradictory.provenance(), query.provenance());
        assertFalse(result.certificateHash().isEmpty());
        assertTrue(verifier.resultBelongsTo(query, result));
        assertFalse(verifier.resultBelongsTo(contradictory, result));
        assertFalse(verifier.resultBelongsTo(analyzer.analyze(satisfiable).request().obligation(), result));
    }

    @Test
    void premiseAnalysisCanOnlyBeCreatedByTheAnalyzer() throws NoSuchMethodException {
        var constructor = AssumptionConsistencyAnalyzer.Analysis.class.getDeclaredConstructor(
            Obligation.class, PortfolioRequest.class, PortfolioRun.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        assertEquals(0, AssumptionConsistencyAnalyzer.Analysis.class.getConstructors().length);
    }

    @Test
    void premiseAnalysisReportsSolverFailuresWithoutAConsistencyVerdict() {
        assertInconclusivePremiseAnalysis(new Z3SmtSolverBackend.ProcessOutput(
            true, false, 0, "unknown\n", ""), ResultStatus.UNKNOWN);
        assertInconclusivePremiseAnalysis(new Z3SmtSolverBackend.ProcessOutput(
            true, true, -1, "", ""), ResultStatus.TIMEOUT);
        assertInconclusivePremiseAnalysis(new Z3SmtSolverBackend.ProcessOutput(
            true, false, 0, "(error \"bad input\")\nsat\n", ""), ResultStatus.ERROR);
        assertInconclusivePremiseAnalysis(new Z3SmtSolverBackend.ProcessOutput(
            false, false, -1, "", "not installed"), ResultStatus.ERROR);
    }

    @Test
    void premiseAnalysisCanRetryAfterATransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        var backend = new Z3SmtSolverBackend("test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> calls.getAndIncrement() == 0
                ? new Z3SmtSolverBackend.ProcessOutput(false, false, -1, "", "not installed")
                : new Z3SmtSolverBackend.ProcessOutput(true, false, 0, "sat\n", ""));
        var analyzer = premiseAnalyzer(backend, BackendAvailability.AVAILABLE);
        Obligation obligation = referenceObligation();

        assertEquals(AssumptionConsistencyAnalyzer.Verdict.INCONCLUSIVE, analyzer.analyze(obligation).verdict());
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.SAT, analyzer.analyze(obligation).verdict());
    }

    @Test
    void premiseAnalysisPreservesUnsupportedAndUnavailableAttemptDiagnostics() {
        var backend = new Z3SmtSolverBackend("test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> { throw new AssertionError("must not run"); });
        var available = premiseAnalyzer(backend, BackendAvailability.AVAILABLE);
        var unsupported = new Predicate("sine", Relation.EQUALS,
            new Call("sin", List.of(new Symbol("x"))), new Literal("0"));
        var analysis = available.analyze(premiseTestObligation(
            List.of(unsupported), referenceObligation().goal()));

        assertEquals(AssumptionConsistencyAnalyzer.Verdict.INCONCLUSIVE, analysis.verdict());
        assertEquals(PortfolioOutcome.UNSUPPORTED, analysis.run().report().outcome());
        var rejected = analysis.run().report().attempts().getFirst();
        assertEquals(AttemptDisposition.FILTERED_UNSUPPORTED, rejected.disposition());
        assertTrue(rejected.issues().contains("UNSUPPORTED_CALL_EXPRESSION"));
        assertTrue(analysis.run().executions().isEmpty());

        var unavailable = premiseAnalyzer(backend, BackendAvailability.UNAVAILABLE)
            .analyze(referenceObligation());
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.INCONCLUSIVE, unavailable.verdict());
        assertEquals(AttemptDisposition.SKIPPED_UNAVAILABLE,
            unavailable.run().report().attempts().getFirst().disposition());
        assertTrue(unavailable.run().executions().isEmpty());
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

        var analysis = premiseAnalyzer(backend, BackendAvailability.AVAILABLE)
            .analyze(referenceObligation());
        assertEquals(AssumptionConsistencyAnalyzer.Verdict.SAT, analysis.verdict());
        assertTrue(analysis.run().selectedExecution().result().counterexample().isEmpty());
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
    void proofRetrievalRejectsConflictingStatuses() {
        SolverExecution execution = executeWithProofOutput(new Z3SmtSolverBackend.ProcessOutput(
            true, false, 0, "unsat\n(proof (asserted false))\nsat\n", ""));

        assertEquals(ResultStatus.ERROR, execution.result().status());
        assertTrue(execution.result().certificateHash().isEmpty());
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

    @Test
    void independentlyReplaysRealZ3EqualityEvidenceAndRejectsChangedInputs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var detection = Z3SmtSolverBackend.detectSystemZ3();
        assumeTrue(detection.availability() == BackendAvailability.AVAILABLE, detection.detail());
        var x = new Symbol("x");
        var y = new Symbol("y");
        var z = new Symbol("z");
        var xy = new Predicate("xy", Relation.EQUALS, x, y);
        var yz = new Predicate("yz", Relation.EQUALS, y, z);
        var xz = new Goal(Relation.EQUALS, x, z);
        var obligation = equalityObligation(List.of(xy, yz), xz);
        var attempt = detection.backend().executeWithEvidence(obligation, root);
        assertEquals(ResultStatus.CONFIRMED, attempt.execution().result().status());
        String proof = java.nio.file.Files.readString(attempt.directory().resolve("invocation-2/proof.txt"));
        assertTrue(attempt.replaysEqualityProof(obligation));
        assertTrue(Z3EqualityProofReplay.verifies(obligation, proof));
        assertFalse(Z3EqualityProofReplay.verifies(equalityObligation(List.of(xy), xz), proof));
        assertFalse(Z3EqualityProofReplay.verifies(equalityObligation(List.of(xy, yz),
            new Goal(Relation.EQUALS, z, x)), proof));
        assertFalse(Z3EqualityProofReplay.verifies(obligation, proof.replace("trans*", "unverified-rule")));
        assertFalse(Z3EqualityProofReplay.verifies(obligation, proof.replace("(asserted (= y z))", "(asserted (= x z))")));
        assertFalse(Z3EqualityProofReplay.verifies(obligation, proof.substring(0, proof.length() - 1)));
        var symmetry = equalityObligation(List.of(xy), new Goal(Relation.EQUALS, y, x));
        var other = detection.backend().executeWithEvidence(symmetry, root);
        assertTrue(other.replaysEqualityProof(symmetry));
        assertFalse(other.replaysEqualityProof(obligation));
        java.nio.file.Files.copy(other.directory().resolve("invocation-2/proof.txt"),
            attempt.directory().resolve("invocation-2/proof.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertFalse(attempt.replaysEqualityProof(obligation));
    }

    @Test
    void rejectsUnusedPremisesUnboundLetsInventedStepsAndMalformedProofs() {
        var x = new Symbol("x");
        var y = new Symbol("y");
        var z = new Symbol("z");
        var xy = new Predicate("xy", Relation.EQUALS, x, y);
        var goal = new Goal(Relation.EQUALS, x, y);
        var obligation = equalityObligation(List.of(xy), goal);
        String body = "(unit-resolution (asserted (= x y)) (asserted (not (= x y))) false)";
        String raw = "((set-logic ALL) (proof " + body + "))";
        assertTrue(Z3EqualityProofReplay.verifies(obligation, raw));
        assertFalse(Z3EqualityProofReplay.verifies(equalityObligation(List.of(xy,
            new Predicate("yz", Relation.EQUALS, y, z)), goal), raw));
        for (String malformed : List.of(raw + "()", raw.replace("false", "true"),
                raw.replace("(asserted (= x y))", "(symm (asserted (= x y)) (= x y))"),
                raw.replace("(asserted (= x y))", "(trans* (asserted (= x y)) (asserted (= x y)) (= x y))"),
                raw.replace("(asserted (= x y))", "@x9"), raw.replace("(asserted (= x y))", "(asserted (= y x))"),
                raw.replace("(asserted (= x y))", "(let ((@x8 (asserted (= x y)))) (let ((@x8 @x8)) @x8))"),
                raw.replace(body, "(asserted false)"), raw.replace(body, "(symm (asserted (= x y)) false)"),
                raw.replace("(asserted (= x y))", "(let ((@x8 (asserted (= x y)))) $x8)"),
                raw.replace(body, "(".repeat(180) + body + ")".repeat(180)))) {
            assertFalse(Z3EqualityProofReplay.verifies(obligation, malformed));
        }
    }

    private static Obligation equalityObligation(List<Predicate> assumptions, Goal goal) {
        return Obligation.create("equality-replay", List.of(new SymbolDeclaration("x", Sort.REAL),
            new SymbolDeclaration("y", Sort.REAL), new SymbolDeclaration("z", Sort.REAL)),
            List.of(Theory.REAL_ARITHMETIC), assumptions, goal, RequestedEvidence.FORMAL_PROOF,
            provenance("equality-replay"));
    }

    private static Obligation referenceObligation() {
        return new SolverObligationFactory().equality(
            "z3-x-plus-zero", "x + 0", "x", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            provenance("x-plus-zero"));
    }

    private static Obligation premiseTestObligation(List<Predicate> premises, Goal goal) {
        return Obligation.create("premise-test", List.of(new SymbolDeclaration("x", Sort.REAL)),
            List.of(Theory.REAL_ARITHMETIC), premises, goal, RequestedEvidence.FORMAL_PROOF,
            provenance("premise-test"));
    }

    private static AssumptionConsistencyAnalyzer premiseAnalyzer(
        Z3SmtSolverBackend backend,
        BackendAvailability availability
    ) {
        return new AssumptionConsistencyAnalyzer(
            new Z3SmtSolverBackend.Detection(backend, availability, ""), PortfolioBudget.standard());
    }

    private static void assertInconclusivePremiseAnalysis(
        Z3SmtSolverBackend.ProcessOutput output,
        ResultStatus expectedStatus
    ) {
        var backend = new Z3SmtSolverBackend("test", List.of("z3"), Duration.ofSeconds(1),
            (command, stdin, timeout) -> output);
        var analysis = premiseAnalyzer(backend, BackendAvailability.AVAILABLE)
            .analyze(referenceObligation());

        assertEquals(AssumptionConsistencyAnalyzer.Verdict.INCONCLUSIVE, analysis.verdict());
        assertEquals(expectedStatus, analysis.run().executions().getFirst().result().status());
    }

    private static SourceProvenance provenance(String sourceId) {
        return new SourceProvenance(
            "z3-test", sourceId,
            SolverIr.sha256("z3-" + sourceId + "/v1"));
    }
}
