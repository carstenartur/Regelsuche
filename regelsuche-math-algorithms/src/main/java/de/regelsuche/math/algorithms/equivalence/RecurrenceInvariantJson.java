package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInductionVerifier.Certificate;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.Run;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.WorkProfile;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Complete specialized artifact projection. Native result hashes never replace their concrete evidence. */
final class RecurrenceInvariantJson {
    private static final int MAX_BYTES = 16_000_000;
    private RecurrenceInvariantJson() {}

    static String run(Run run, boolean includeHash) {
        JsonWriter json = new JsonWriter().beginObject().property("schema", Run.SCHEMA)
            .property("discoveryRevision", ExactRecurrenceInvariantDiscovery.REVISION)
            .property("checkerRevision", ExactRecurrenceInductionVerifier.REVISION)
            .property("formationCanonicalJson", run.formation().toCanonicalJson()).property("formationHash", run.formation().contentHash())
            .property("status", run.status().name()).object("work", j -> work(j, run.work()))
            .property("plannedCharts", run.formation().lambdas().size() * run.formation().basis().size())
            .property("unstartedCharts", run.formation().lambdas().size() * run.formation().basis().size() - run.attempts().size())
            .array("attempts", array -> run.attempts().forEach(attempt -> array.objectValue(j -> {
                j.property("lambdaIndex", attempt.lambdaIndex()).property("chartIndex", attempt.chartIndex())
                    .property("status", attempt.status()).property("detailCode", attempt.detailCode());
                if (attempt.solverResult().isPresent()) { j.object("solverResult", r -> result(r, attempt.solverResult().orElseThrow())); }
                else { j.nullProperty("solverResult"); }
                if (attempt.replayResult().isPresent()) { j.object("replayResult", r -> result(r, attempt.replayResult().orElseThrow())); }
                else { j.nullProperty("replayResult"); }
                if (attempt.certificate().isPresent()) {
                    var checked = attempt.certificate().orElseThrow();
                    j.property("certificateCanonicalJson", checked.certificate().toCanonicalJson())
                        .object("cumulativeWorkAtIssuance", w -> work(w, checked.work()));
                } else { j.nullProperty("certificateCanonicalJson"); }
            })));
        if (includeHash) { json.property("contentHash", run.contentHash()); }
        return bounded(json.endObject().toString());
    }

    static String certificate(Certificate certificate, boolean includeHash) {
        JsonWriter json = new JsonWriter().beginObject().property("schema", Certificate.SCHEMA)
            .property("checkerRevision", ExactRecurrenceInductionVerifier.REVISION)
            .property("formationHash", certificate.formationHash()).property("lambdaIndex", certificate.lambdaIndex())
            .property("chartIndex", certificate.chartIndex()).property("lambda", certificate.lambda().canonicalText())
            .stringArray("coefficients", RecurrenceInvariantFormation.text(certificate.coefficients()))
            .object("solverResult", j -> result(j, certificate.solverResult()))
            .property("invariantExpression", certificate.invariantExpression())
            .property("shiftedInvariantExpression", certificate.shiftedInvariantExpression())
            .property("scaledInvariantExpression", certificate.scaledInvariantExpression())
            .property("initialValue", certificate.initialValue().canonicalText()).property("indexDomain", certificate.indexDomain())
            .property("claim", "I(s_n)=lambda^n*I(s_0)").property("naturalPowerAtZero", "1")
            .property("sourceModel", "a_(n+k)=sum_i(recurrenceCoefficient_i*a_(n+i))");
        if (includeHash) { json.property("contentHash", certificate.contentHash()); }
        return bounded(json.endObject().toString());
    }

    private static void work(JsonWriter j, WorkProfile work) {
        j.property("configured", work.configured()).property("formation", work.formation()).property("solver", work.solver())
            .property("solverReplay", work.solverReplay()).property("transitionCheck", work.transitionCheck())
            .property("initialCheck", work.initialCheck()).property("consumed", work.consumed()).property("remaining", work.remaining());
    }

    private static void result(JsonWriter j, ExactLinearPolynomialHoleSolver.Result result) {
        j.property("solverId", ExactLinearPolynomialHoleSolver.SOLVER_ID).property("solverRevision", ExactLinearPolynomialHoleSolver.REVISION)
            .property("contentHash", result.contentHash()).property("sourceExpression", result.sourceExpression())
            .property("ansatzTemplate", result.ansatzTemplate()).stringArray("holeIds", result.holeIds())
            .stringArray("assumptions", result.assumptions()).object("limits", x -> x.property("maxHoles", result.limits().maxHoles())
                .property("maxMonomials", result.limits().maxMonomials()).property("maxScalarBits", result.limits().maxScalarBits())
                .property("maxWorkUnits", result.limits().maxWorkUnits()))
            .property("status", result.status().name()).property("detailCode", result.detailCode())
            .object("work", x -> x.property("configured", result.work().configured()).property("projection", result.work().projection())
                .property("constraints", result.work().constraints()).property("elimination", result.work().elimination())
                .property("verification", result.work().verification()).property("consumed", result.work().consumed())
                .property("remaining", result.work().remaining()))
            .array("constraints", array -> result.constraints().forEach(row -> array.objectValue(x -> x.property("monomial", row.monomial())
                .stringArray("coefficients", row.coefficients().stream().map(Rational::toString).toList())
                .property("rightHandSide", row.rightHandSide().toString()))));
        if (result.candidate().isPresent()) {
            var candidate = result.candidate().orElseThrow();
            j.object("candidate", x -> x.property("instantiatedExpression", candidate.instantiatedExpression())
                .object("bindings", values -> candidate.bindings().forEach((id, scalar) -> values.property(id, scalar.canonicalText()))));
        } else { j.nullProperty("candidate"); }
        if (result.reduction().isPresent()) {
            var reduction = result.reduction().orElseThrow();
            j.object("rrefResult", x -> {
                x.property("status", reduction.status().name()).property("detailCode", reduction.detailCode())
                    .object("work", w -> w.property("configuredWorkUnits", reduction.work().configuredWorkUnits())
                        .property("consumedWorkUnits", reduction.work().consumedWorkUnits()));
                if (reduction.certificate().isPresent()) { x.object("certificate", c -> certificate(c, reduction.certificate().orElseThrow())); }
                else { x.nullProperty("certificate"); }
                if (reduction.reduction().isPresent()) { x.object("reduction", r -> reduction(r, reduction.reduction().orElseThrow())); }
                else { x.nullProperty("reduction"); }
            });
        } else { j.nullProperty("rrefResult"); }
    }

    private static void certificate(JsonWriter j, ExactRrefSolver.Certificate certificate) {
        j.property("schema", certificate.schema()).property("solverId", certificate.solverId()).property("relation", certificate.relation().name())
            .property("sourceSystemHash", certificate.sourceSystemHash()).property("solutionClassification", certificate.solutionClassification().name())
            .property("contentHash", certificate.contentHash());
        matrix(j, "reducedAugmentedRows", certificate.reducedAugmentedRows());
        j.stringArray("canonicalOperations", certificate.canonicalOperations());
        pivots(j, certificate.coefficientPivots());
        integers(j, "freeVariableColumns", certificate.freeVariableColumns());
        integers(j, "contradictionRows", certificate.contradictionRows());
        j.stringArray("particularSolution", certificate.particularSolution());
        matrix(j, "nullspaceBasis", certificate.nullspaceBasis());
        j.stringArray("capabilitiesBefore", certificate.capabilitiesBefore()).stringArray("capabilitiesAfter", certificate.capabilitiesAfter())
            .stringArray("newlyUnlockedCapabilities", certificate.newlyUnlockedCapabilities())
            .stringArray("lostOrConditionalCapabilities", certificate.lostOrConditionalCapabilities());
    }

    private static void reduction(JsonWriter j, ExactRrefReduction reduction) {
        j.stringArray("variables", reduction.variables()).property("relation", reduction.relation().name());
        matrix(j, "reducedAugmentedRows", reduction.reducedAugmentedRows().stream().map(row -> row.stream().map(Rational::toString).toList()).toList());
        pivots(j, reduction.coefficientPivots());
        integers(j, "freeVariableColumns", reduction.freeVariableColumns());
        integers(j, "contradictionRows", reduction.contradictionRows());
        if (reduction.particularSolution().isPresent()) { j.stringArray("particularSolution", vector(reduction.particularSolution().orElseThrow())); }
        else { j.nullProperty("particularSolution"); }
        matrix(j, "nullspaceBasis", reduction.nullspaceBasis().stream().map(RecurrenceInvariantJson::vector).toList());
        j.stringArray("rowOperations", reduction.rowOperations().stream().map(ExactRrefReduction.RowOperation::canonicalForm).toList())
            .object("capabilityFrontier", x -> x.stringArray("applicableBefore", reduction.capabilityFrontier().applicableBefore())
                .stringArray("applicableAfter", reduction.capabilityFrontier().applicableAfter())
                .stringArray("newlyUnlocked", reduction.capabilityFrontier().newlyUnlocked())
                .stringArray("lostOrConditional", reduction.capabilityFrontier().lostOrConditional()));
    }

    private static List<String> vector(ExactVector vector) { return vector.values().stream().map(Rational::toString).toList(); }
    private static void integers(JsonWriter j, String key, List<Integer> values) { j.array(key, array -> values.forEach(array::value)); }
    private static void matrix(JsonWriter j, String key, List<List<String>> rows) {
        j.array(key, array -> rows.forEach(row -> array.arrayValue(values -> row.forEach(values::value))));
    }
    private static void pivots(JsonWriter j, List<ExactRrefReduction.Pivot> pivots) {
        j.array("coefficientPivots", array -> pivots.forEach(pivot -> array.objectValue(x -> x.property("row", pivot.row()).property("column", pivot.column()))));
    }
    private static String bounded(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) { throw new IllegalArgumentException("recurrence artifact exceeds byte bound"); }
        return value;
    }
}
