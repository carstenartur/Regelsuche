package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.ArrayList;
import java.util.Objects;

/** Direct counterpart of the canonical expression codec guards; never builds JSON or copies ASTs. */
public final class AstExpressionValidation {
    private AstExpressionValidation() {}
    /** Intentional syntax/transport rejection; unrelated observer exceptions retain their identity. */
    public static final class InvalidExpression extends IllegalArgumentException {
        public InvalidExpression(String reason) { super(reason); }
    }
    public record Inspection(long nodes, long textCharacters, long canonicalBytes,long canonicalCharacters) {
        public long work() { return Math.addExact(nodes, textCharacters); }
    }
    public static Inspection inspect(Expr expression) {
        return inspect(expression, null);
    }
    /** Same per-state and cumulative history limits as encode, including unexported intermediate states. */
    public static Inspection inspectHistory(CompiledAstRewriteProgram.Candidate history) {
        return inspect(null, Objects.requireNonNull(history));
    }
    private static Inspection inspect(Expr expression, CompiledAstRewriteProgram.Candidate history) {
        var counter = new Counter(history == null ? expression : history);
        RetainedOperation.Frame retained = null;
        try {
            retained = RetainedOperation.retainCompleted(2, counter);
            var inspection = history == null ? inspectExpression(expression, counter) : inspectHistory(history, counter);
            counter.checkpointIfGrown();
            counter.release();
            if (retained != null) retained.close();
            return inspection;
        } catch (RuntimeException | Error failure) {
            counter.cleanup(retained, failure);
            throw failure;
        }
    }
    private static Inspection inspectExpression(Expr expression, Counter counter) {
        long bytes = Math.addExact(27L + CompiledAstReplayCodec.EXPRESSION_SCHEMA.length(), counter.expression(expression));
        if (bytes > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new InvalidExpression("invalid AST replay byte length");
        return new Inspection(counter.nodes, counter.characters, bytes,bytes-counter.extraUtf8Bytes);
    }
    private static Inspection inspectHistory(CompiledAstRewriteProgram.Candidate history, Counter counter) {
        counter.byteGenerator = true;
        long bytes = "{\"schema\":\"\",\"backend\":\"\",\"program\":\"\",\"sourceIds\":[],\"states\":[],\"steps\":[]}".length()
            + CompiledAstReplayCodec.SCHEMA.length() + CompiledAstRewriteProgram.REVISION.length() + counter.text(history.programId());
        for (int i = 0; i < history.sourceIds().size(); i++)
            bytes = Math.addExact(bytes, 2 + counter.text(history.sourceIds().get(i)) + (i == 0 ? 0 : 1));
        long nodes = 0;
        for (int i = 0; i <= history.steps().size(); i++) {
            counter.nodes = 0;
            bytes = Math.addExact(bytes, counter.expression(i == 0 ? history.source() : history.steps().get(i - 1).target()) + (i == 0 ? 0 : 1));
            nodes = Math.addExact(nodes, counter.nodes);
        }
        for (int i = 0; i < history.steps().size(); i++) {
            var step = history.steps().get(i);
            if (step.assumptions().size() > CompiledAstReplayCodec.MAXIMUM_ASSUMPTIONS)
                throw new InvalidExpression("too many AST replay assumptions");
            bytes = Math.addExact(bytes, "{\"rule\":\"\",\"kind\":\"\",\"mayIncreaseComplexity\":,\"estimatedCostDelta\":,\"equivalencePreservingByConstruction\":,\"assumptions\":[],\"packId\":\"\",\"license\":\"\"}".length()
                + counter.text(step.rule()) + step.kind().name().length() + (step.mayIncreaseComplexity() ? 4 : 5)
                + integerCharacters(step.estimatedCostDelta()) + (step.equivalencePreservingByConstruction() ? 4 : 5)
                + counter.text(step.packId()) + counter.text(step.license()) + (i == 0 ? 0 : 1));
            for (int j = 0; j < step.assumptions().size(); j++)
                bytes = Math.addExact(bytes, 2 + counter.text(step.assumptions().get(j)) + (j == 0 ? 0 : 1));
        }
        if (bytes > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new InvalidExpression("invalid AST replay byte length");
        return new Inspection(nodes, counter.characters, bytes, bytes - counter.extraUtf8Bytes);
    }
    private static int integerCharacters(int value) {
        long magnitude = Math.abs((long) value);
        int count = value < 0 ? 2 : 1;
        while (magnitude >= 10) { magnitude /= 10; count++; }
        return count;
    }
    private record Visit(Expr expression, int depth, int previous) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(expression); }
    }
    private static final class Counter implements RetainedGraph.View {
        // Append-only within one inspection: scratch growth cannot disappear between observations.
        // Existing occurrence/depth limits also bound this arena (at most nine states per history).
        private static final int OBSERVATION_INTERVAL = 256;
        private Object source;
        private final ArrayList<Visit> visits = new ArrayList<>();
        private Visit current;
        private String rendered;
        private int top = -1, growth, stateStart;
        private boolean released, unobservedGrowth;
        long nodes, characters,extraUtf8Bytes;
        boolean byteGenerator;
        Counter(Object source) { this.source = source; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(visits); visitor.reference(current); visitor.reference(rendered);
        }
        long expression(Expr expression) {
            stateStart = visits.size();
            push(Objects.requireNonNull(expression), 0);
            long bytes = 0;
            while (top >= 0) {
                current = visits.get(top); top = current.previous();
                RetainedOperation.work(2);
                RetainedOperation.validation(1);
                if (++nodes > AstRewriteTransport.MAXIMUM_NODES || current.depth() > AstRewriteTransport.MAXIMUM_DEPTH)
                    throw new InvalidExpression("AST replay structural limit exceeded");
                bytes = Math.addExact(bytes, nodeBytes(current.expression(), current.depth()));
            }
            return bytes;
        }
        private void push(Expr expression, int depth) {
            RetainedOperation.work(1);
            if (visits.size() - stateStart == AstRewriteTransport.MAXIMUM_NODES)
                throw new InvalidExpression("AST replay structural limit exceeded");
            visits.add(new Visit(expression, depth, top)); top = visits.size() - 1;
            unobservedGrowth = true;
            RetainedOperation.work(2); // visit allocation and arena insertion
            if (++growth == OBSERVATION_INTERVAL) { growth = 0; checkpointIfGrown(); }
        }
        private long nodeBytes(Expr expression, int depth) {
            return switch (expression) {
                case NumberExpr number -> {
                    if (number.value().numerator().bitLength() > 4 * CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS
                            || number.value().denominator().bitLength() > 4 * CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS)
                        throw new InvalidExpression("AST replay numeric literal is too large");
                    yield 28 + renderedText(number.value().canonicalText());
                }
                case VariableExpr variable -> variable.symbol().isPresent()
                    ? 25 + renderedText(variable.symbol().orElseThrow().canonicalText()) : 29 + text(variable.name());
                case BinaryExpr binary -> {
                    push(binary.right(), depth + 1); push(binary.left(), depth + 1);
                    yield 48L + binary.operator().name().length();
                }
                case FunctionExpr function -> {
                    if (function.arguments().size() > AstRewriteTransport.MAXIMUM_NODES - nodes)
                        throw new InvalidExpression("AST replay argument count exceeded");
                    long size = 44 + text(function.name());
                    for (int i = function.arguments().size() - 1; i >= 0; i--)
                        push(function.arguments().get(i), depth + 1);
                    yield Math.addExact(size, Math.max(0, function.arguments().size() - 1));
                }
            };
        }
        private long renderedText(String value) {
            rendered = value; // publish before the allocation/validation debit can fail
            unobservedGrowth = true;
            RetainedOperation.work(1L + value.length()); // completed rendering, separate from validation scanning
            long bytes = text(value);
            checkpointIfGrown();
            RetainedOperation.work(1);
            rendered = null; // drop the observed buffer before another scalar can be formatted
            return bytes;
        }
        private void checkpointIfGrown() {
            if (unobservedGrowth) {
                RetainedOperation.checkpoint();
                unobservedGrowth = false;
            }
        }
        private void release() {
            if (released) return;
            long work = Math.addExact(4, 2L * visits.size());
            source = null; current = null; rendered = null; visits.clear(); top = -1; released = true;
            RetainedOperation.work(work);
        }
        private void cleanup(RetainedOperation.Frame retained, Throwable failure) {
            // Repeated budget failures during observation/cleanup must not replace the primary cause.
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) { if (observation != failure) failure.addSuppressed(observation); }
            try { release(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            try { if (retained != null) retained.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        }
        long text(String value) {
            RetainedOperation.validation(value==null?0:value.length());
            if (value == null || value.isBlank() || value.length() > CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS)
                throw new InvalidExpression("invalid or oversized AST replay text");
            characters = Math.addExact(characters, value.length());
            if (characters > CompiledAstReplayCodec.MAXIMUM_BYTES) throw new InvalidExpression("AST replay text limit exceeded");
            long bytes = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new InvalidExpression("unpaired Unicode surrogate");
                    // Jackson byte output escapes each supplementary UTF-16 code unit; String output retains the pair.
                    if (byteGenerator) bytes += 12;
                    else { bytes += 4; extraUtf8Bytes += 2; }
                } else if (Character.isLowSurrogate(c)) throw new InvalidExpression("unpaired Unicode surrogate");
                else if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f') bytes += 2;
                else if (c < 32) bytes += 6;
                else { int size=c < 128 ? 1 : c < 2048 ? 2 : 3;bytes+=size;extraUtf8Bytes+=size-1; }
            }
            return bytes;
        }
    }
}
