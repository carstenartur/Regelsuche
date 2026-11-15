package de.regelsuche.discovery.representation;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.assumption.ExpressionDefinedness;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.knowledge.RuleStatus;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Replays trusted formation primitives, including occurrence choice and cumulative premises. */
final class FrozenPrimitiveLineage {
    private static final int MAX_REPLAY_TRANSITIONS = 4096;
    private final ExpressionParser parser = new ExpressionParser();
    private final Map<String, AstRewriteTransformationEngine> engines;

    FrozenPrimitiveLineage(List<RewriteRule> formationRules) {
        engines = formationRules.stream()
            .filter(rule -> rule.descriptor().status() == RuleStatus.VALIDATED)
            .filter(RewriteRule::isEquivalencePreservingByConstruction)
            .collect(Collectors.toUnmodifiableMap(RewriteRule::id,
                rule -> new AstRewriteTransformationEngine(List.of(rule))));
    }

    boolean verifies(String source, String candidate, int depth, List<String> pathRuleIds,
            List<String> primitiveRuleIds, List<String> initialAssumptions, List<String> assumptions, List<String> pathExpressions) {
        if (!eligible(depth, pathRuleIds, primitiveRuleIds)) return false;
        var expected = pathExpressions.stream().map(parser::parseTerm).toList();
        Expr start = parser.parseTerm(source);
        Expr target = parser.parseTerm(candidate);
        if (!matchesEndpoints(expected, depth, start, target)) return false;
        Set<String> permitted = premises(assumptions);
        Set<String> initial = premises(initialAssumptions);
        if (!permitted.containsAll(initial) || !definedUnder(start, permitted)) return false;
        var remaining = new Work();
        Set<Node> frontier = Set.of(new Node(source, initial));
        for (int i = 0; i < depth; i++) {
            Expr required = expected.isEmpty() ? null : expected.get(i + 1);
            frontier = expand(frontier, pathRuleIds.get(i), required, permitted, remaining);
            if (frontier.isEmpty()) return false;
        }
        return frontier.stream().anyMatch(node -> node.premises().equals(permitted)
            && parser.parseTerm(node.expression()).equals(target));
    }

    private boolean eligible(int depth, List<String> pathRules, List<String> primitives) {
        return depth > 0 && depth == pathRules.size() && pathRules.equals(primitives)
            && engines.keySet().containsAll(pathRules);
    }

    private static boolean matchesEndpoints(List<Expr> path, int depth, Expr start, Expr end) {
        return path.isEmpty() || path.size() == depth + 1 && path.getFirst().equals(start) && path.getLast().equals(end);
    }

    private static Set<String> premises(List<String> values) {
        return Set.copyOf(AssumptionSignature.ofExpressions(values).normalizedAssumptions());
    }

    private boolean definedUnder(Expr expression, Set<String> permitted) {
        var requirements = new AssumptionContext();
        return ExpressionDefinedness.canElideWithoutDomainLoss(expression, requirements)
            && requirements.snapshot().stream().allMatch(required -> declared(required, permitted)
                || required.kind() == Assumption.Kind.NON_ZERO
                    && provesNonZero(parser.parseTerm(required.symbols().getFirst()), permitted));
    }

    private static boolean declared(Assumption assumption, Set<String> permitted) {
        return permitted.contains(AssumptionSignature.normalizeExpression(assumption.expression()));
    }

    private static boolean provesNonZero(Expr expression, Set<String> permitted) {
        if (expression instanceof NumberExpr number) return number.value().signum() != 0;
        if (declared(Assumption.nonZero(ExpressionFormatter.format(expression)), permitted)) return true;
        return expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL
            && provesNonZero(binary.left(), permitted) && provesNonZero(binary.right(), permitted);
    }

    private Set<Node> expand(Set<Node> frontier, String rule, Expr required, Set<String> permitted, Work remaining) {
        var next = new LinkedHashSet<Node>();
        for (var node : frontier) {
            var engine = engines.get(rule);
            var records = new ArrayList<AstRewriteTransformationEngine.GeneratedTransformation>();
            engine.transformWithOccurrences(node.expression(), records::add);
            if (!remaining.admit(records.size() + 1)) return Set.of();
            for (var generated : records) {
                var move = generated.transformation();
                if (!move.primitiveRuleIds().equals(List.of(rule)) || !move.equivalencePreservingByConstruction()) continue;
                Expr after = parser.parseTerm(move.transformedExpression());
                if (required != null && !after.equals(required)) continue;
                if (!rebuilds(generated, engine.rules().getFirst(), after) || !definedUnder(after, permitted)) continue;
                var obligations = new LinkedHashSet<>(node.premises());
                obligations.addAll(premises(move.assumptions()));
                if (permitted.containsAll(obligations)) next.add(new Node(move.transformedExpression(), Set.copyOf(obligations)));
            }
        }
        return next;
    }

    private boolean rebuilds(AstRewriteTransformationEngine.GeneratedTransformation generated, RewriteRule rule, Expr after) {
        Expr before = parser.parseTerm(generated.sourceExpression());
        var position = generated.position();
        var selected = position.selectAt(before).selectedSubtree();
        if (selected.isEmpty() || !rule.matches(selected.orElseThrow())) return false;
        var replaced = position.replaceAt(before, rule.apply(selected.orElseThrow())).rewrittenRoot();
        return replaced.isPresent() && sameGrouping(replaced.orElseThrow(), after);
    }

    // The search formatter flattens nested sums and products. This comparison permits only their
    // associative grouping and preserves operand order, multiplicity and all other operators.
    boolean sameGrouping(Expr left, Expr right) {
        if (left.equals(right)) return true;
        if (!(left instanceof BinaryExpr a) || !(right instanceof BinaryExpr b) || a.operator() != b.operator()) return false;
        if (a.operator() != BinaryOperator.ADD && a.operator() != BinaryOperator.MUL) {
            return sameGrouping(a.left(), b.left()) && sameGrouping(a.right(), b.right());
        }
        var lhs = new ArrayList<Expr>();
        var rhs = new ArrayList<Expr>();
        flatten(left, a.operator(), lhs);
        flatten(right, a.operator(), rhs);
        if (lhs.size() != rhs.size()) return false;
        for (int i = 0; i < lhs.size(); i++) if (!sameGrouping(lhs.get(i), rhs.get(i))) return false;
        return true;
    }

    private static void flatten(Expr term, BinaryOperator operator, List<Expr> terms) {
        if (term instanceof BinaryExpr binary && binary.operator() == operator) {
            flatten(binary.left(), operator, terms);
            flatten(binary.right(), operator, terms);
        } else {
            terms.add(term);
        }
    }

    boolean bindsSource(String source, List<String> path) {
        return !path.isEmpty() && sameGrouping(parser.parseTerm(source), parser.parseTerm(path.getFirst()));
    }

    private static final class Work {
        private int used;

        boolean admit(int transitions) {
            used += transitions;
            return used <= MAX_REPLAY_TRANSITIONS;
        }
    }

    static List<String> statePath(TargetFreeRepresentationSearch.State state,
            Map<String, TargetFreeRepresentationSearch.State> byHash) {
        var reverse = new ArrayList<String>();
        var current = state;
        for (int depth = state.depth(); depth >= 0; depth--) {
            if (current == null || current.depth() != depth) return List.of();
            reverse.add(current.expression());
            current = byHash.get(current.parentStateHash());
        }
        return List.copyOf(reverse.reversed());
    }

    private record Node(String expression, Set<String> premises) {}
}
