package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.AstExpressionValidation;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.ArrayList;
import java.util.Objects;

/** Occurrence count: visits are delegated once; native scratch ownership is charged separately. */
public enum NativeNodeCountObjective implements TypedSourceOnlySearch.Objective, RetainedGraph.View {
    INSTANCE;
    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}

    @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) {
        var counter = new Counter(Objects.requireNonNull(state));
        try {
            var frame = RetainedOperation.retainCompleted(2, counter);
            Throwable primary = null;
            try { return counter.evaluate(); }
            catch (RuntimeException | Error failure) {
                primary = failure;
                SearchExecution.observeFailure(failure);
                throw failure;
            } finally {
                try { counter.release(); }
                catch (RuntimeException | Error cleanup) {
                    if (primary == null) { primary = cleanup; throw cleanup; }
                    if (cleanup != primary) primary.addSuppressed(cleanup);
                } finally { SearchExecution.close(frame, primary); }
            }
        } catch (SearchExecution.ResourceLimit exhausted) {
            throw exhausted.paidSearch(counter.visited);
        }
    }

    private static final class Counter implements RetainedGraph.View {
        private final TypedMoveSearch.State state;
        private final ArrayList<Expr> arena = new ArrayList<>();
        private TypedSourceOnlySearch.Score score;
        private int visited;
        Counter(TypedMoveSearch.State state) { this.state = state; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(state); visitor.reference(arena); visitor.reference(score);
        }
        TypedSourceOnlySearch.Score evaluate() {
            push(state.expression());
            while (visited < arena.size()) {
                var expression = arena.get(visited++);
                switch (expression) {
                    case BinaryExpr binary -> { push(binary.left()); push(binary.right()); }
                    case FunctionExpr function -> { for (var arg : function.arguments()) push(arg); }
                    case VariableExpr ignored -> {}
                    case NumberExpr ignored -> {}
                }
            }
            score = new TypedSourceOnlySearch.Score(visited, visited);
            SearchExecution.completed(1);
            return score;
        }
        private void push(Expr expression) {
            if (arena.size() == AstRewriteTransport.MAXIMUM_NODES)
                throw new AstExpressionValidation.InvalidExpression("objective occurrence limit exceeded");
            arena.add(expression);
            RetainedOperation.work(1);
            if (arena.size() % 256 == 0) RetainedOperation.checkpoint();
        }
        void release() {
            long work = arena.size() + 1L;
            arena.clear();
            RetainedOperation.work(work);
        }
    }
}
