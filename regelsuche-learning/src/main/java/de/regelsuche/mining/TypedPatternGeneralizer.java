package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Opt-in structural hypothesis formation over actual producer ASTs.
 *
 * <p>One table of corresponding subtree vectors spans both sides of every
 * example. Equal vectors receive the same placeholder; variables are never
 * independently renamed on the two sides. Numbers remain exact, binary
 * grouping remains structural, and scoped symbols are compared as Expr values.
 * No formatting, parsing or arithmetic normalization occurs here.</p>
 *
 * <p>A result is only a syntax hypothesis, NOT a proved rewrite rule. Even
 * perfectly reconstructing every example does not prove a generalized identity.
 * Assumptions are retained as sample data, not inferred for arbitrary bindings.
 * A caller must independently verify each concrete application and its premises
 * before passing it to a trusted rule inventory or search frontier.</p>
 */
public final class TypedPatternGeneralizer {
    public static final int MAXIMUM_EXAMPLES = 32;
    public static final int MAXIMUM_NODES = 1024;
    public static final int MAXIMUM_DEPTH = 64;

    /** An observation, not a claim that its source and target are equivalent. */
    public record Example(Expr source, Expr target, List<String> assumptions) {
        public Example {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        }
        public Example(Expr source, Expr target) { this(source, target, List.of()); }
    }

    /** Immutable hypothesis plus the substitutions needed to recover the observations. */
    public record Candidate(PatternExpr source, PatternExpr target,
            List<Example> examples, List<Map<String, Expr>> bindings) {
        public Candidate {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            examples = List.copyOf(Objects.requireNonNull(examples, "examples"));
            bindings = Objects.requireNonNull(bindings, "bindings").stream().map(Map::copyOf).toList();
            if (examples.size() != bindings.size()) {
                throw new IllegalArgumentException("one binding map is required per example");
            }
        }
    }

    /**
     * Forms a directed hypothesis from two through thirty-two observations.
     * Empty means no nontrivial, source-bound abstraction could be formed.
     * Structural limit violations are explicit errors, not negative discoveries.
     */
    public Optional<Candidate> generalize(List<Example> examples) {
        Objects.requireNonNull(examples, "examples");
        if (examples.size() < 2 || examples.size() > MAXIMUM_EXAMPLES) {
            throw new IllegalArgumentException("require 2 through " + MAXIMUM_EXAMPLES + " examples");
        }
        var retained = List.copyOf(examples);
        for (var example : retained) {
            requireBounded(example.source());
            requireBounded(example.target());
        }
        var state = new Bindings();
        PatternExpr source = column(retained.stream().map(Example::source).toList(), state, true);
        PatternExpr target;
        try {
            target = column(retained.stream().map(Example::target).toList(), state, false);
        } catch (UnboundTarget exception) {
            return Optional.empty();
        }
        if (state.columns.isEmpty() || source.equals(target)) {
            return Optional.empty();
        }
        var substitutions = new ArrayList<Map<String, Expr>>(retained.size());
        for (int i = 0; i < retained.size(); i++) {
            var substitution = new LinkedHashMap<String, Expr>();
            for (var entry : state.columns.entrySet()) {
                substitution.put(entry.getValue(), entry.getKey().get(i));
            }
            // A candidate must losslessly reconstruct both sides using the SAME map.
            var example = retained.get(i);
            if (!source.instantiate(substitution).equals(example.source())
                    || !target.instantiate(substitution).equals(example.target())) {
                throw new IllegalStateException("typed hypothesis does not reconstruct its observation");
            }
            substitutions.add(substitution);
        }
        return Optional.of(new Candidate(source, target, retained, substitutions));
    }

    private static PatternExpr column(List<Expr> nodes, Bindings state, boolean source) {
        Expr first = nodes.getFirst();
        // Always abstract variable leaves, including an unchanged scoped variable.
        // PatternExpr.LiteralVariable would replace its exact SymbolId with text.
        if (nodes.stream().allMatch(VariableExpr.class::isInstance)) {
            return state.placeholder(nodes, source);
        }
        if (first instanceof NumberExpr number && nodes.stream().allMatch(first::equals)) {
            return PatternExpr.num(number.value());
        }
        if (first instanceof BinaryExpr binary && nodes.stream().allMatch(node ->
                node instanceof BinaryExpr other && other.operator() == binary.operator())) {
            var left = nodes.stream().map(node -> ((BinaryExpr) node).left()).toList();
            var right = nodes.stream().map(node -> ((BinaryExpr) node).right()).toList();
            return PatternExpr.op(binary.operator(), column(left, state, source), column(right, state, source));
        }
        if (first instanceof FunctionExpr function && nodes.stream().allMatch(node ->
                node instanceof FunctionExpr other && other.name().equals(function.name())
                    && other.arguments().size() == function.arguments().size())) {
            var arguments = new ArrayList<PatternExpr>(function.arguments().size());
            for (int i = 0; i < function.arguments().size(); i++) {
                final int position = i;
                var children = nodes.stream().map(node -> ((FunctionExpr) node).arguments().get(position)).toList();
                arguments.add(column(children, state, source));
            }
            return new PatternExpr.Function(function.name(), arguments);
        }
        // Different shapes or different exact literals can be one expression hole.
        return state.placeholder(nodes, source);
    }

    private static final class Bindings {
        private final Map<List<Expr>, String> columns = new LinkedHashMap<>();

        PatternExpr placeholder(List<Expr> nodes, boolean source) {
            String name = columns.get(nodes);
            if (name == null) {
                if (!source) throw new UnboundTarget();
                name = "P" + columns.size();
                columns.put(List.copyOf(nodes), name);
            }
            return PatternExpr.var(name);
        }
    }

    private static final class UnboundTarget extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record Pending(Expr expression, int depth) {}

    /** Count occurrences, not object identities, before any recursive equality/matching. */
    private static void requireBounded(Expr root) {
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(root, 0));
        int visited = 0;
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (++visited > MAXIMUM_NODES || next.depth() > MAXIMUM_DEPTH) {
                throw new IllegalArgumentException("typed generalization structural limit exceeded");
            }
            if (next.expression() instanceof BinaryExpr binary) {
                pending.push(new Pending(binary.right(), next.depth() + 1));
                pending.push(new Pending(binary.left(), next.depth() + 1));
            } else if (next.expression() instanceof FunctionExpr function) {
                if (function.arguments().size() > MAXIMUM_NODES - visited) {
                    throw new IllegalArgumentException("typed generalization argument limit exceeded");
                }
                for (var argument : function.arguments()) {
                    pending.push(new Pending(argument, next.depth() + 1));
                }
            }
        }
    }
}
