package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverObligationFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortfolioRunPersistenceTest {

    @Test
    void rewriteProducesOneCompleteAuthoritativeRunBundle(
        @TempDir Path directory
    ) throws Exception {
        var obligation = new SolverObligationFactory().equality(
            "portfolio-persistence",
            "x + 0",
            "x",
            List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE,
            new SourceProvenance(
                "portfolio-test",
                "persistence",
                SolverIr.sha256("portfolio-persistence/v1")));
        PortfolioRequest request = PortfolioRequest.create(
            obligation,
            SolverObjective.SYMBOLIC_CONFIRMATION,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(),
            "persistence-test/v1");
        PortfolioRun run = new SolverPortfolioExecutor(
            List.of(StandardPortfolioBackends.polynomialNormalForm()))
            .execute(request);

        run.write(directory, request);
        Path staleRoot = directory.resolve("stale.json");
        Path staleAttempt = directory.resolve("executions/999-stale/execution.json");
        Files.writeString(staleRoot, "stale");
        Files.createDirectories(staleAttempt.getParent());
        Files.writeString(staleAttempt, "stale");

        run.write(directory, request);

        assertFalse(Files.exists(staleRoot));
        assertFalse(Files.exists(staleAttempt));
        assertEquals(obligation.toCanonicalJson(),
            Files.readString(directory.resolve("obligation.json")));
        assertEquals(request.toCanonicalJson(),
            Files.readString(directory.resolve("request.json")));
        assertEquals(run.report().toCanonicalJson(),
            Files.readString(directory.resolve("report.json")));

        List<Path> attemptDirectories;
        try (var files = Files.list(directory.resolve("executions"))) {
            attemptDirectories = files.sorted().toList();
        }
        assertEquals(run.executions().size(), attemptDirectories.size());
        assertEquals(1, attemptDirectories.size());

        SolverExecution execution = run.executions().getFirst();
        Path attemptDirectory = attemptDirectories.getFirst();
        assertTrue(Files.isDirectory(attemptDirectory));
        assertEquals(execution.translation().toCanonicalJson(),
            Files.readString(attemptDirectory.resolve("translation.json")));
        assertEquals(execution.result().toCanonicalJson(),
            Files.readString(attemptDirectory.resolve("result.json")));
        assertEquals(execution.toCanonicalJson(),
            Files.readString(attemptDirectory.resolve("execution.json")));
    }

    @Test
    void writerRejectsAnotherRequestForTheSameRuntimeRun(
        @TempDir Path directory
    ) {
        SolverObligationFactory obligations = new SolverObligationFactory();
        SourceProvenance provenance = new SourceProvenance(
            "portfolio-test", "request-mismatch",
            SolverIr.sha256("portfolio-request-mismatch/v1"));
        var firstObligation = obligations.equality(
            "first", "x + 0", "x", List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, provenance);
        var secondObligation = obligations.equality(
            "second", "x * 1", "x", List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, provenance);
        PortfolioRequest firstRequest = PortfolioRequest.create(
            firstObligation, SolverObjective.SYMBOLIC_CONFIRMATION,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(), "first/v1");
        PortfolioRequest secondRequest = PortfolioRequest.create(
            secondObligation, SolverObjective.SYMBOLIC_CONFIRMATION,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(), "second/v1");
        PortfolioRun run = new SolverPortfolioExecutor(
            List.of(StandardPortfolioBackends.polynomialNormalForm()))
            .execute(firstRequest);

        boolean rejected = false;
        try {
            run.write(directory, secondRequest);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        assertFalse(Files.exists(directory.resolve("report.json")));
    }
}
