package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RecognitionProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in application of a structural hypothesis to selected ordered outputs.
 *
 * <p>Pattern slot i is written back to the physical slot matched by source i.
 * Unchanged outputs remain in place with their original objects. The existing
 * ExprMatcher binds the entire selected tuple at once, so repeated placeholders
 * share one binding. Recognition is exact unless structural arithmetic
 * associativity/commutativity is explicitly requested; no text round-trip or
 * domain-specific rule is used.</p>
 *
 * <p>Applications are untrusted syntax proposals, NOT proved Transformations.
 * Retained sample assumptions and recognition traces do not become proof
 * premises. Every concrete application requires independent verification.</p>
 */
public final class TypedOutputPattern {
    public static final int MAXIMUM_OUTPUTS = 8;
    public static final int MAXIMUM_ASSIGNMENTS = 4096;
    private final TypedPatternGeneralizer.Candidate hypothesis;
    private final PatternExpr.Function sourcePattern;
    private final PatternExpr.Function targetPattern;
    private final RecognitionProfile recognitionProfile;
    private final ExprMatcher matcher;

    public TypedOutputPattern(TypedPatternGeneralizer.Candidate hypothesis) {
        this(hypothesis, RecognitionProfile.exact());
    }

    /**
     * The profile requests recognition, not mathematical authority. Only the
     * existing structural ADD/MUL profiles are supported: no inferred algebraic
     * bindings or external equivalence exploration is performed here.
     */
    public TypedOutputPattern(TypedPatternGeneralizer.Candidate hypothesis, RecognitionProfile recognitionProfile) {
        this.hypothesis = Objects.requireNonNull(hypothesis, "hypothesis");
        this.recognitionProfile = requireSupported(recognitionProfile);
        requireBounded(hypothesis.source(), null);
        requireBounded(hypothesis.target(), null);
        if (!(hypothesis.source() instanceof PatternExpr.Function source)
                || !(hypothesis.target() instanceof PatternExpr.Function target)
                || !source.name().equals(target.name())
                || source.arguments().isEmpty()
                || source.arguments().size() > MAXIMUM_OUTPUTS
                || source.arguments().size() != target.arguments().size()) {
            throw new IllegalArgumentException("same function envelope and corresponding output slots required");
        }
        if (!placeholders(source).containsAll(placeholders(target))) {
            throw new IllegalArgumentException("target-only output-pattern placeholder");
        }
        sourcePattern = source;
        targetPattern = target;
        matcher = ExprMatcher.pattern(sourcePattern, recognitionProfile);
    }

    public TypedPatternGeneralizer.Candidate hypothesis() { return hypothesis; }

    /** Positions, bindings and recognition metadata permit checking but grant no authority. */
    public record Application(FunctionExpr source, FunctionExpr target,
            List<Integer> positions, Map<String, Expr> bindings,
            RecognitionProfile recognitionProfile, List<String> recognitionTrace) {
        /** Retain the original exact structural construction contract. */
        public Application(FunctionExpr source, FunctionExpr target,
                List<Integer> positions, Map<String, Expr> bindings) {
            this(source, target, positions, bindings, RecognitionProfile.exact(), List.of());
        }

        public Application {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            positions = List.copyOf(positions);
            bindings = Map.copyOf(bindings);
            recognitionProfile = requireSupported(recognitionProfile);
            recognitionTrace = List.copyOf(recognitionTrace);
        }
    }

    /** Counters describe tuple attempts and matcher steps, not complete CPU/memory costs. */
    public record Result(List<Application> applications, boolean complete,
            int assignmentAttempts, long matcherSteps) {
        public Result {
            applications = List.copyOf(applications);
            if (assignmentAttempts < 0 || matcherSteps < 0) {
                throw new IllegalArgumentException("negative matching counter");
            }
        }
    }

    public Result find(FunctionExpr program, int maximumAssignments) {
        Objects.requireNonNull(program, "program");
        if (maximumAssignments < 1 || maximumAssignments > MAXIMUM_ASSIGNMENTS) {
            throw new IllegalArgumentException("assignment budget outside supported bounds");
        }
        if (program.arguments().size() > MAXIMUM_OUTPUTS) {
            throw new IllegalArgumentException("too many program outputs");
        }
        requireBounded(program, null);
        var run = new Run();
        if (program.name().equals(sourcePattern.name())
                && program.arguments().size() >= sourcePattern.arguments().size()) {
            enumerate(program, maximumAssignments, new ArrayList<>(),
                new boolean[program.arguments().size()], run);
        }
        return new Result(run.applications, run.complete, run.attempts, run.matcherSteps);
    }

    private static final class Run {
        final List<Application> applications = new ArrayList<>();
        boolean complete = true;
        boolean stopped;
        int attempts;
        long matcherSteps;
    }

    private void enumerate(FunctionExpr program, int maximumAssignments,
            List<Integer> positions, boolean[] used, Run run) {
        if (run.stopped) return;
        if (positions.size() == sourcePattern.arguments().size()) {
            // Only an untried assignment makes an exactly consumed budget incomplete.
            if (run.attempts == maximumAssignments) {
                run.complete = false;
                run.stopped = true;
                return;
            }
            run.attempts++;
            var selected = new FunctionExpr(program.name(), positions.stream()
                .map(index -> program.arguments().get(index)).toList());
            var outcome = matcher.match(selected);
            run.matcherSteps = Math.addExact(run.matcherSteps, outcome.evaluatedSteps());
            run.complete &= outcome.complete();
            for (var match : outcome.matches()) {
                var bindings = match.bindings();
                try {
                    // Bound substitution before allocating a reconstruction or target.
                    requireBounded(sourcePattern, bindings);
                    requireBounded(targetPattern, bindings);
                    if (recognitionProfile.equals(RecognitionProfile.exact())
                            && !sourcePattern.instantiate(bindings).equals(selected)) {
                        throw new IllegalStateException("exact output match does not reconstruct its source");
                    }
                    var replacement = (FunctionExpr) targetPattern.instantiate(bindings);
                    var outputs = new ArrayList<>(program.arguments());
                    for (int i = 0; i < positions.size(); i++) {
                        // Recognition must not reorder or rebuild an unchanged output.
                        if (!sourcePattern.arguments().get(i).equals(targetPattern.arguments().get(i))) {
                            outputs.set(positions.get(i), replacement.arguments().get(i));
                        }
                    }
                    var target = new FunctionExpr(program.name(), outputs);
                    requireBounded(target, null);
                    run.applications.add(new Application(program, target, positions, bindings,
                        recognitionProfile, match.trace()));
                } catch (StructuralLimit exception) {
                    run.complete = false;
                }
            }
            return;
        }
        for (int index = 0; index < used.length && !run.stopped; index++) {
            if (used[index]) continue;
            used[index] = true;
            positions.add(index);
            enumerate(program, maximumAssignments, positions, used, run);
            positions.removeLast();
            used[index] = false;
        }
    }

    private static RecognitionProfile requireSupported(RecognitionProfile profile) {
        Objects.requireNonNull(profile, "recognitionProfile");
        var supported = Set.of(BinaryOperator.ADD, BinaryOperator.MUL);
        if (profile.inferAlgebraicBindings() || !profile.recognitionRuleIds().isEmpty()
                || profile.maxEquivalenceDepth() != 0
                || !supported.containsAll(profile.associativeOperators())
                || !supported.containsAll(profile.commutativeOperators())) {
            throw new IllegalArgumentException("only structural ADD/MUL recognition is supported");
        }
        return profile;
    }

    private static Set<String> placeholders(PatternExpr root) {
        var names = new HashSet<String>();
        var pending = new ArrayDeque<Object>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Object node = pending.pop();
            if (node instanceof PatternExpr.Placeholder placeholder) names.add(placeholder.name());
            for (Object child : children(node)) pending.push(child);
        }
        return Set.copyOf(names);
    }

    private record Pending(Object node, int depth) {}
    private static final class StructuralLimit extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        StructuralLimit() { super("typed output-pattern structural limit exceeded"); }
    }

    /** Count occurrences, not unique Java objects; optional bindings preflight expansion. */
    private static void requireBounded(Object root, Map<String, Expr> bindings) {
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(root, 0));
        int visited = 0;
        while (!pending.isEmpty()) {
            var next = pending.pop();
            Object node = next.node();
            if (bindings != null && node instanceof PatternExpr.Placeholder placeholder) {
                node = Objects.requireNonNull(bindings.get(placeholder.name()), "unbound target placeholder");
            }
            if (++visited > TypedPatternGeneralizer.MAXIMUM_NODES
                    || next.depth() > TypedPatternGeneralizer.MAXIMUM_DEPTH) throw new StructuralLimit();
            var children = children(node);
            if (children.size() > TypedPatternGeneralizer.MAXIMUM_NODES - visited) throw new StructuralLimit();
            for (Object child : children) pending.push(new Pending(child, next.depth() + 1));
        }
    }

    private static List<?> children(Object node) {
        if (node instanceof BinaryExpr binary) return List.of(binary.left(), binary.right());
        if (node instanceof FunctionExpr function) return function.arguments();
        if (node instanceof PatternExpr.Operation binary) return List.of(binary.left(), binary.right());
        if (node instanceof PatternExpr.Function function) return function.arguments();
        return List.of();
    }
}
