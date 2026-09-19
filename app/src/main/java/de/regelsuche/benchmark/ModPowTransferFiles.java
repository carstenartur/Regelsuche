package de.regelsuche.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.mining.TypedPatternGeneralizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small bounded research wire format; decoding never authorizes an arithmetic rule. */
final class ModPowTransferFiles {
    static final int MAX_BYTES = 1_048_576;
    static final String CORPUS_SCHEMA = "regelsuche.modpow-transfer-corpus/v1";
    static final String MODEL_SCHEMA = "regelsuche.modpow-transfer-model/v1";
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(150)
            .maxStringLength(16_384).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    record Row(String id, FunctionExpr source, Map<String, String> roles,
            int qBits, int eBits, int rBits, boolean positive, List<String> assumptions) {
        Row { roles = Map.copyOf(roles); assumptions = List.copyOf(assumptions); }
        Row withSource(FunctionExpr replacement) {
            return new Row(id, replacement, roles, qBits, eBits, rBits, positive, assumptions);
        }
    }

    private ModPowTransferFiles() {}

    static byte[] read(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            require(bytes.length <= MAX_BYTES, "research input too large");
            return bytes;
        }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static List<Row> rows(byte[] bytes, String split) throws IOException {
        JsonNode root = JSON.readTree(bytes);
        fields(root, Set.of("schema", "split", "cases"));
        require(CORPUS_SCHEMA.equals(text(root, "schema")) && split.equals(text(root, "split")), "wrong corpus split/schema");
        JsonNode cases = root.get("cases");
        require(cases.isArray() && cases.size() >= 1 && cases.size() <= 32, "require 1 through 32 cases");
        var result = new ArrayList<Row>(); var ids = new HashSet<String>();
        for (var row : cases) {
            fields(row, Set.of("id", "source", "roles", "qBits", "eBits", "rBits", "positive", "assumptions"));
            String id = text(row, "id");
            require(id.length() <= 64 && ids.add(id), "duplicate/oversized case id");
            String source = text(row, "source"); require(source.length() <= 4096, "source too long");
            fields(row.get("roles"), Set.of("a", "q", "e", "n", "r"));
            var roles = new LinkedHashMap<String, String>();
            for (String role : List.of("a", "q", "e", "n", "r")) {
                String name = text(row.get("roles"), role);
                require(name.matches("[a-zA-Z][a-zA-Z0-9_]*"), "simple named role required"); roles.put(role, name);
            }
            require(new HashSet<>(roles.values()).size() == roles.size(), "role renaming must be injective");
            Expr parsed = new ExpressionParser().parseTerm(source);
            require(parsed instanceof FunctionExpr, "output program required");
            FunctionExpr program = (FunctionExpr) parsed;
            require(program.name().equals("program") && program.arguments().size() >= 2 && program.arguments().size() <= 8,
                "require 2 through 8 ordered program outputs");
            require(row.get("positive").isBoolean(), "boolean classification required");
            require(row.get("assumptions").isArray() && row.get("assumptions").size() <= 32, "bounded assumptions required");
            var assumptions = new ArrayList<String>();
            for (var assumption : row.get("assumptions")) {
                require(assumption.isTextual() && assumption.textValue().length() <= 256, "textual assumption required");
                assumptions.add(assumption.textValue());
            }
            result.add(new Row(id, program, roles, bits(row, "qBits"), bits(row, "eBits"), bits(row, "rBits"),
                row.get("positive").booleanValue(), assumptions));
        }
        return List.copyOf(result);
    }

    static ObjectNode template(PatternExpr pattern) {
        var node = JSON.createObjectNode();
        if (pattern instanceof PatternExpr.Placeholder hole) return node.put("kind", "hole").put("name", hole.name());
        if (pattern instanceof PatternExpr.LiteralNumber number) return node.put("kind", "number").put("value", number.value().canonicalText());
        if (pattern instanceof PatternExpr.Operation op) {
            node.put("kind", "operation").put("operator", op.operator().name());
            node.set("left", template(op.left())); node.set("right", template(op.right())); return node;
        }
        if (pattern instanceof PatternExpr.Function function) {
            node.put("kind", "function").put("name", function.name());
            var arguments = node.putArray("arguments"); function.arguments().forEach(arg -> arguments.add(template(arg))); return node;
        }
        throw new IllegalArgumentException("unexpected literal variable in typed hypothesis");
    }

    static TypedPatternGeneralizer.Candidate candidate(JsonNode model) {
        fields(model, Set.of("schema", "trainSha256", "sourceTemplate", "targetTemplate", "witnesses", "shuffledWitnessCount"));
        require(MODEL_SCHEMA.equals(text(model, "schema")), "wrong model schema");
        require(text(model, "trainSha256").matches("[0-9a-f]{64}"), "invalid training digest");
        require(model.get("witnesses").isArray() && model.get("witnesses").size() == 2, "exactly two retained witnesses required");
        require(model.get("shuffledWitnessCount").isIntegralNumber() && model.get("shuffledWitnessCount").intValue() == 0,
            "shuffled control not clean");
        // Observations remain in the wire artifact. This syntax-only candidate has no proof authority.
        return new TypedPatternGeneralizer.Candidate(pattern(model.get("sourceTemplate"), 0, new int[1]),
            pattern(model.get("targetTemplate"), 0, new int[1]), List.of(), List.of());
    }

    private static PatternExpr pattern(JsonNode node, int depth, int[] count) {
        require(depth <= 64 && ++count[0] <= 1024, "template structural limit");
        String kind = text(node, "kind");
        return switch (kind) {
            case "hole" -> {
                fields(node, Set.of("kind", "name")); String name = text(node, "name");
                require(name.matches("P[0-9]{1,4}"), "invalid hole"); yield PatternExpr.var(name);
            }
            case "number" -> { fields(node, Set.of("kind", "value")); yield PatternExpr.num(text(node, "value")); }
            case "operation" -> {
                fields(node, Set.of("kind", "operator", "left", "right"));
                yield PatternExpr.op(BinaryOperator.valueOf(text(node, "operator")),
                    pattern(node.get("left"), depth + 1, count), pattern(node.get("right"), depth + 1, count));
            }
            case "function" -> {
                fields(node, Set.of("kind", "name", "arguments"));
                require(node.get("arguments").isArray() && node.get("arguments").size() <= 1024, "bounded arguments required");
                var arguments = new ArrayList<PatternExpr>();
                for (var arg : node.get("arguments")) arguments.add(pattern(arg, depth + 1, count));
                yield new PatternExpr.Function(text(node, "name"), arguments);
            }
            default -> throw new IllegalArgumentException("unknown template kind");
        };
    }

    private static int bits(JsonNode row, String key) {
        JsonNode node = row.get(key);
        require(node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0 && node.intValue() <= 4096, "invalid bit profile");
        return node.intValue();
    }
    static String text(JsonNode node, String key) {
        require(node != null && node.isObject() && node.has(key) && node.get(key).isTextual()
            && !node.get(key).textValue().isBlank(), "missing text: " + key);
        return node.get(key).textValue();
    }
    static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject(), "object required");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "unexpected/missing fields: " + actual);
    }
    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
