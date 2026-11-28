package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded projection of solver-issued, independently replayed task witnesses onto unchanged AST contexts. */
final class TaskSubpathMiner {
    private TaskSubpathMiner() { }

    @FunctionalInterface
    interface Sink {
        void accept(String location, Expr source, Expr target);
    }

    private record Step(Expr before, Expr after, int index, boolean primitive) {
        Step project(Expr source, Expr target) { return new Step(source, target, index, primitive); }
    }
    private record Trace(String path, List<Step> steps) { }

    /** Returns false when the bounded inventory of possible training observations was truncated. */
    static boolean mine(List<TypedMoveSearch.WitnessStep> witness, CheckedLearnedSchemaModel.Bounds bounds,
            int allowance, CheckedSchemaSupport.Work work, Sink sink) {
        work.add(1);
        if (witness.size() > bounds.maximumExamples()) return false;
        if (witness.size() < 3) return true;
        var steps = new ArrayList<Step>();
        CheckedSchemaSupport.domain(witness.getFirst().source().expression(), bounds, work);
        for (var edge : witness) {
            Expr target = edge.target().expression();
            CheckedSchemaSupport.domain(target, bounds, work);
            var move = edge.move();
            steps.add(new Step(edge.source().expression(), target, steps.size(),
                move.sourceKind() == SearchMove.SourceKind.PRIMITIVE && move.assumptions().isEmpty()
                && edge.verification().accepted()));
            work.add(1);
        }
        var root = new Trace("", steps);
        var scopes = new ArrayList<Trace>();
        collectScopes(root, scopes, work);
        return inspect(root, scopes, allowance, work, sink);
    }

    /**
     * Follow only transitions with the same enclosing operator and exactly one changed
     * child. A step wholly in the sibling becomes a stutter; a change at the common
     * ancestor forbids that projection. Keeping original indices binds interleaved work.
     */
    private static void collectScopes(Trace trace, List<Trace> scopes, CheckedSchemaSupport.Work work) {
        var left = new ArrayList<Step>();
        var right = new ArrayList<Step>();
        for (var step : trace.steps()) {
            work.add(1);
            if (!(step.before() instanceof BinaryExpr before) || !(step.after() instanceof BinaryExpr after)
                    || before.operator() != after.operator()) return;
            boolean sameLeft = before.left().equals(after.left());
            boolean sameRight = before.right().equals(after.right());
            if (sameLeft == sameRight) return;
            if (sameLeft) right.add(step.project(before.right(), after.right()));
            else left.add(step.project(before.left(), after.left()));
        }
        collectChild(trace.path() + "L", left, scopes, work);
        collectChild(trace.path() + "R", right, scopes, work);
    }

    private static void collectChild(String path, List<Step> steps, List<Trace> scopes, CheckedSchemaSupport.Work work) {
        if (steps.size() < 2) return;
        var trace = new Trace(path, steps);
        scopes.add(trace);
        collectScopes(trace, scopes, work);
    }

    private static boolean inspect(Trace root, List<Trace> scopes, int allowance,
            CheckedSchemaSupport.Work work, Sink sink) {
        // Whole projected subproblems first. Preserve remaining capacity for proper windows.
        for (var trace : scopes) {
            if (allowance-- == 0) return false;
            observe(trace, 0, trace.steps().size(), work, sink);
        }
        scopes.add(root);
        for (int width = 2; width < root.steps().size(); width++) {
            for (var trace : scopes) {
                work.add(1);
                if (width >= trace.steps().size()) continue;
                for (int start = 0; start <= trace.steps().size() - width; start++) {
                    if (allowance-- == 0) return false;
                    observe(trace, start, start + width, work, sink);
                }
            }
        }
        return true;
    }

    private static void observe(Trace trace, int start, int end, CheckedSchemaSupport.Work work, Sink sink) {
        var steps = trace.steps();
        Expr source = steps.get(start).before();
        var visited = new HashSet<Expr>();
        visited.add(source);
        var location = new StringBuilder(trace.path()).append('[');
        for (var step : steps.subList(start, end)) {
            work.add(1);
            if (!step.primitive() || !visited.add(step.after())) return;
            location.append(step.index()).append(',');
        }
        String origin = location.append(']').toString();
        if (origin.length() > 400) {
            // The persisted support ID also contains its lesson and lineage. Preserve all
            // original indices in at most 128 bits under the snapshot's 512-character limit.
            var mask = java.math.BigInteger.ZERO;
            for (var step : steps.subList(start, end)) mask = mask.setBit(step.index());
            work.add(end - start);
            origin = trace.path() + "[indices=0x" + mask.toString(16) + "]";
        }
        sink.accept(origin, source, steps.get(end - 1).after());
    }
}
