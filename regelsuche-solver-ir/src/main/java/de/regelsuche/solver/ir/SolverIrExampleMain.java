package de.regelsuche.solver.ir;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes annotated, deterministic two-backend solver IR examples. */
public final class SolverIrExampleMain {
    private SolverIrExampleMain() {
    }

    public static void main(String[] args) {
        Path output = args.length == 0
            ? Path.of("build", "reports", "solver-ir")
            : Path.of(args[0]);
        SolverObligationFactory factory = new SolverObligationFactory();
        SourceProvenance provenance = new SourceProvenance(
            "documentation-example",
            "solver-neutral-ir",
            SolverIr.sha256("solver-neutral-ir-example/v1"));
        Obligation obligation = factory.equality(
            "additive-identity",
            "x + 0",
            "x",
            List.of(),
            RequestedEvidence.DECISION,
            provenance);
        SolverExecution search = new RegelsucheSearchBackend().execute(obligation);
        SolverExecution polynomial = new PolynomialNormalFormSolverBackend()
            .execute(obligation);

        Obligation assumptionBound = factory.equality(
            "division-identity",
            "x / x",
            "1",
            List.of("x != 0"),
            RequestedEvidence.DECISION,
            provenance);
        SolverExecution unsupported = new RegelsucheSearchBackend()
            .execute(assumptionBound);
        write(output, obligation, search, polynomial, assumptionBound, unsupported);
    }

    private static void write(
        Path output,
        Obligation obligation,
        SolverExecution search,
        SolverExecution polynomial,
        Obligation assumptionBound,
        SolverExecution unsupported
    ) {
        try {
            Files.createDirectories(output);
            write(output.resolve("obligation.json"), obligation.toCanonicalJson());
            writeExecution(output, "regelsuche-search", search);
            writeExecution(output, "polynomial-normal-form", polynomial);
            write(output.resolve("assumption-obligation.json"),
                assumptionBound.toCanonicalJson());
            writeExecution(output, "unsupported", unsupported);
            String manifest = new JsonWriter().beginObject()
                .property("schema", "regelsuche.solver-ir-example/v1")
                .property("obligationHash", obligation.contentHash())
                .property("searchExecutionHash", search.contentHash())
                .property("polynomialExecutionHash", polynomial.contentHash())
                .property("sameObligationSubmittedToBothBackends",
                    search.obligationHash().equals(polynomial.obligationHash()))
                .property("assumptionObligationHash", assumptionBound.contentHash())
                .property("unsupportedExecutionHash", unsupported.contentHash())
                .property("unsupportedBeforeExecution",
                    unsupported.result().status()
                        == SolverIr.ResultStatus.UNSUPPORTED)
                .endObject().toString();
            write(output.resolve("manifest.json"), manifest);
        } catch (IOException exception) {
            throw new UncheckedIOException("could not write solver IR example", exception);
        }
    }

    private static void writeExecution(
        Path output,
        String prefix,
        SolverExecution execution
    ) throws IOException {
        write(output.resolve(prefix + "-translation.json"),
            execution.translation().toCanonicalJson());
        write(output.resolve(prefix + "-result.json"),
            execution.result().toCanonicalJson());
        write(output.resolve(prefix + "-execution.json"),
            execution.toCanonicalJson());
    }

    private static void write(Path path, String value) throws IOException {
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
