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
    void identicalObligationIsSubmittedToInternalAndSymbolicBackends() {
        Obligation obligation = equality("x + 0", "x", List.of());
        SolverIr.SolverResult search = new RegelsucheSearchBackend().solve(obligation);
        SolverIr.SolverResult symbolic = new SymPySolverBackend().solve(obligation);

        assertEquals(ResultStatus.CONFIRMED, search.status(), search::toCanonicalJson);
        assertEquals(ResultStatus.CONFIRMED, symbolic.status(), symbolic::toCanonicalJson);
        assertEquals(obligation.contentHash(), search.obligationHash());
        assertEquals(obligation.contentHash(), symbolic.obligationHash());
        assertEquals(obligation.goalHash(), search.goalHash());
        assertEquals(obligation.assumptionsHash(), symbolic.assumptionsHash());
        assertEquals(TranslationStatus.LOSSLESS, search.translationStatus());
        assertEquals(TranslationStatus.LOSSLESS, symbolic.translationStatus());
        assertFalse(search.certificateHash().isBlank());
        assertFalse(symbolic.certificateHash().isBlank());

        SolverIrJsonCodec codec = new SolverIrJsonCodec();
        assertEquals(search.toCanonicalJson(),
            codec.readResult(search.toCanonicalJson()).toCanonicalJson());
        assertEquals(symbolic.toCanonicalJson(),
            codec.readResult(symbolic.toCanonicalJson()).toCanonicalJson());
    }

    @Test
    void assumptionsAreRejectedBeforeEitherBackendCanDropThem() {
        Obligation obligation = equality("x / x", "1", List.of("x != 0"));
        SolverIr.SolverResult search = new RegelsucheSearchBackend().solve(obligation);
        SolverIr.SolverResult symbolic = new SymPySolverBackend().solve(obligation);

        for (SolverIr.SolverResult result : List.of(search, symbolic)) {
            assertEquals(ResultStatus.UNSUPPORTED, result.status());
            assertEquals(TranslationStatus.REJECTED, result.translationStatus());
            assertTrue(result.translationIssues().contains("ASSUMPTIONS_NOT_SUPPORTED"));
            assertTrue(result.message().contains("before execution"));
        }
    }

    @Test
    void symbolicBackendDistinguishesRefutationAndStableHashes() {
        Obligation obligation = equality("x", "x + 1", List.of());
        SymPySolverBackend backend = new SymPySolverBackend();

        SolverIr.SolverResult first = backend.solve(obligation);
        SolverIr.SolverResult second = backend.solve(obligation);

        assertEquals(ResultStatus.REFUTED, first.status(), first::toCanonicalJson);
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.invocationHash(), second.invocationHash());
    }

    private Obligation equality(
        String left,
        String right,
        List<String> assumptions
    ) {
        return factory.equality(
            "backend-integration-" + SolverIr.sha256(left + '=' + right).substring(7, 19),
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
