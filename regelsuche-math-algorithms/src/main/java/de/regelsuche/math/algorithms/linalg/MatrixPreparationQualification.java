package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.representation.RepresentationBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Finite profile characterization; the frozen RREF-v1 experiment is left intact. */
public final class MatrixPreparationQualification {
    public static final String SCHEMA = "regelsuche.matrix-preparation-qualification/v1";
    private MatrixPreparationQualification() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("verify")) {
            verifyDirectories(Path.of(args[1]), Path.of(args[2]));
        } else if (args.length == 1) {
            write(Path.of(args[0]));
        } else {
            throw new IllegalArgumentException("supply output directory, or verify <host-directory> <container-directory>");
        }
    }

    public static Map<String, String> artifacts() {
        Map<String, String> artifacts = new TreeMap<>();
        JsonWriter report = new JsonWriter().beginObject();
        report.property("schema", SCHEMA).property("totalBudgetPerRoute", MatrixPreparation.DEFAULT_WORK)
            .property("claim", "Finite exact correctness and capability characterization; no universal speedup claim")
            .property("workConvention", "Construction ledgers exclude separately bounded independent audit reruns in both routes")
            .property("directBaseline", DirectScalarEliminationSolver.SOLVER_ID);
        report.array("cases", writer -> cases().forEach(fixture -> writer.objectValue(w -> writeCase(w, fixture, artifacts))));
        report.array("artifactIdentities", writer -> artifacts.forEach((name, contents) -> writer.objectValue(w ->
            w.property("file", name).property("sha256", MatrixRepresentationBridge.hash(contents)))));
        artifacts.put("qualification.json", report.endObject().toString() + "\n");
        return java.util.Collections.unmodifiableMap(artifacts);
    }

    private static void writeCase(JsonWriter writer, Fixture fixture, Map<String, String> artifacts) {
        writer.property("id", fixture.id()).property("source", fixture.source());
        writer.stringArray("experimentalRecipes", fixture.operators());
        List<MatrixPreparation.Analysis> analyses = new ArrayList<>();
        for (MatrixPreparation.Profile profile : MatrixPreparation.Profile.values()) {
            var request = new MatrixPreparation.Request(fixture.source(), fixture.unknowns(), profile,
                MatrixPreparation.DEFAULT_WORK, fixture.catalog(),
                profile == MatrixPreparation.Profile.EXPERIMENTAL_OPERATOR_V1 ? fixture.operators() : List.of(), fixture.eigenvalue(),
                fixture.nonzero(), "", List.of());
            MatrixPreparation preparation = new MatrixPreparation();
            var analysis = preparation.analyze(request);
            if (!preparation.verify(analysis)) {
                throw new IllegalStateException("profile replay failed: " + fixture.id() + "/" + profile);
            }
            analyses.add(analysis);
            artifacts.put(fixture.id() + "-" + profile + ".json", MatrixPreparationJson.toJson(analysis));
        }
        writer.array("profiles", w -> analyses.forEach(analysis -> w.objectValue(profile -> {
            profile.property("profile", analysis.request().profile().name()).property("status", analysis.status().name())
                .property("acceptedRepresentations", analysis.acceptedCount())
                .property("contentHash", MatrixPreparationJson.contentHash(analysis));
            MatrixPreparationJson.writeWork(profile, "work", analysis.work());
            profile.stringArray("newlyUnlockedCapabilities", analysis.attempts().stream().filter(a -> a.outcome().accepted())
                .flatMap(a -> a.outcome().formation().representation().orElseThrow().newlyUnlockedCapabilities().stream()).distinct().toList());
        })));
        compareDirect(writer, fixture, analyses);
    }

    private static void compareDirect(JsonWriter writer, Fixture fixture, List<MatrixPreparation.Analysis> analyses) {
        MatrixPreparation.Analysis safe = analyses.get(1);
        if (safe.equations().isEmpty()) {
            writer.property("comparison", "SOURCE_UNSUPPORTED");
            return;
        }
        var source = new DirectScalarEliminationSolver.Source(safe.equations(), fixture.unknowns());
        DirectScalarEliminationSolver direct = new DirectScalarEliminationSolver();
        var baseline = direct.solve(source, new RepresentationBridge.Budget(MatrixPreparation.DEFAULT_WORK));
        writer.object("direct", w -> {
            w.property("status", baseline.status().name()).property("detailCode", baseline.detailCode());
            MatrixPreparationJson.writeWork(w, "work", baseline.work());
            baseline.certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
        });
        if (baseline.status() != DirectScalarEliminationSolver.Status.SOLVED) {
            writer.property("comparison", "OUTSIDE_DIRECT_EXACT_FRAGMENT");
            return;
        }
        if (!direct.verify(source, baseline)) {
            throw new IllegalStateException("direct baseline verification failed: " + fixture.id());
        }
        for (MatrixPreparation.Analysis analysis : analyses.subList(1, analyses.size())) {
            var reduction = analysis.rowReduction().flatMap(ExactRrefSolver.Result::reduction).orElseThrow(() ->
                new IllegalStateException("prepared profile failed a directly solved fixture: " + fixture.id()));
            if (!ExactLinearSolutionConsequence.fromRref(reduction).equals(baseline.consequence().orElseThrow())) {
                throw new IllegalStateException("solution-set disagreement: " + fixture.id());
            }
        }
        writer.property("comparison", "EXACT_SOLUTION_CONSEQUENCES_AGREE");
    }

    private record Fixture(String id, String source, List<String> unknowns,
            List<MatrixPreparation.NamedMatrix> catalog, String eigenvalue, boolean nonzero, List<String> operators) { }

    private static Fixture scalar(String id, String source, String... unknowns) {
        return new Fixture(id, source, List.of(unknowns), List.of(), "", false, List.of());
    }

    private static List<Fixture> cases() {
        return List.of(
            scalar("source-product", "2*(x+y)+3*(x-y)=5; (x+y)+4*(x-y)=6", "x", "y"),
            scalar("mapped-blocks", "x+y=2; z=3; x-y=0", "z", "y", "x"),
            scalar("identity-plus", "x+(2*x+y)=4; y+(x+3*y)=5", "x", "y"),
            scalar("unused-coordinate", "x=2", "unused", "x"),
            scalar("inconsistent", "x+y=1; x+y=2", "x", "y"),
            scalar("exact-decimals", "0.1*x+0.2*y=0.3; x-y=0", "x", "y"),
            scalar("nonlinear-control", "x*y=1; x+y=2", "x", "y"),
            new Fixture("visible-factors", "5*x-y=5; 5*x-3*y=6", List.of("x", "y"), List.of(
                new MatrixPreparation.NamedMatrix("P", List.of(List.of("2", "3"), List.of("1", "4")), List.of()),
                new MatrixPreparation.NamedMatrix("Q", List.of(List.of("1", "1"), List.of("1", "-1")), List.of())), "", false, List.of()),
            new Fixture("ordered-distributivity", "3*x+2*y=5; x+2*y=3", List.of("x", "y"), List.of(
                new MatrixPreparation.NamedMatrix("A", List.of(List.of("1", "1"), List.of("0", "1")), List.of()),
                new MatrixPreparation.NamedMatrix("B", List.of(List.of("1", "0"), List.of("1", "1")), List.of())),
                "", false, List.of("A*(B+I(2))")),
            new Fixture("guarded-inverse", "x=2; y=3", List.of("x", "y"), List.of(
                new MatrixPreparation.NamedMatrix("T", List.of(List.of("1", "1"), List.of("0", "1")),
                    List.of(List.of("1", "-1"), List.of("0", "1")))), "", false, List.of("T*inverse(T)")),
            new Fixture("eigenproblem", "a*x+b*y=lambda*x; c*x+d*y=lambda*y", List.of("x", "y"), List.of(), "lambda", true, List.of()),
            new Fixture("missing-vector-guard", "a*x+b*y=lambda*x; c*x+d*y=lambda*y", List.of("x", "y"), List.of(), "lambda", false, List.of()));
    }

    public static void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (Map.Entry<String, String> artifact : artifacts().entrySet()) {
            AtomicJsonFile.writeUtf8(directory.resolve(artifact.getKey()), artifact.getValue());
        }
    }

    public static void verifyDirectories(Path host, Path container) throws IOException {
        Map<String, String> expected = artifacts();
        requireFiles(host, expected.keySet());
        requireFiles(container, expected.keySet());
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String hostContents = Files.readString(host.resolve(entry.getKey()));
            String containerContents = Files.readString(container.resolve(entry.getKey()));
            if (!entry.getValue().equals(hostContents) || !hostContents.equals(containerContents)) {
                throw new IllegalStateException("matrix reproduction mismatch: " + entry.getKey());
            }
        }
    }

    private static void requireFiles(Path directory, java.util.Set<String> expected) throws IOException {
        try (var paths = Files.list(directory)) {
            var actual = paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected)) {
                throw new IllegalStateException("matrix reproduction file inventory mismatch: " + directory);
            }
        }
    }
}
