package de.regelsuche.solver.portfolio;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverObligationFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes a real three-role portfolio example; system Z3 is required. */
public final class SolverPortfolioExampleMain {
    private SolverPortfolioExampleMain() {
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0
            ? Path.of("build", "reports", "solver-portfolio")
            : Path.of(args[0]);
        Files.createDirectories(output);
        Z3SmtSolverBackend.Detection detection =
            Z3SmtSolverBackend.detectSystemZ3();
        if (detection.availability() != BackendAvailability.AVAILABLE) {
            throw new IllegalStateException(
                "system Z3 is required: " + detection.detail());
        }
        List<PortfolioBackend> backends = List.of(
            StandardPortfolioBackends.search(),
            StandardPortfolioBackends.polynomialNormalForm(),
            StandardPortfolioBackends.z3(
                detection.backend(), detection.availability()));
        SolverObligationFactory obligations = new SolverObligationFactory();
        SourceProvenance provenance = new SourceProvenance(
            "solver-portfolio-example", "x-plus-zero",
            SolverIr.sha256("solver-portfolio-example/v1"));

        var formalObligation = obligations.equality(
            "portfolio-formal-x-plus-zero", "x + 0", "x", List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, provenance);
        PortfolioRequest formalRequest = PortfolioRequest.create(
            formalObligation, SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.COUNTEREXAMPLE_FIRST,
            PortfolioBudget.standard(), "reference-formal-path/v1");
        PortfolioRun formalRun = new SolverPortfolioExecutor(backends)
            .execute(formalRequest);
        if (formalRun.report().outcome() != PortfolioOutcome.CONFIRMED
                || !formalRun.report().proofAuthorized()) {
            throw new IllegalStateException(
                "reference formal portfolio did not confirm: "
                    + formalRun.report().toCanonicalJson());
        }

        var guidanceObligation = obligations.equality(
            "portfolio-guidance-x-plus-zero", "x + 0", "x", List.of(),
            RequestedEvidence.DECISION, provenance);
        PortfolioRequest guidanceRequest = PortfolioRequest.create(
            guidanceObligation, SolverObjective.SEARCH_GUIDANCE,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(), "reference-guidance-path/v1");
        PortfolioRun guidanceRun = new SolverPortfolioExecutor(backends)
            .execute(guidanceRequest);
        if (guidanceRun.report().outcome() != PortfolioOutcome.CONFIRMED) {
            throw new IllegalStateException(
                "reference search portfolio did not confirm");
        }

        write(output.resolve("formal-request.json"), formalRequest.toCanonicalJson());
        write(output.resolve("formal-report.json"), formalRun.report().toCanonicalJson());
        write(output.resolve("formal-selected-execution.json"),
            formalRun.selectedExecution().toCanonicalJson());
        write(output.resolve("guidance-request.json"), guidanceRequest.toCanonicalJson());
        write(output.resolve("guidance-report.json"), guidanceRun.report().toCanonicalJson());
        for (PortfolioBackend backend : backends) {
            write(output.resolve("profile-" + backend.profile().backendId() + ".json"),
                backend.profile().toCanonicalJson());
        }
        String manifest = new JsonWriter().beginObject()
            .property("schema", "regelsuche.solver-portfolio-example/v1")
            .property("z3Version", detection.backend().descriptor().backendVersion())
            .property("allThreeRolesConfigured", backends.size() == 3)
            .property("formalMultiStage", formalRun.report().attempts().stream()
                .filter(attempt -> attempt.disposition() == AttemptDisposition.EXECUTED)
                .map(PortfolioAttempt::backendId).distinct().count() >= 2)
            .property("formalProofAuthorized", formalRun.report().proofAuthorized())
            .property("searchGuidanceExecuted",
                "regelsuche-search".equals(guidanceRun.report().selectedBackendId()))
            .property("formalReportHash", formalRun.report().contentHash())
            .property("guidanceReportHash", guidanceRun.report().contentHash())
            .endObject().toString();
        write(output.resolve("manifest.json"), manifest);
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
