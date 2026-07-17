package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.solver.ir.SolverTranslation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortfolioTransientCacheTest {

    @Test
    void timeoutAndErrorNeverSuppressALaterBackendAttempt() {
        InMemoryPortfolioExecutionCache cache = new InMemoryPortfolioExecutionCache();
        Obligation obligation = obligation();

        cache.put("timeout", execution(obligation, ResultStatus.TIMEOUT));
        cache.put("error", execution(obligation, ResultStatus.ERROR));
        cache.put("unknown", execution(obligation, ResultStatus.UNKNOWN));

        assertTrue(cache.find("timeout").isEmpty());
        assertTrue(cache.find("error").isEmpty());
        assertEquals(ResultStatus.UNKNOWN,
            cache.find("unknown").orElseThrow().result().status());
    }

    private static Obligation obligation() {
        return new SolverObligationFactory().equality(
            "transient-cache",
            "x + 0",
            "x",
            List.of(),
            RequestedEvidence.DECISION,
            new SourceProvenance(
                "portfolio-test",
                "transient-cache",
                SolverIr.sha256("transient-cache/v1")));
    }

    private static SolverExecution execution(
        Obligation obligation,
        ResultStatus status
    ) {
        BackendDescriptor descriptor = new BackendDescriptor(
            "transient-backend",
            "1",
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            List.of(RequestedEvidence.DECISION),
            true);
        SolverTranslation translation = SolverTranslation.create(
            obligation,
            descriptor,
            TranslationStatus.LOSSLESS,
            List.of(),
            Map.of(
                "goal.left", "(+ x 0)",
                "goal.right", "x"));
        SolverResult result = SolverResult.create(
            obligation,
            descriptor,
            status,
            TranslationStatus.LOSSLESS,
            List.of("TRANSIENT_TEST_BACKEND"),
            List.of(),
            status.name(),
            Map.of(),
            "");
        return SolverExecution.create(obligation, translation, result);
    }
}
