package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Formation;
import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Run;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Limits;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.WorkProfile;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction.Pivot;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicit complete byte projection: nested Optional values and concrete RREF lineage are never omitted. */
final class ExactLinearPolynomialPlanJson {
    private ExactLinearPolynomialPlanJson() {}

    static String formationScope(String id, Formation formation, SchematicProofPlan.Limits limits) {
        return new JsonWriter().beginObject()
            .property("resolverId", ExactLinearPolynomialPlanResolver.RESOLVER_ID)
            .property("resolverRevisionHash", ExactLinearPolynomialPlanResolver.REVISION_HASH)
            .property("fragment", ExactLinearPolynomialPlanResolver.FRAGMENT)
            .property("planId", id).property("sourceExpression", formation.sourceExpression())
            .property("ansatzTemplate", formation.ansatzTemplate()).stringArray("holeIds", formation.holeIds())
            .stringArray("assumptions", formation.assumptions()).object("solverLimits", j -> limits(j, formation.solverLimits()))
            .object("planLimits", j -> j.property("maxSteps", limits.maxSteps()).property("maxHoles", limits.maxHoles())
                .property("maxObligations", limits.maxObligations()).property("maxCanonicalBytes", limits.maxCanonicalBytes()))
            .endObject().toString();
    }

    static String run(Run run, boolean includeHash) {
        JsonWriter json = new JsonWriter().beginObject().property("schema", Run.SCHEMA)
            .property("resolverId", ExactLinearPolynomialPlanResolver.RESOLVER_ID)
            .property("resolverRevisionHash", ExactLinearPolynomialPlanResolver.REVISION_HASH)
            .property("fragment", ExactLinearPolynomialPlanResolver.FRAGMENT).property("planHash", run.planHash())
            .object("solverResult", j -> result(j, run.solverResult()));
        if (run.resolution().isPresent()) {
            json.property("resolutionCanonicalJson", run.resolution().orElseThrow().toCanonicalJson());
        } else { json.nullProperty("resolutionCanonicalJson"); }
        if (includeHash) { json.property("contentHash", run.contentHash()); }
        String value = json.endObject().toString();
        if (value.getBytes(StandardCharsets.UTF_8).length > Run.MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException("linear plan run exceeds canonical byte limit");
        }
        return value;
    }

    private static void result(JsonWriter j, ExactLinearPolynomialHoleSolver.Result result) {
        j.property("solverId", ExactLinearPolynomialHoleSolver.SOLVER_ID)
            .property("solverRevision", ExactLinearPolynomialHoleSolver.REVISION)
            .property("contentHash", result.contentHash()).property("sourceExpression", result.sourceExpression())
            .property("ansatzTemplate", result.ansatzTemplate()).stringArray("holeIds", result.holeIds())
            .stringArray("assumptions", result.assumptions()).object("limits", x -> limits(x, result.limits()))
            .property("status", result.status().name()).property("detailCode", result.detailCode())
            .object("work", x -> work(x, result.work()))
            .array("constraints", array -> result.constraints().forEach(row -> array.objectValue(x -> x
                .property("monomial", row.monomial())
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
                    .object("work", work -> work.property("configuredWorkUnits", reduction.work().configuredWorkUnits())
                        .property("consumedWorkUnits", reduction.work().consumedWorkUnits()));
                if (reduction.certificate().isPresent()) {
                    x.object("certificate", c -> certificate(c, reduction.certificate().orElseThrow()));
                } else { x.nullProperty("certificate"); }
                if (reduction.reduction().isPresent()) {
                    x.object("reduction", r -> reduction(r, reduction.reduction().orElseThrow()));
                } else { x.nullProperty("reduction"); }
            });
        } else { j.nullProperty("rrefResult"); }
    }

    static void work(JsonWriter j, WorkProfile work) {
        j.property("configured", work.configured()).property("projection", work.projection())
            .property("constraints", work.constraints()).property("elimination", work.elimination())
            .property("verification", work.verification()).property("consumed", work.consumed()).property("remaining", work.remaining());
    }

    private static void limits(JsonWriter j, Limits limits) {
        j.property("maxHoles", limits.maxHoles()).property("maxMonomials", limits.maxMonomials())
            .property("maxScalarBits", limits.maxScalarBits()).property("maxWorkUnits", limits.maxWorkUnits());
    }

    private static void certificate(JsonWriter j, ExactRrefSolver.Certificate certificate) {
        j.property("schema", certificate.schema()).property("solverId", certificate.solverId())
            .property("relation", certificate.relation().name()).property("sourceSystemHash", certificate.sourceSystemHash())
            .property("solutionClassification", certificate.solutionClassification().name())
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
        matrix(j, "reducedAugmentedRows", reduction.reducedAugmentedRows().stream()
            .map(row -> row.stream().map(Rational::toString).toList()).toList());
        pivots(j, reduction.coefficientPivots());
        integers(j, "freeVariableColumns", reduction.freeVariableColumns());
        integers(j, "contradictionRows", reduction.contradictionRows());
        if (reduction.particularSolution().isPresent()) {
            j.stringArray("particularSolution", vector(reduction.particularSolution().orElseThrow()));
        } else { j.nullProperty("particularSolution"); }
        matrix(j, "nullspaceBasis", reduction.nullspaceBasis().stream().map(ExactLinearPolynomialPlanJson::vector).toList());
        j.stringArray("rowOperations", reduction.rowOperations().stream().map(ExactRrefReduction.RowOperation::canonicalForm).toList())
            .object("capabilityFrontier", x -> x.stringArray("applicableBefore", reduction.capabilityFrontier().applicableBefore())
                .stringArray("applicableAfter", reduction.capabilityFrontier().applicableAfter())
                .stringArray("newlyUnlocked", reduction.capabilityFrontier().newlyUnlocked())
                .stringArray("lostOrConditional", reduction.capabilityFrontier().lostOrConditional()));
    }

    private static List<String> vector(ExactVector vector) { return vector.values().stream().map(Rational::toString).toList(); }
    private static void integers(JsonWriter j, String key, List<Integer> values) {
        j.array(key, array -> values.forEach(array::value));
    }
    private static void matrix(JsonWriter j, String key, List<List<String>> rows) {
        j.array(key, array -> rows.forEach(row -> array.arrayValue(values -> row.forEach(values::value))));
    }
    private static void pivots(JsonWriter j, List<Pivot> pivots) {
        j.array("coefficientPivots", array -> pivots.forEach(pivot -> array.objectValue(x ->
            x.property("row", pivot.row()).property("column", pivot.column()))));
    }
}
