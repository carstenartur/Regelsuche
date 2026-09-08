package de.regelsuche.transform;

import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Portable observation of the canonical execution provenance, never an execution
 * capability. Loading public bytes cannot construct an {@link ExactTheoryEvidence}.
 * A replay must obtain fresh transformations from trusted sources and compare the
 * entire observation, including every intermediate expression and evidence byte.
 */
public final class RecordedExecution {
    public static final String SCHEMA = "regelsuche.recorded-execution/v1";
    public static final int MAX_CODE_UNITS = 8_000_000;
    public static final int MAX_NESTING = 64;
    private final String sourceExpression;
    private final String transformedExpression;
    private final String provenance;
    private final ExecutionWork work;
    private final int edgeCount;
    private final String canonicalJson;
    private final String contentHash;

    private RecordedExecution(String source, String output, String provenance, ExecutionWork work, int edgeCount) {
        this.sourceExpression = source;
        this.transformedExpression = output;
        this.provenance = provenance;
        this.work = work;
        this.edgeCount = edgeCount;
        canonicalJson = new JsonWriter().beginObject().property("schema", SCHEMA)
            .property("sourceExpression", source).property("transformedExpression", output)
            .property("provenance", provenance).property("edgeCount", edgeCount)
            .property("primitiveRewrites", work.primitiveRewrites())
            .property("exactTheorySteps", work.exactTheorySteps())
            .property("exactTheoryWorkUnits", work.exactTheoryWorkUnits())
            .property("canonicalWorkUnits", work.canonicalWorkUnits()).endObject().toString();
        if (canonicalJson.length() > MAX_CODE_UNITS) throw new IllegalArgumentException("execution artifact too large");
        contentHash = hash(canonicalJson);
    }

    public static RecordedExecution capture(String source, List<Transformation> steps) {
        requireText(source);
        var retained = List.copyOf(steps);
        if (retained.isEmpty()) return new RecordedExecution(source, source, "", ExecutionWork.ZERO, 0);
        var sequence = new TransformationProvenance.Sequence(source, retained);
        return new RecordedExecution(source, retained.getLast().transformedExpression(),
            sequence.toCanonicalJson(), sequence.work(), retained.size());
    }

    /** Strict, bounded observational loading; no evidence provider is invoked. */
    public static RecordedExecution fromCanonicalJson(String json) {
        var values = readObject(json);
        if (!SCHEMA.equals(text(values, "schema"))) throw new IllegalArgumentException("unsupported execution schema");
        String source = text(values, "sourceExpression");
        String output = text(values, "transformedExpression");
        String provenance = string(values, "provenance");
        Summary summary = provenance.isEmpty() ? new Summary(source, ExecutionWork.ZERO, 0)
            : summarize(provenance, source, 0, new ParseAllowance());
        if (!output.equals(summary.output())) throw new IllegalArgumentException("execution endpoint differs from provenance");
        var result = new RecordedExecution(source, output, provenance, summary.work(), summary.edges());
        // Binds all derived counters, field names, order, integer syntax and omitted fields.
        if (!result.toCanonicalJson().equals(json)) throw new IllegalArgumentException("noncanonical or inconsistent execution artifact");
        return result;
    }

    public String sourceExpression() { return sourceExpression; }
    public String transformedExpression() { return transformedExpression; }
    public String provenance() { return provenance; }
    public ExecutionWork work() { return work; }
    public int edgeCount() { return edgeCount; }
    public String toCanonicalJson() { return canonicalJson; }
    public String contentHash() { return contentHash; }

    public List<String> assumptions() {
        if (edgeCount == 0) return List.of();
        return de.regelsuche.assumption.AssumptionSignature.ofExpressions(list(readObject(provenance), "steps").stream()
            .flatMap(step -> list(object(step), "assumptions").stream()).map(String.class::cast).toList()).normalizedAssumptions();
    }

    public static void writeOptional(JsonWriter writer, RecordedExecution execution) {
        if (execution != null) writer.property("execution", execution.toCanonicalJson());
    }

    public static RecordedExecution readOptional(Map<String, Object> values) {
        Object value = values.get("execution");
        if (value == null) return null;
        if (!(value instanceof String json)) throw new IllegalArgumentException("expected canonical execution JSON string");
        return fromCanonicalJson(json);
    }

    public void requireEndpoints(String source, String output) {
        if (!sourceExpression.equals(source) || !transformedExpression.equals(output)) {
            throw new IllegalArgumentException("recorded execution differs from its owning expressions");
        }
    }

    public void requireReplay(String source, List<Transformation> independentlyVerifiedSteps) {
        if (!equals(capture(source, independentlyVerifiedSteps))) {
            throw new IllegalArgumentException("execution replay differs in provenance, evidence or work");
        }
    }

    public void requireStep(String source, String output, String rule, RewriteKind kind,
            boolean equivalencePreserving, List<String> assumptions) {
        requireStep(source, output, rule, equivalencePreserving);
        var step = object(list(readObject(provenance), "steps").getFirst());
        if (!text(step, "kind").equals(kind.name()) || !list(step, "assumptions").equals(assumptions)) {
            throw new IllegalArgumentException("step metadata differs from retained execution");
        }
    }

    public void requireStep(String source, String output, String rule, boolean equivalencePreserving) {
        requireEndpoints(source, output);
        if (edgeCount != 1) throw new IllegalArgumentException("step record requires exactly one outer edge");
        var step = object(list(readObject(provenance), "steps").getFirst());
        if (!text(step, "rule").equals(rule) || !Objects.equals(step.get("equivalencePreserving"), equivalencePreserving)) {
            throw new IllegalArgumentException("step metadata differs from retained execution");
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof RecordedExecution recorded && canonicalJson.equals(recorded.canonicalJson);
    }
    @Override public int hashCode() { return canonicalJson.hashCode(); }
    @Override public String toString() { return canonicalJson; }

    private static Summary summarize(String json, String source, int depth, ParseAllowance allowance) {
        if (depth > MAX_NESTING) throw new IllegalArgumentException("execution provenance nesting exceeded");
        allowance.consume(json.length());
        var value = readObject(json);
        if (!TransformationProvenance.SCHEMA.equals(text(value, "schema"))) {
            throw new IllegalArgumentException("unsupported provenance schema");
        }
        return switch (text(value, "kind")) {
            case "PRIMITIVE_REWRITE_SEQUENCE" -> {
                requireKeys(value, "schema", "kind", "primitiveRuleIds", "applicationKey");
                var rules = list(value, "primitiveRuleIds");
                if (rules.isEmpty()) throw new IllegalArgumentException("empty primitive lineage");
                rules.forEach(rule -> requireText((String) rule));
                text(value, "applicationKey");
                yield new Summary(null, new ExecutionWork(rules.size(), 0, 0), 1);
            }
            case "EXACT_THEORY_STEP" -> {
                requireKeys(value, "schema", "kind", "sourceExpression", "transformedExpression", "theoryStepId",
                    "evidenceHash", "receiptArtifactId", "runArtifactId", "canonicalWorkUnits", "assumptions", "canonicalEvidenceJson");
                if (!source.equals(text(value, "sourceExpression")) || !list(value, "assumptions").isEmpty()) {
                    throw new IllegalArgumentException("theory source or assumptions substituted");
                }
                // Binding is explicitly observational, not accepted by fromVerified.
                var binding = new ExactTheoryEvidence.Binding(source, text(value, "transformedExpression"),
                    text(value, "theoryStepId"), text(value, "evidenceHash"), text(value, "receiptArtifactId"),
                    text(value, "runArtifactId"), integer(value, "canonicalWorkUnits"), text(value, "canonicalEvidenceJson"));
                yield new Summary(binding.transformedExpression(), new ExecutionWork(0, 1, binding.canonicalWorkUnits()), 1);
            }
            case "SEQUENCE" -> {
                requireKeys(value, "schema", "kind", "sourceExpression", "steps");
                if (!source.equals(text(value, "sourceExpression"))) throw new IllegalArgumentException("sequence source substituted");
                var steps = list(value, "steps");
                if (steps.isEmpty()) throw new IllegalArgumentException("empty provenance sequence");
                String current = source;
                ExecutionWork total = ExecutionWork.ZERO;
                for (Object raw : steps) {
                    var step = object(raw);
                    requireKeys(step, "rule", "output", "applicationKey", "kind", "mayIncreaseComplexity", "estimatedCostDelta",
                        "equivalencePreserving", "assumptions", "packId", "license", "provenance");
                    text(step, "rule"); text(step, "applicationKey"); text(step, "packId"); text(step, "license");
                    RewriteKind.valueOf(text(step, "kind"));
                    bool(step, "mayIncreaseComplexity"); bool(step, "equivalencePreserving");
                    Math.toIntExact(integer(step, "estimatedCostDelta"));
                    list(step, "assumptions").forEach(assumption -> requireText((String) assumption));
                    Summary nested = summarize(text(step, "provenance"), current, depth + 1, allowance);
                    current = text(step, "output");
                    if (nested.output() != null && !nested.output().equals(current)) {
                        throw new IllegalArgumentException("nested execution endpoint substituted");
                    }
                    total = total.plus(nested.work());
                }
                yield new Summary(current, total, steps.size());
            }
            default -> throw new IllegalArgumentException("unknown execution provenance kind");
        };
    }

    private static Map<String, Object> readObject(String json) {
        Objects.requireNonNull(json, "json");
        if (json.length() > MAX_CODE_UNITS) throw new IllegalArgumentException("execution artifact too large");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if ((c == '{' || c == '[') && ++depth > MAX_NESTING) {
                throw new IllegalArgumentException("execution JSON nesting exceeded");
            } else if (c == '}' || c == ']') depth--;
        }
        try { return new JsonReader(json).readObject(); }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("malformed execution JSON", malformed); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("expected execution object");
        return (Map<String, Object>) value;
    }
    private static List<?> list(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof List<?> result)) throw new IllegalArgumentException("expected execution array: " + key);
        return result;
    }
    private static String string(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String result)) throw new IllegalArgumentException("expected execution string: " + key);
        return result;
    }
    private static String text(Map<String, Object> values, String key) {
        String result = string(values, key); requireText(result); return result;
    }
    private static long integer(Map<String, Object> values, String key) {
        Object result = values.get(key);
        if (!(result instanceof Integer) && !(result instanceof Long)) throw new IllegalArgumentException("expected execution integer: " + key);
        return ((Number) result).longValue();
    }
    private static void bool(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof Boolean)) throw new IllegalArgumentException("expected execution boolean: " + key);
    }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank execution field");
    }
    private static void requireKeys(Map<String, Object> values, String... keys) {
        if (!values.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("unexpected execution fields");
    }
    private static String hash(String value) {
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException malformed) { throw new IllegalArgumentException("invalid execution Unicode", malformed); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Summary(String output, ExecutionWork work, int edges) { }
    private static final class ParseAllowance {
        private long remaining = MAX_CODE_UNITS * 4L;
        void consume(long units) {
            remaining -= units;
            if (remaining < 0) throw new IllegalArgumentException("execution decoding work exceeded");
        }
    }
}
