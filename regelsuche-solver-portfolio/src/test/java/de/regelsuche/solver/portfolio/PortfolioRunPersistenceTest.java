package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void rewriteRemovesStaleExecutionsButPreservesSiblingRunFiles(
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

        Files.writeString(directory.resolve("request.json"),
            request.toCanonicalJson());
        run.write(directory);
        Path stale = directory.resolve("executions/999-stale.json");
        Files.writeString(stale, "stale");

        run.write(directory);

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(directory.resolve("request.json")));
        assertEquals(request.toCanonicalJson(),
            Files.readString(directory.resolve("request.json")));
        assertEquals(run.report().toCanonicalJson(),
            Files.readString(directory.resolve("report.json")));
        try (var files = Files.list(directory.resolve("executions"))) {
            assertEquals(run.executions().size(), files.count());
        }
    }
}
