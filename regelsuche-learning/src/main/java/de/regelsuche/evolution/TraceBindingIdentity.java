package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.PatternBinary;
import de.regelsuche.mining.PatternFunction;
import de.regelsuche.mining.PatternNumber;
import de.regelsuche.mining.PatternVariable;
import de.regelsuche.mining.RulePatternNode;
import java.util.ArrayDeque;

/** Ordered, typed preorder encodings: display formatting is not structural identity. */
final class TraceBindingIdentity {
    static final String REVISION = "regelsuche.trace-binding-identity/v1";

    private TraceBindingIdentity() {}

    static String expression(Expr root) {
        var pending = new ArrayDeque<Expr>();
        pending.push(root);
        var json = new JsonWriter().beginArray();
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (node instanceof NumberExpr number) {
                json.objectValue(value -> value.property("node", "number")
                    .property("numerator", number.value().numerator().toString())
                    .property("denominator", number.value().denominator().toString()));
            } else if (node instanceof VariableExpr variable) {
                // name() is the encoded SymbolId for scoped variables, never a display label.
                json.objectValue(value -> value.property("node", variable.symbol().isPresent() ? "symbol" : "variable")
                    .property("identity", variable.name()));
            } else if (node instanceof BinaryExpr binary) {
                json.objectValue(value -> value.property("node", "binary").property("operator", binary.operator().name()));
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof FunctionExpr function) {
                json.objectValue(value -> value.property("node", "function").property("name", function.name())
                    .property("arity", function.arguments().size()));
                for (int i = function.arguments().size() - 1; i >= 0; i--) pending.push(function.arguments().get(i));
            } else {
                throw new IllegalArgumentException("unsupported expression identity node");
            }
        }
        return json.endArray().toString();
    }

    static String pattern(RulePatternNode root) {
        var pending = new ArrayDeque<RulePatternNode>();
        pending.push(root);
        var json = new JsonWriter().beginArray();
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (node instanceof PatternNumber number) {
                json.objectValue(value -> value.property("node", "number").property("value", number.value()));
            } else if (node instanceof PatternVariable variable) {
                json.objectValue(value -> value.property("node", "placeholder").property("name", variable.name()));
            } else if (node instanceof PatternBinary binary) {
                json.objectValue(value -> value.property("node", "binary").property("operator", binary.op().name()));
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof PatternFunction function) {
                json.objectValue(value -> value.property("node", "function").property("name", function.name())
                    .property("arity", function.arguments().size()));
                for (int i = function.arguments().size() - 1; i >= 0; i--) pending.push(function.arguments().get(i));
            } else {
                throw new IllegalArgumentException("unsupported pattern identity node");
            }
        }
        return json.endArray().toString();
    }
}
