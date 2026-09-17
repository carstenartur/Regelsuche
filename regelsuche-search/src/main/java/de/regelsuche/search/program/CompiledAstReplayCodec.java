package de.regelsuche.search.program;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.CompiledAstRewriteProgram.Candidate;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.RewriteKind;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded interchange of untrusted typed histories. Decoding is not mathematical or execution approval. */
public final class CompiledAstReplayCodec {
    public static final String SCHEMA = "regelsuche.compiled-ast-replay/v1";
    public static final int MAXIMUM_BYTES = 1_048_576;
    public static final int MAXIMUM_TEXT_CHARACTERS = 4_096;
    public static final int MAXIMUM_ASSUMPTIONS = 128;
    private static final Set<String> ROOT_FIELDS = Set.of("schema", "backend", "program", "sourceIds", "states", "steps");
    private static final Set<String> STEP_FIELDS = Set.of("rule", "kind", "mayIncreaseComplexity", "estimatedCostDelta",
        "equivalencePreservingByConstruction", "assumptions", "packId", "license");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(270)
            .maxStringLength(MAXIMUM_TEXT_CHARACTERS).maxNameLength(128).maxNumberLength(32).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Canonical field order and exact typed values. No executable rule or class is serialized. */
    public byte[] encode(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        var data = new AstReplayJson(JSON);
        var root = JSON.createObjectNode().put("schema", SCHEMA).put("backend", CompiledAstRewriteProgram.REVISION)
            .put("program", data.text(candidate.programId()));
        var sources = root.putArray("sourceIds");
        candidate.sourceIds().forEach(id -> sources.add(data.text(id)));
        var states = root.putArray("states");
        candidate.states().forEach(state -> states.add(data.write(state)));
        var steps = root.putArray("steps");
        for (var step : candidate.steps()) {
            var node = steps.addObject().put("rule", data.text(step.rule())).put("kind", step.kind().name())
                .put("mayIncreaseComplexity", step.mayIncreaseComplexity()).put("estimatedCostDelta", step.estimatedCostDelta())
                .put("equivalencePreservingByConstruction", step.equivalencePreservingByConstruction());
            if (step.assumptions().size() > MAXIMUM_ASSUMPTIONS) throw new IllegalArgumentException("too many AST replay assumptions");
            var assumptions = node.putArray("assumptions");
            step.assumptions().forEach(value -> assumptions.add(data.text(value)));
            node.put("packId", data.text(step.packId())).put("license", data.text(step.license()));
        }
        try {
            byte[] result = JSON.writeValueAsBytes(root);
            requireBytes(result);
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot encode AST replay", exception);
        }
    }

    /** Returns data only. Use the receiving program's replayEncoded method for source-bound regeneration. */
    public Candidate decode(byte[] bytes) {
        requireBytes(bytes);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(text);
            AstReplayJson.fields(root, ROOT_FIELDS);
            var data = new AstReplayJson(JSON);
            if (!SCHEMA.equals(data.text(root, "schema")) || !CompiledAstRewriteProgram.REVISION.equals(data.text(root, "backend"))) {
                throw new IllegalArgumentException("unsupported AST replay version");
            }
            var stepNodes = root.get("steps");
            AstReplayJson.array(stepNodes, 1, 8);
            var stateNodes = root.get("states");
            AstReplayJson.array(stateNodes, stepNodes.size() + 1, stepNodes.size() + 1);
            List<String> sourceIds = texts(root.get("sourceIds"), stepNodes.size(), stepNodes.size(), data);
            var states = new ArrayList<Expr>();
            for (var state : stateNodes) states.add(data.read(state));
            var steps = new ArrayList<AstRewriteTransport.Step>();
            for (int i = 0; i < stepNodes.size(); i++) {
                var node = stepNodes.get(i);
                AstReplayJson.fields(node, STEP_FIELDS);
                var assumptions = texts(node.get("assumptions"), 0, MAXIMUM_ASSUMPTIONS, data);
                var step = new AstRewriteTransport.Step(states.get(i), states.get(i + 1), data.text(node, "rule"),
                    RewriteKind.valueOf(data.text(node, "kind")), bool(node, "mayIncreaseComplexity"),
                    integer(node, "estimatedCostDelta"), bool(node, "equivalencePreservingByConstruction"), assumptions,
                    data.text(node, "packId"), data.text(node, "license"));
                if (!step.assumptions().equals(assumptions)) {
                    throw new IllegalArgumentException("noncanonical AST replay assumptions");
                }
                steps.add(step);
            }
            return new Candidate(data.text(root, "program"), sourceIds, steps);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid UTF-8 AST replay JSON", exception);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid AST replay JSON", exception);
        }
    }

    /** Content identity of canonical data, not a signature, rule-set identity or proof. */
    public String contentHash(Candidate candidate) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(candidate)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requireBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("invalid AST replay byte length");
    }

    private static List<String> texts(JsonNode array, int minimum, int maximum, AstReplayJson data) {
        AstReplayJson.array(array, minimum, maximum);
        var values = new ArrayList<String>();
        for (var value : array) {
            if (!value.isTextual()) throw new IllegalArgumentException("expected AST replay text array");
            values.add(data.text(value.textValue()));
        }
        return List.copyOf(values);
    }

    private static boolean bool(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("expected boolean: " + field);
        return value.booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("expected exact 32-bit integer: " + field);
        }
        return value.intValue();
    }
}
