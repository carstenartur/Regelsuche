package de.regelsuche.web;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputType;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExactParsedTerm.SourceRange;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** AST and source occurrences produced by the ordinary Workbench input parser. */
final class FormulaPreview {
    static final int MAX_SOURCE_LENGTH = 256;

    static String parse(String source, String type) {
        if (source.length() > MAX_SOURCE_LENGTH)
            throw new IllegalArgumentException("Die Formelvorschau unterstützt bis zu " + MAX_SOURCE_LENGTH + " Zeichen.");
        InputType inputType = InputType.valueOf(type);
        var parser = new ExpressionParser();
        var result = new JsonWriter().beginObject();
        if (inputType == InputType.SYSTEM) {
            var equations = parser.parseExactSystem(source);
            if (equations.isEmpty()) throw new IllegalArgumentException("Das Gleichungssystem benötigt mindestens eine Gleichung.");
            result.property("kind", "SYSTEM").property("value", ";").array("children",
                rows -> equations.forEach(row -> rows.objectValue(item -> writeEquation(item, row))));
        } else if (inputType == InputType.EQUATION) {
            writeEquation(result, parser.parseExactEquation(source));
        } else {
            var parsed = parser.parseExactTerm(source);
            write(result, parsed.expression(), parsed::sourceRangeFor);
        }
        return result.endObject().toString();
    }

    private static void writeEquation(JsonWriter result, de.regelsuche.parse.ExactParsedEquation parsed) {
        var equation = parsed.equation();
        result.property("kind", "EQUATION").property("value", "=");
        writeChildren(result, List.of(equation.left(), equation.right()), parsed::sourceRangeFor);
    }

    private static void write(JsonWriter writer, Expr expression, Function<Expr, Optional<SourceRange>> ranges) {
        List<Expr> children = switch (expression) {
            case NumberExpr n -> {
                writer.property("kind", "NUMBER").property("value", n.value().canonicalText());
                yield List.of();
            }
            case VariableExpr v -> {
                writer.property("kind", "VARIABLE").property("value", v.name());
                yield List.of();
            }
            case FunctionExpr f -> {
                writer.property("kind", "FUNCTION").property("value", f.name());
                yield f.arguments();
            }
            case BinaryExpr b -> {
                writer.property("kind", b.operator().name()).property("value", b.operator().symbol());
                yield List.of(b.left(), b.right());
            }
        };
        ranges.apply(expression).ifPresent(range -> writer.object("sourceRange", source -> source
            .property("startInclusive", range.startInclusive())
            .property("endExclusive", range.endExclusive())));
        writeChildren(writer, children, ranges);
    }

    private static void writeChildren(JsonWriter writer, List<Expr> children, Function<Expr, Optional<SourceRange>> ranges) {
        writer.array("children", list -> children.forEach(child -> list.objectValue(item -> write(item, child, ranges))));
    }
}
