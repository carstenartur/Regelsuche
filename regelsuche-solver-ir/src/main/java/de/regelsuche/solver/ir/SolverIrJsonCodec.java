package de.regelsuche.solver.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Predicate;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import de.regelsuche.solver.ir.SolverIr.SymbolDeclaration;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Strict JSON decoder that re-runs all canonical IR invariants and hash checks. */
public final class SolverIrJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public Obligation readObligation(String json) {
        JsonNode root = parse(json);
        List<SymbolDeclaration> declarations = new ArrayList<>();
        for (JsonNode item : array(root, "declarations")) {
            declarations.add(new SymbolDeclaration(
                text(item, "name"),
                Sort.valueOf(text(item, "sort"))));
        }
        List<Theory> theories = new ArrayList<>();
        for (JsonNode item : array(root, "theories")) {
            theories.add(Theory.valueOf(item.asText()));
        }
        List<Predicate> assumptions = new ArrayList<>();
        for (JsonNode item : array(root, "assumptions")) {
            JsonNode right = item.get("right");
            assumptions.add(new Predicate(
                text(item, "id"),
                Relation.valueOf(text(item, "relation")),
                readExpression(required(item, "left")),
                right == null || right.isNull() ? null : readExpression(right)));
        }
        JsonNode goal = required(root, "goal");
        JsonNode provenance = required(root, "provenance");
        return new Obligation(
            text(root, "schema"),
            text(root, "obligationId"),
            declarations,
            theories,
            assumptions,
            new Goal(
                Relation.valueOf(text(goal, "relation")),
                readExpression(required(goal, "left")),
                readExpression(required(goal, "right"))),
            RequestedEvidence.valueOf(text(root, "requestedEvidence")),
            new SourceProvenance(
                text(provenance, "sourceType"),
                text(provenance, "sourceId"),
                text(provenance, "revisionHash")),
            text(root, "contentHash"));
    }

    public SolverResult readResult(String json) {
        JsonNode root = parse(json);
        List<String> capabilities = strings(array(root, "usedCapabilities"));
        List<String> issues = strings(array(root, "translationIssues"));
        Map<String, String> counterexample = new TreeMap<>();
        JsonNode model = required(root, "counterexample");
        Iterator<Map.Entry<String, JsonNode>> fields = model.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            counterexample.put(field.getKey(), field.getValue().asText());
        }
        return new SolverResult(
            text(root, "schema"),
            text(root, "obligationHash"),
            text(root, "goalHash"),
            text(root, "assumptionsHash"),
            text(root, "backendId"),
            text(root, "backendVersion"),
            ResultStatus.valueOf(text(root, "status")),
            TranslationStatus.valueOf(text(root, "translationStatus")),
            capabilities,
            issues,
            text(root, "invocationHash"),
            root.path("message").asText(""),
            counterexample,
            root.path("certificateHash").asText(""),
            text(root, "contentHash"));
    }

    private Expression readExpression(JsonNode node) {
        return switch (text(node, "kind")) {
            case "LITERAL" -> new Literal(text(node, "value"));
            case "SYMBOL" -> new Symbol(text(node, "name"));
            case "BINARY" -> new Binary(
                BinaryOperator.valueOf(text(node, "operator")),
                readExpression(required(node, "left")),
                readExpression(required(node, "right")));
            case "CALL" -> new Call(
                text(node, "function"),
                expressions(array(node, "arguments")));
            default -> throw new IllegalArgumentException(
                "unsupported solver expression kind: " + node.path("kind").asText());
        };
    }

    private List<Expression> expressions(JsonNode values) {
        List<Expression> result = new ArrayList<>();
        values.forEach(value -> result.add(readExpression(value)));
        return List.copyOf(result);
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON must not be blank");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("solver IR JSON must be an object");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid solver IR JSON", exception);
        }
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value;
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.asText();
    }
}
