package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolverBackendIntegrationTest {
    private final SolverObligationFactory factory = new SolverObligationFactory();

    @Test
    void identicalObligationIsSubmittedToSearchAndExactPolynomialBackends() {
        Obligation obligation = equality("x + 0", "x", List.of());
        SolverExecution search = new RegelsucheSearchBackend().execute(obligation);
        SolverExecution polynomial = new PolynomialNormalFormSolverBackend()
            .execute(obligation);

        assertEquals(ResultStatus.CONFIRMED,
            search.result().status(), search.result()::toCanonicalJson);
        assertEquals(ResultStatus.CONFIRMED,
            polynomial.result().status(), polynomial.result()::toCanonicalJson);
        assertEquals(obligation.contentHash(), search.obligationHash());
        assertEquals(obligation.contentHash(), polynomial.obligationHash());
        assertEquals(obligation.goalHash(), search.result().goalHash());
        assertEquals(obligation.assumptionsHash(),
            polynomial.result().assumptionsHash());
        assertEquals(TranslationStatus.LOSSLESS,
            search.translation().status());
        assertEquals(TranslationStatus.LOSSLESS,
            polynomial.translation().status());
        assertEquals("x + 0", search.translation().termMapping().get("goal.left"));
        assertEquals("x", search.translation().termMapping().get("goal.right"));
        assertEquals(search.translation().termMapping(),
            polynomial.translation().termMapping());
        assertFalse(search.result().certificateHash().isBlank());
        assertFalse(polynomial.result().certificateHash().isBlank());
        assertTrue(polynomial.result().usedCapabilities().contains(
            "DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"));
        assertEquals("matching deterministic polynomial normal form",
            polynomial.result().message());
        assertEquals(search.result().translationStatus(),
            search.translation().status());
        assertEquals(polynomial.result().translationIssues(),
            polynomial.translation().issues());

        SolverIrJsonCodec codec = new SolverIrJsonCodec();
        assertEquals(search.result().toCanonicalJson(),
            codec.readResult(search.result().toCanonicalJson()).toCanonicalJson());
        assertEquals(polynomial.result().toCanonicalJson(),
            codec.readResult(polynomial.result().toCanonicalJson()).toCanonicalJson());
    }

    @Test
    void assumptionsAreRejectedBeforeEitherBackendCanDropThem() {
        Obligation obligation = equality("x / x", "1", List.of("x != 0"));
        SolverExecution search = new RegelsucheSearchBackend().execute(obligation);
        SolverExecution polynomial = new PolynomialNormalFormSolverBackend()
            .execute(obligation);

        for (SolverExecution execution : List.of(search, polynomial)) {
            assertEquals(ResultStatus.UNSUPPORTED, execution.result().status());
            assertEquals(TranslationStatus.REJECTED,
                execution.translation().status());
            assertTrue(execution.translation().issues().contains(
                "ASSUMPTIONS_NOT_SUPPORTED"));
            assertEquals(execution.translation().issues(),
                execution.result().translationIssues());
            assertTrue(execution.result().message().contains("before execution"));
            assertEquals("x / x",
                execution.translation().termMapping().get("goal.left"));
        }
        assertTrue(polynomial.translation().issues().contains(
            "UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY"));
    }

    @Test
    void polynomialBackendDistinguishesExactRefutationAndStableHashes() {
        Obligation obligation = equality("x", "x + 1", List.of());
        PolynomialNormalFormSolverBackend backend =
            new PolynomialNormalFormSolverBackend();

        SolverExecution first = backend.execute(obligation);
        SolverExecution second = backend.execute(obligation);

        assertEquals(ResultStatus.REFUTED,
            first.result().status(), first.result()::toCanonicalJson);
        assertEquals("normal forms differ", first.result().message());
        assertEquals(first.translation().contentHash(),
            second.translation().contentHash());
        assertEquals(first.result().contentHash(), second.result().contentHash());
        assertEquals(first.contentHash(), second.contentHash());
        assertFalse(first.result().certificateHash().isBlank());
    }

    @Test
    void nonPolynomialExpressionsAreRejectedBeforeNormalFormExecution() {
        Obligation obligation = equality("sin(x)^2 + cos(x)^2", "1", List.of());

        SolverExecution execution = new PolynomialNormalFormSolverBackend()
            .execute(obligation);

        assertEquals(ResultStatus.UNSUPPORTED, execution.result().status());
        assertEquals(TranslationStatus.REJECTED,
            execution.translation().status());
        assertTrue(execution.translation().issues().contains(
            "UNSUPPORTED_THEORY:TRANSCENDENTAL_FUNCTIONS"));
        assertTrue(execution.translation().issues().contains(
            "UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY"));
        assertEquals("sin(x) ^ 2 + cos(x) ^ 2",
            execution.translation().termMapping().get("goal.left"));
    }

    private Obligation equality(
        String left,
        String right,
        List<String> assumptions
    ) {
        return factory.equality(
            "backend-integration-"
                + SolverIr.sha256(left + '=' + right).substring(7, 19),
            left,
            right,
            assumptions,
            RequestedEvidence.DECISION,
            new SourceProvenance(
                "test",
                "solver-backend-integration",
                SolverIr.sha256("solver-backend-integration-revision")));
    }
}
