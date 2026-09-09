package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Audit;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Result;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Route;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned linear solve requests and fully replayable, content-bound artifacts. */
public final class LinearRepresentationJson {
    public static final String REQUEST_SCHEMA = "regelsuche.linear-solve-request/v1";
    public static final String ARTIFACT_SCHEMA = "regelsuche.linear-solution-artifact/v1";
    public static final int AUDIT_BUDGET = 200_000;
    private LinearRepresentationJson() { }
    public record Request(List<String> equations, Route route, int maxWorkUnits) {
        public Request { equations = List.copyOf(equations); }
    }

    public static Request readRequest(Map<String, ?> object) {
        if (!REQUEST_SCHEMA.equals(object.get("schema"))
                || !Set.of("schema", "equations", "route", "maxWorkUnits").containsAll(object.keySet())) {
            throw new IllegalArgumentException("Unsupported linear solve schema or field");
        }
        if (!(object.get("equations") instanceof List<?> raw)
                || raw.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("equations must be an array of strings");
        }
        Object value = object.containsKey("maxWorkUnits") ? object.get("maxWorkUnits") : 20_000;
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > LinearRepresentationPlanner.MAX_BUDGET) {
            throw new IllegalArgumentException("maxWorkUnits must be an integer between 0 and 1000000");
        }
        Object route = object.containsKey("route") ? object.get("route") : "AUTO";
        if (!(route instanceof String text)) throw new IllegalArgumentException("route must be a string");
        return new Request(raw.stream().map(String.class::cast).toList(), Route.valueOf(text), ((Number) value).intValue());
    }
    public static String solve(Map<String, ?> object) {
        Request request = readRequest(object);
        var planner = new LinearRepresentationPlanner();
        Result result = planner.solve(request.equations, request.route, request.maxWorkUnits);
        return toJson(result, planner.audit(result, AUDIT_BUDGET));
    }
    public static String replay(Map<String, ?> artifact) {
        if (!ARTIFACT_SCHEMA.equals(artifact.get("schema"))) throw new IllegalArgumentException("Unsupported solution artifact");
        Map<String, ?> evidence = object(artifact.get("evidence"));
        String recomputed = solve(object(evidence.get("request")));
        if (!new JsonReader(recomputed).readObject().equals(artifact)) {
            throw new IllegalArgumentException("Solution artifact differs from complete replay");
        }
        return recomputed;
    }
    public static String toJson(Result result, Audit audit) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeArtifact(writer, result, audit);
        return writer.endObject().toString() + "\n";
    }
    public static void writeArtifact(JsonWriter writer, Result result, Audit audit) {
        JsonWriter evidence = new JsonWriter().beginObject();
        writeEvidence(evidence, result, audit);
        writer.property("schema", ARTIFACT_SCHEMA)
            .property("contentHash", hash(evidence.endObject().toString()))
            .object("evidence", w -> writeEvidence(w, result, audit));
    }
    private static void writeEvidence(JsonWriter writer, Result result, Audit audit) {
        writer.object("request", w -> w.property("schema", REQUEST_SCHEMA)
            .stringArray("equations", result.source()).property("route", result.requested().name())
            .property("maxWorkUnits", result.budget()));
        writer.object("result", w -> writeResult(w, result));
        writer.object("audit", w -> {
            w.property("status", audit.status()).property("work", audit.work());
            audit.reference().ifPresent(reference -> w.object("reference", r -> writeResult(r, reference)));
        });
    }
    public static void writeResult(JsonWriter writer, Result result) {
        writer.property("requested", result.requested().name()).property("selected", result.selected().name())
            .property("status", result.status()).stringArray("equations", result.source())
            .stringArray("variables", result.variables()).property("budget", result.budget())
            .property("preparationWork", result.preparationWork()).property("executionWork", result.executionWork())
            .property("compositionWork", result.compositionWork()).property("totalWork", result.totalWork())
            .array("blocks", w -> result.blocks().forEach(rows -> w.arrayValue(a -> rows.forEach(a::value))))
            .array("steps", w -> result.steps().forEach(step -> w.objectValue(s -> s
                .array("sourceRows", a -> step.sourceRows().forEach(a::value)).property("route", step.route())
                .property("status", step.status()).property("work", step.work()).stringArray("operations", step.operations()))));
        result.solution().ifPresent(solution -> writer.object("solution", w -> {
            w.property("classification", solution.classification().name()).stringArray("variables", solution.variables());
            solution.particularSolution().ifPresent(vector -> w.stringArray("particular", vector.values().stream().map(Object::toString).toList()));
            w.array("basis", a -> solution.nullspaceBasis().forEach(vector ->
                a.arrayValue(b -> vector.values().forEach(value -> b.value(value.toString())))));
            solution.normalizedContradiction().ifPresent(value -> w.property("contradiction", value.toString()));
        }));
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static Map<String, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        @SuppressWarnings("unchecked") Map<String, ?> result = (Map<String, ?>) map;
        return result;
    }
}
