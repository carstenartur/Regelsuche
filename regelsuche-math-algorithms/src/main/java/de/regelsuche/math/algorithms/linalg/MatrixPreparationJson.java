package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.representation.RepresentationBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned request and canonical evidence contract shared by CLI, Workbench and reproduction. */
public final class MatrixPreparationJson {
    public static final String REQUEST_SCHEMA = "regelsuche.matrix-preparation-request/v1";
    public static final String ARTIFACT_SCHEMA = "regelsuche.matrix-preparation-artifact/v1";
    private static final Set<String> REQUEST_FIELDS = Set.of("schema", "equations", "unknowns", "profile",
        "maxWorkUnits", "catalog", "operatorExpressions", "eigenvalueParameter", "nonZeroVector",
        "matrixExpression", "rightHandSide");

    private MatrixPreparationJson() { }

    public static MatrixPreparation.Request readRequest(Map<String, ?> object) {
        if (!REQUEST_SCHEMA.equals(object.get("schema")) || !REQUEST_FIELDS.containsAll(object.keySet())) {
            throw new IllegalArgumentException("unsupported matrix-preparation request schema or field");
        }
        List<MatrixPreparation.NamedMatrix> catalog = new ArrayList<>();
        for (Object value : list(object, "catalog")) {
            Map<String, ?> entry = object(value);
            if (!Set.of("name", "entries", "inverseWitness").containsAll(entry.keySet())) {
                throw new IllegalArgumentException("unsupported matrix declaration field");
            }
            catalog.add(new MatrixPreparation.NamedMatrix(string(entry, "name", ""),
                rows(entry, "entries"), rows(entry, "inverseWitness")));
        }
        return new MatrixPreparation.Request(string(object, "equations", ""), strings(object, "unknowns"),
            MatrixPreparation.Profile.valueOf(string(object, "profile", "SAFE_PREPARED_REPRESENTATION_V1")),
            integer(object, "maxWorkUnits", MatrixPreparation.DEFAULT_WORK), catalog,
            strings(object, "operatorExpressions"), string(object, "eigenvalueParameter", ""),
            bool(object, "nonZeroVector"), string(object, "matrixExpression", ""), strings(object, "rightHandSide"));
    }

    public static String requestJson(MatrixPreparation.Request request) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeRequest(writer, request);
        return writer.endObject().toString();
    }

    public static void writeRequest(JsonWriter writer, MatrixPreparation.Request request) {
        writer.property("schema", REQUEST_SCHEMA).property("equations", request.equations())
            .stringArray("unknowns", request.unknowns()).property("profile", request.profile().name())
            .property("maxWorkUnits", request.maxWorkUnits()).array("catalog", w -> request.catalog().forEach(matrix ->
                w.objectValue(m -> {
                    m.property("name", matrix.name());
                    writeRows(m, "entries", matrix.entries());
                    writeRows(m, "inverseWitness", matrix.inverseWitness());
                }))).stringArray("operatorExpressions", request.operatorExpressions())
            .property("eigenvalueParameter", request.eigenvalueParameter()).property("nonZeroVector", request.nonZeroVector())
            .property("matrixExpression", request.matrixExpression()).stringArray("rightHandSide", request.rightHandSide());
    }

    public static String toJson(MatrixPreparation.Analysis analysis) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", ARTIFACT_SCHEMA).property("contentHash", contentHash(analysis))
            .object("evidence", w -> writeAnalysis(w, analysis));
        return writer.endObject().toString() + "\n";
    }

    /** Recompute the complete retained artifact; a supplied digest alone has no authority. */
    public static String replay(Map<String, ?> artifact) {
        if (!ARTIFACT_SCHEMA.equals(artifact.get("schema"))) {
            throw new IllegalArgumentException("unsupported matrix-preparation artifact schema");
        }
        Map<String, ?> evidence = object(artifact.get("evidence"));
        var request = readRequest(object(evidence.get("request")));
        String recomputed = toJson(new MatrixPreparation().analyze(request));
        if (!new de.regelsuche.json.JsonReader(recomputed).readObject().equals(artifact)) {
            throw new IllegalArgumentException("retained representation evidence does not match complete replay");
        }
        return recomputed;
    }

    public static String contentHash(MatrixPreparation.Analysis analysis) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeAnalysis(writer, analysis);
        return MatrixRepresentationBridge.hash(writer.endObject().toString());
    }

    private static void writeAnalysis(JsonWriter writer, MatrixPreparation.Analysis analysis) {
        writer.property("schema", MatrixPreparation.SCHEMA).property("status", analysis.status().name())
            .property("detailCode", analysis.detailCode()).property("profile", analysis.request().profile().name())
            .object("request", w -> writeRequest(w, analysis.request()));
        writeWork(writer, "work", analysis.work());
        writer.property("workConvention", "MATRIX_PREPARATION_CONSTRUCTION_V1; independent audit reruns use separate bounded budgets")
            .property("physicalInterpretation", "NONE");
        analysis.formation().ifPresent(result -> writer.object("formation", w -> writeFormation(w, result)));
        writer.array("candidates", w -> analysis.attempts().forEach(attempt -> w.objectValue(a -> writeAttempt(a, attempt))));
        analysis.blocks().ifPresent(result -> writer.object("blocks", w -> {
            writeStatus(w, result);
            result.certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
            result.representation().ifPresent(blocks -> {
                w.array("rowPermutation", a -> blocks.rowPermutation().forEach(a::value));
                w.array("columnPermutation", a -> blocks.columnPermutation().forEach(a::value));
                w.stringArray("capabilities", blocks.unlockedCapabilities());
            });
        }));
        analysis.eigenproblem().ifPresent(result -> writer.object("eigenproblem", w -> {
            writeStatus(w, result);
            result.certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
            result.representation().ifPresent(eigen -> {
                w.stringArray("variableOrder", eigen.vectorCoordinates())
                    .property("eigenvalueParameter", eigen.eigenvalueParameter())
                    .stringArray("assumptions", eigen.requiredAssumptions())
                    .stringArray("capabilities", eigen.unlockedCapabilities())
                    .property("modelInterpretation", eigen.modelInterpretation().name());
                MatrixRepresentationBridge.writeMatrix(w, "operator", eigen.operator());
                MatrixRepresentationBridge.writeMatrix(w, "shiftedOperator", eigen.shiftedOperator());
            });
        }));
        writer.object("solving", w -> writeSolving(w, analysis));
    }

    private static void writeFormation(JsonWriter writer,
            RepresentationBridge.Result<SymbolicLinearSystem, SymbolicLinearSystemRepresentationBridge.Certificate> result) {
        writeStatus(writer, result);
        result.certificate().ifPresent(c -> writer.property("certificate", c.contentHash()));
        result.representation().ifPresent(system -> {
            writer.stringArray("variableOrder", system.unknowns()).stringArray("parameters", system.scalarParameters())
                .stringArray("assumptions", List.of()).property("rowCount", system.equationCount())
                .property("columnCount", system.unknownCount());
            MatrixRepresentationBridge.writeMatrix(writer, "coefficients", system.coefficients());
            writer.stringArray("rightHandSide", system.rightHandSide().values().stream().map(Polynomial::toCanonicalString).toList());
            writer.array("sourceRows", w -> system.rowOrigins().forEach(row -> w.objectValue(r ->
                r.property("sourceIndex", row.sourceIndex()).property("equation", row.sourceEquation()))));
        });
    }

    private static void writeAttempt(JsonWriter writer, MatrixPreparation.Attempt attempt) {
        var outcome = attempt.outcome();
        writer.property("origin", attempt.origin()).property("accepted", outcome.accepted())
            .property("detailCode", outcome.detailCode()).stringArray("provenance", attempt.provenance())
            .object("expression", w -> MatrixRepresentationBridge.writeExpression(w, attempt.expression()));
        writeWork(writer, "work", outcome.work());
        writer.object("formation", w -> {
            writeStatus(w, outcome.formation());
            outcome.formation().certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
        });
        outcome.formation().representation().ifPresent(view ->
            writer.stringArray("newlyUnlockedCapabilities", view.newlyUnlockedCapabilities()));
        outcome.replay().ifPresent(result -> writer.object("replay", w -> {
            writeStatus(w, result);
            result.certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
            result.representation().ifPresent(rows -> w.stringArray("leftHandSides",
                rows.stream().map(Polynomial::toCanonicalString).toList()));
        }));
    }

    private static void writeSolving(JsonWriter writer, MatrixPreparation.Analysis analysis) {
        writer.property("phase", "DOWNSTREAM_EXACT_CONSEQUENCES");
        analysis.rowReduction().ifPresent(result -> writer.object("rref", w -> {
            w.property("status", result.status().name()).property("detailCode", result.detailCode());
            w.property("relation", RepresentationBridge.Relation.SOLUTION_SET_EQUIVALENCE.name());
            writeWork(w, "work", result.work());
            w.stringArray("variableOrder", analysis.exactSystem().orElseThrow().variables());
            result.certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
            result.reduction().ifPresent(reduction -> {
                w.property("classification", reduction.solutionClassification().name());
                writeRows(w, "augmentedRows", reduction.reducedAugmentedRows().stream()
                    .map(row -> row.stream().map(Object::toString).toList()).toList());
                w.stringArray("rowOperations", reduction.rowOperations().stream().map(Object::toString).toList());
                reduction.particularSolution().ifPresent(v -> w.stringArray("particularSolution",
                    v.values().stream().map(Object::toString).toList()));
                writeRows(w, "nullspaceBasis", reduction.nullspaceBasis().stream()
                    .map(v -> v.values().stream().map(Object::toString).toList()).toList());
                w.stringArray("newlyUnlockedCapabilities", reduction.capabilityFrontier().newlyUnlocked());
            });
        }));
        analysis.characteristicPolynomial().ifPresent(result -> writer.object("characteristicPolynomial", w -> {
            w.property("status", result.status().name()).property("detailCode", result.detailCode());
            w.property("relation", RepresentationBridge.Relation.SPECTRAL_OR_SIMILARITY_RELATION.name());
            writeWork(w, "work", result.work());
            result.certificate().ifPresent(c -> w.property("certificate", c.contentHash()));
            result.characteristicPolynomial().ifPresent(polynomial -> w.property("equation", polynomial.singularityEquation()));
        }));
    }

    private static void writeStatus(JsonWriter writer, RepresentationBridge.Result<?, ?> result) {
        writer.property("status", result.status().name()).property("detailCode", result.detailCode());
        result.relation().ifPresent(relation -> writer.property("relation", relation.name()));
        writeWork(writer, "work", result.work());
    }

    public static void writeWork(JsonWriter writer, String key, RepresentationBridge.WorkLedger work) {
        writer.object(key, w -> w.property("configured", work.configuredWorkUnits())
            .property("consumed", work.consumedWorkUnits()).property("remaining", work.remainingWorkUnits()));
    }

    private static void writeRows(JsonWriter writer, String key, List<List<String>> rows) {
        writer.array(key, w -> rows.forEach(row -> w.arrayValue(r -> row.forEach(r::value))));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("expected an object");
        }
        return (Map<String, ?>) map;
    }

    private static String string(Map<String, ?> object, String key, String fallback) {
        if (!object.containsKey(key)) {
            return fallback;
        }
        if (!(object.get(key) instanceof String value)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value;
    }

    private static int integer(Map<String, ?> object, String key, int fallback) {
        Object value = object.get(key);
        if (value == null && !object.containsKey(key)) {
            return fallback;
        }
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return Math.toIntExact(((Number) value).longValue());
    }

    private static boolean bool(Map<String, ?> object, String key) {
        if (!object.containsKey(key)) {
            return false;
        }
        if (!(object.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value;
    }

    private static List<?> list(Map<String, ?> object, String key) {
        if (!object.containsKey(key)) {
            return List.of();
        }
        if (!(object.get(key) instanceof List<?> value)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return value;
    }

    private static List<String> strings(Map<String, ?> object, String key) {
        return strings(list(object, key));
    }

    private static List<String> strings(List<?> values) {
        return values.stream().map(value -> {
            if (!(value instanceof String string)) {
                throw new IllegalArgumentException("array entries must be strings");
            }
            return string;
        }).toList();
    }

    private static List<List<String>> rows(Map<String, ?> object, String key) {
        return list(object, key).stream().map(value -> {
            if (!(value instanceof List<?> row)) {
                throw new IllegalArgumentException("matrix rows must be arrays");
            }
            return strings(row);
        }).toList();
    }
}
