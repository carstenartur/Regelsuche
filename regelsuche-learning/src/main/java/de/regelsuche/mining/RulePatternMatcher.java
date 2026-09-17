package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Structural applicability matching; a binding is not a mathematical proof. */
public class RulePatternMatcher {
    public static final String SEQUENCE_REVISION = "regelsuche.rule-pattern-sequence/v1";
    public static final String LAZY_SEQUENCE_REVISION = "regelsuche.rule-pattern-sequence/lazy-v1";
    public static final int MAXIMUM_SEQUENCE_STEPS = 64;

    public enum MatchStatus { MATCH, NO_MATCH, BUDGET_EXHAUSTED }

    /** One constraint in a sequence sharing the same placeholder substitution. */
    public record MatchStep(RulePatternNode pattern, Expr expression) {
        public MatchStep {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /** Failed or incomplete searches never expose a partial substitution. */
    public record MatchResult(MatchStatus status, Map<String, Expr> bindings, long workUnits) {
        public MatchResult {
            Objects.requireNonNull(status, "status");
            bindings = Map.copyOf(bindings);
            if (workUnits < 0 || (status != MatchStatus.MATCH && !bindings.isEmpty())) {
                throw new IllegalArgumentException("invalid matching result");
            }
        }
    }

    private final RulePatternParser patternParser = new RulePatternParser();
    private final ExpressionParser expressionParser = new ExpressionParser();

    public Optional<Map<String, Expr>> match(String patternString, String expression) {
        try {
            return matchExpression(patternParser.parse(patternString), expressionParser.parseTerm(expression));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public Optional<Map<String, Expr>> match(RulePatternNode pattern, String expression) {
        try {
            return matchExpression(pattern, expressionParser.parseTerm(expression));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Matches an already identity-bound AST without converting it through display text. */
    public Optional<Map<String, Expr>> matchExpression(String patternString, Expr expression) {
        try {
            return matchExpression(patternParser.parse(patternString), expression);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public Optional<Map<String, Expr>> matchExpression(RulePatternNode pattern, Expr expression) {
        return matchExpression(pattern, expression, Map.of());
    }

    /** Initial bindings are constraints, not mutable output parameters. */
    public Optional<Map<String, Expr>> matchExpression(RulePatternNode pattern, Expr expression,
            Map<String, Expr> initialBindings) {
        var result = matchSequence(List.of(new MatchStep(pattern, expression)), initialBindings, Long.MAX_VALUE);
        return result.status() == MatchStatus.MATCH ? Optional.of(result.bindings()) : Optional.empty();
    }

    /**
     * Searches a bounded sequence with one shared substitution. Later constraints
     * may reject earlier commutative choices. Work counts constraint construction,
     * attempted node matches and associative traversal/inspection, including unused
     * alternatives. Parsing, map-copy allocation, equality internals and numerical
     * bit complexity are outside this logical work counter.
     *
     * <p>NO_MATCH means this existing structural matching relation was exhausted;
     * it is not an algebraic inequivalence claim. BUDGET_EXHAUSTED is inconclusive.
     */
    public MatchResult matchSequence(List<MatchStep> steps, Map<String, Expr> initialBindings,
            long maximumWorkUnits) {
        return matchSequence(steps, initialBindings, maximumWorkUnits, false);
    }

    /**
     * Opt-in execution of the same relation and depth-first choice order. Swapped
     * and repeated-associative alternatives retain their bindings and continuation,
     * but construct constraints only when visited. The same logical operations are
     * charged as in {@link #matchSequence}; unused constraints are not constructed.
     * Alternative descriptors, like the historical frame/map allocation, are not
     * part of the node-work ledger. This is not a walltime or allocation estimate.
     */
    public MatchResult matchSequenceLazy(List<MatchStep> steps, Map<String, Expr> initialBindings,
            long maximumWorkUnits) {
        return matchSequence(steps, initialBindings, maximumWorkUnits, true);
    }

    private MatchResult matchSequence(List<MatchStep> steps, Map<String, Expr> initialBindings,
            long maximumWorkUnits, boolean lazyAlternatives) {
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty() || steps.size() > MAXIMUM_SEQUENCE_STEPS || maximumWorkUnits < 1) {
            throw new IllegalArgumentException("require 1 through 64 steps and positive matching work");
        }
        return new RulePatternBindingSearch(maximumWorkUnits, lazyAlternatives)
            .match(List.copyOf(steps), Map.copyOf(initialBindings));
    }
}
