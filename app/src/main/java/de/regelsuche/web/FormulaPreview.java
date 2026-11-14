package de.regelsuche.web;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** Read-only view of the AST produced by the ordinary Workbench input parser. */
final class FormulaPreview {
    static final int MAX_SOURCE_LENGTH = 256;

    static String parse(String source, String type) {
        if (source.length() > MAX_SOURCE_LENGTH)
            throw new IllegalArgumentException("Die Formelvorschau unterstützt bis zu " + MAX_SOURCE_LENGTH + " Zeichen.");
        InputType inputType = InputType.valueOf(type);
        if (inputType == InputType.SYSTEM) throw new IllegalArgumentException("TERM oder EQUATION erforderlich.");
        var parsed = new ExpressionParser().parse(new InputRequest(inputType, source));
        var result = new JsonWriter().beginObject();
        if (inputType == InputType.EQUATION) {
            Equation equation = parsed.equations().getFirst();
            node(result, "EQUATION", "=", List.of(equation.left(), equation.right()));
        } else write(result, parsed.terms().getFirst());
        return result.endObject().toString();
    }

    private static void write(JsonWriter writer, Expr expression) {
        switch (expression) {
            case NumberExpr n -> node(writer, "NUMBER", n.value().canonicalText(), List.of());
            case VariableExpr v -> node(writer, "VARIABLE", v.name(), List.of());
            case FunctionExpr f -> node(writer, "FUNCTION", f.name(), f.arguments());
            case BinaryExpr b -> node(writer, b.operator().name(), b.operator().symbol(), List.of(b.left(), b.right()));
        }
    }

    private static void node(JsonWriter writer, String kind, String value, List<Expr> children) {
        writer.property("kind", kind).property("value", value)
            .array("children", list -> children.forEach(child -> list.objectValue(item -> write(item, child))));
    }
}
