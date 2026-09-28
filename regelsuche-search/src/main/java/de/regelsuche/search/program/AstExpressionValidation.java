package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.Objects;

/** Direct counterpart of the canonical expression codec guards; never builds JSON or copies ASTs. */
public final class AstExpressionValidation {
    private AstExpressionValidation() {}
    public record Inspection(long nodes, long textCharacters, long canonicalBytes) {
        public long work() { return Math.addExact(nodes, textCharacters); }
    }
    public static Inspection inspect(Expr expression) {
        var counter = new Counter();
        long bytes = Math.addExact(27L + CompiledAstReplayCodec.EXPRESSION_SCHEMA.length(), counter.expression(expression, 0));
        if (bytes > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new IllegalArgumentException("invalid AST replay byte length");
        return new Inspection(counter.nodes, counter.characters, bytes);
    }
    private static final class Counter {
        long nodes, characters;
        long expression(Expr expression, int depth) {
            Objects.requireNonNull(expression);
            if (++nodes > AstRewriteTransport.MAXIMUM_NODES || depth > AstRewriteTransport.MAXIMUM_DEPTH)
                throw new IllegalArgumentException("AST replay structural limit exceeded");
            return switch (expression) {
                case NumberExpr number -> {
                    if (number.value().numerator().bitLength() > 4 * CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS
                            || number.value().denominator().bitLength() > 4 * CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS)
                        throw new IllegalArgumentException("AST replay numeric literal is too large");
                    yield 28 + text(number.value().canonicalText());
                }
                case VariableExpr variable -> variable.symbol().isPresent()
                    ? 25 + text(variable.symbol().orElseThrow().canonicalText()) : 29 + text(variable.name());
                case BinaryExpr binary -> 48L + binary.operator().name().length()
                    + expression(binary.left(), depth + 1) + expression(binary.right(), depth + 1);
                case FunctionExpr function -> {
                    if (function.arguments().size() > AstRewriteTransport.MAXIMUM_NODES - nodes)
                        throw new IllegalArgumentException("AST replay argument count exceeded");
                    long size = 44 + text(function.name());
                    for (int i = 0; i < function.arguments().size(); i++)
                        size = Math.addExact(size, expression(function.arguments().get(i), depth + 1) + (i == 0 ? 0 : 1));
                    yield size;
                }
            };
        }
        long text(String value) {
            if (value == null || value.isBlank() || value.length() > CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS)
                throw new IllegalArgumentException("invalid or oversized AST replay text");
            characters = Math.addExact(characters, value.length());
            if (characters > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new IllegalArgumentException("AST replay text limit exceeded");
            long bytes = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("unpaired Unicode surrogate");
                    bytes += 4;
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("unpaired Unicode surrogate");
                else if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f') bytes += 2;
                else if (c < 32) bytes += 6;
                else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
            }
            return bytes;
        }
    }
}
