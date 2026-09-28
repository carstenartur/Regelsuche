package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.Objects;

/** Direct counterpart of the canonical expression codec guards; never builds JSON or copies ASTs. */
public final class AstExpressionValidation {
    private AstExpressionValidation() {}
    public record Inspection(long nodes, long textCharacters, long canonicalBytes,long canonicalCharacters) {
        public long work() { return Math.addExact(nodes, textCharacters); }
    }
    public static Inspection inspect(Expr expression) {
        var counter = new Counter();
        long bytes = Math.addExact(27L + CompiledAstReplayCodec.EXPRESSION_SCHEMA.length(), counter.expression(expression, 0));
        if (bytes > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new IllegalArgumentException("invalid AST replay byte length");
        return new Inspection(counter.nodes, counter.characters, bytes,bytes-counter.extraUtf8Bytes);
    }
    /** Same per-state and cumulative history limits as encode, including unexported intermediate states. */
    public static Inspection inspectHistory(CompiledAstRewriteProgram.Candidate history) {
        Objects.requireNonNull(history);
        var counter = new Counter();
        counter.byteGenerator = true;
        long bytes = "{\"schema\":\"\",\"backend\":\"\",\"program\":\"\",\"sourceIds\":[],\"states\":[],\"steps\":[]}".length()
            + CompiledAstReplayCodec.SCHEMA.length() + CompiledAstRewriteProgram.REVISION.length() + counter.text(history.programId());
        for (int i = 0; i < history.sourceIds().size(); i++)
            bytes = Math.addExact(bytes, 2 + counter.text(history.sourceIds().get(i)) + (i == 0 ? 0 : 1));
        long nodes = 0;
        for (int i = 0; i <= history.steps().size(); i++) {
            counter.nodes = 0;
            bytes = Math.addExact(bytes, counter.expression(i == 0 ? history.source() : history.steps().get(i - 1).target(), 0) + (i == 0 ? 0 : 1));
            nodes = Math.addExact(nodes, counter.nodes);
        }
        for (int i = 0; i < history.steps().size(); i++) {
            var step = history.steps().get(i);
            if (step.assumptions().size() > CompiledAstReplayCodec.MAXIMUM_ASSUMPTIONS)
                throw new IllegalArgumentException("too many AST replay assumptions");
            bytes = Math.addExact(bytes, "{\"rule\":\"\",\"kind\":\"\",\"mayIncreaseComplexity\":,\"estimatedCostDelta\":,\"equivalencePreservingByConstruction\":,\"assumptions\":[],\"packId\":\"\",\"license\":\"\"}".length()
                + counter.text(step.rule()) + step.kind().name().length() + (step.mayIncreaseComplexity() ? 4 : 5)
                + integerCharacters(step.estimatedCostDelta()) + (step.equivalencePreservingByConstruction() ? 4 : 5)
                + counter.text(step.packId()) + counter.text(step.license()) + (i == 0 ? 0 : 1));
            for (int j = 0; j < step.assumptions().size(); j++)
                bytes = Math.addExact(bytes, 2 + counter.text(step.assumptions().get(j)) + (j == 0 ? 0 : 1));
        }
        if (bytes > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new IllegalArgumentException("invalid AST replay byte length");
        return new Inspection(nodes, counter.characters, bytes, bytes - counter.extraUtf8Bytes);
    }
    private static int integerCharacters(int value) {
        long magnitude = Math.abs((long) value);
        int count = value < 0 ? 2 : 1;
        while (magnitude >= 10) { magnitude /= 10; count++; }
        return count;
    }
    private static final class Counter {
        long nodes, characters,extraUtf8Bytes;
        boolean byteGenerator;
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
                    // Jackson byte output escapes each supplementary UTF-16 code unit; String output retains the pair.
                    if (byteGenerator) bytes += 12;
                    else { bytes += 4; extraUtf8Bytes += 2; }
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("unpaired Unicode surrogate");
                else if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f') bytes += 2;
                else if (c < 32) bytes += 6;
                else { int size=c < 128 ? 1 : c < 2048 ? 2 : 3;bytes+=size;extraUtf8Bytes+=size-1; }
            }
            return bytes;
        }
    }
}
