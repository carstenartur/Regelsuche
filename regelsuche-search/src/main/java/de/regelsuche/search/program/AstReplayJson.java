package de.regelsuche.search.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/** Data-only tagged AST representation; never invokes the expression parser or imports classes. */
final class AstReplayJson {
    private final ObjectMapper mapper;
    private int textCharacters;

    AstReplayJson(ObjectMapper mapper) { this.mapper = mapper; }

    ObjectNode write(Expr expression) { return write(expression, 0, new int[1]); }
    Expr read(JsonNode node) { return read(node, 0, new int[1]); }

    private ObjectNode write(Expr expression, int depth, int[] nodes) {
        visit(depth, nodes);
        Objects.requireNonNull(expression, "expression");
        var node = mapper.createObjectNode();
        switch (expression) {
            case NumberExpr number -> {
                // Bound conversion of arbitrarily large in-memory values before decimal rendering.
                if (number.value().numerator().bitLength() > 4 * CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS
                        || number.value().denominator().bitLength() > 4 * CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS) {
                    throw new IllegalArgumentException("AST replay numeric literal is too large");
                }
                node.put("type", "number").put("value", text(number.value().canonicalText()));
            }
            case VariableExpr variable -> {
                if (variable.symbol().isPresent()) {
                    node.put("type", "symbol").put("id", text(variable.symbol().orElseThrow().canonicalText()));
                } else node.put("type", "variable").put("name", text(variable.name()));
            }
            case BinaryExpr binary -> {
                node.put("type", "binary").put("operator", binary.operator().name());
                node.set("left", write(binary.left(), depth + 1, nodes));
                node.set("right", write(binary.right(), depth + 1, nodes));
            }
            case FunctionExpr function -> {
                node.put("type", "function").put("name", text(function.name()));
                if (function.arguments().size() > AstRewriteTransport.MAXIMUM_NODES - nodes[0]) {
                    throw new IllegalArgumentException("AST replay argument count exceeded");
                }
                var arguments = node.putArray("arguments");
                function.arguments().forEach(argument -> arguments.add(write(argument, depth + 1, nodes)));
            }
        }
        return node;
    }

    private Expr read(JsonNode node, int depth, int[] nodes) {
        visit(depth, nodes);
        return switch (text(node, "type")) {
            case "number" -> {
                fields(node, Set.of("type", "value"));
                yield new NumberExpr(ExactRational.fromCanonicalText(text(node, "value")));
            }
            case "variable" -> {
                fields(node, Set.of("type", "name"));
                var variable = new VariableExpr(text(node, "name"));
                if (variable.symbol().isPresent()) throw new IllegalArgumentException("scoped symbol requires a symbol tag");
                yield variable;
            }
            case "symbol" -> {
                fields(node, Set.of("type", "id"));
                yield VariableExpr.scoped(SymbolId.fromCanonicalText(text(node, "id")));
            }
            case "binary" -> {
                fields(node, Set.of("type", "operator", "left", "right"));
                var operator = BinaryOperator.valueOf(text(node, "operator"));
                yield new BinaryExpr(read(node.get("left"), depth + 1, nodes), operator,
                    read(node.get("right"), depth + 1, nodes));
            }
            case "function" -> {
                fields(node, Set.of("type", "name", "arguments"));
                String name = text(node, "name");
                var array = node.get("arguments");
                array(array, 0, AstRewriteTransport.MAXIMUM_NODES - nodes[0]);
                var arguments = new ArrayList<Expr>();
                for (var argument : array) arguments.add(read(argument, depth + 1, nodes));
                yield new FunctionExpr(name, arguments);
            }
            default -> throw new IllegalArgumentException("unknown AST replay node type");
        };
    }

    private static void visit(int depth, int[] nodes) {
        if (depth > AstRewriteTransport.MAXIMUM_DEPTH || ++nodes[0] > AstRewriteTransport.MAXIMUM_NODES) {
            throw new IllegalArgumentException("AST replay structural limit exceeded");
        }
    }

    String text(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("expected text field: " + field);
        return text(value.textValue());
    }

    String text(String value) {
        if (value == null || value.isBlank() || value.length() > CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS) {
            throw new IllegalArgumentException("invalid or oversized AST replay text");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("unpaired Unicode surrogate");
                }
            } else if (Character.isLowSurrogate(character)) throw new IllegalArgumentException("unpaired Unicode surrogate");
        }
        textCharacters = Math.addExact(textCharacters, value.length());
        if (textCharacters > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new IllegalArgumentException("AST replay text limit exceeded");
        return value;
    }

    static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()) {
            throw new IllegalArgumentException("missing or unexpected AST replay fields");
        }
        for (var entry : node.properties()) {
            if (!names.contains(entry.getKey())) throw new IllegalArgumentException("unknown AST replay field");
        }
    }

    static void array(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isArray() || node.size() < minimum || node.size() > maximum) {
            throw new IllegalArgumentException("invalid AST replay array size");
        }
    }
}
