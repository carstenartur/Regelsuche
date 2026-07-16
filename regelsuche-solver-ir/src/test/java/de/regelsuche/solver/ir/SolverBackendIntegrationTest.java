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
        SolverIr.SolverResult search = new RegelsucheSearchBackend().solve(obligation);
        SolverIr.SolverResult polynomial = new PolynomialNormalFormSolverBackend()
            .solve(obligation);

        assertEquals(ResultStatus.CONFIRMED, search.status(), search::toCanonicalJson);
        assertEquals(ResultStatus.CONFIRMED,
            polynomial.status(), polynomial::toCanonicalJson);
        assertEquals(obligation.contentHash(), search.obligationHash());
        assertEquals(obligation.contentHash(), polynomial.obligationHash());
        assertEquals(obligation.goalHash(), search.goalHash());
        assertEquals(obligation.assumptionsHash(), polynomial.assumptionsHash());
        assertEquals(TranslationStatus.LOSSLESS, search.translationStatus());
        assertEquals(TranslationStatus.LOSSLESS, polynomial.translationStatus());
        assertFalse(search.certificateHash().isBlank());
        assertFalse(polynomial.certificateHash().isBlank());
        assertTrue(polynomial.usedCapabilities().contains(
            "DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"));
        assertEquals("matching deterministic polynomial normal form",
            polynomial.message());

        SolverIrJsonCodec codec = new SolverIrJsonCodec();
        assertEquals(search.toCanonicalJson(),
            codec.readResult(search.toCanonicalJson()).toCanonicalJson());
        assertEquals(polynomial.toCanonicalJson(),
            codec.readResult(polynomial.toCanonicalJson()).toCanonicalJson());
    }

    @Test
    void assumptionsAreRejectedBeforeEitherBackendCanDropThem() {
        Obligation obligation = equality("x / x", "1", List.of("x != 0"));
        SolverIr.SolverResult search = new RegelsucheSearchBackend().solve(obligation);
        SolverIr.SolverResult polynomial = new PolynomialNormalFormSolverBackend()
            .solve(obligation);

        for (SolverIr.SolverResult result : List.of(search, polynomial)) {
            assertEquals(ResultStatus.UNSUPPORTED, result.status());
            assertEquals(TranslationStatus.REJECTED, result.translationStatus());
            assertTrue(result.translationIssues().contains(
                "ASSUMPTIONS_NOT_SUPPORTED"));
            assertTrue(result.message().contains("before execution"));
        }
        assertTrue(polynomial.translationIssues().contains(
            "UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY"));
    }

    @Test
    void polynomialBackendDistinguishesExactRefutationAndStableHashes() {
        Obligation obligation = equality("x", "x + 1", List.of());
        PolynomialNormalFormSolverBackend backend =
            new PolynomialNormalFormSolverBackend();

        SolverIr.SolverResult first = backend.solve(obligation);
        SolverIr.SolverResult second = backend.solve(obligation);

        assertEquals(ResultStatus.REFUTED, first.status(), first::toCanonicalJson);
        assertEquals("normal forms differ", first.message());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.invocationHash(), second.invocationHash());
        assertFalse(first.certificateHash().isBlank());
    }

    @Test
    void nonPolynomialExpressionsAreRejectedBeforeNormalFormExecution() {
        Obligation obligation = equality("sin(x)^2 + cos(x)^2", "1", List.of());

        SolverIr.SolverResult result = new PolynomialNormalFormSolverBackend()
            .solve(obligation);

        assertEquals(ResultStatus.UNSUPPORTED, result.status());
        assertEquals(TranslationStatus.REJECTED, result.translationStatus());
        assertTrue(result.translationIssues().contains(
            "UNSUPPORTED_THEORY:TRANSCENDENTAL_FUNCTIONS"));
        assertTrue(result.translationIssues().contains(
            "UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY"));
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
