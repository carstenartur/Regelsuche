package de.regelsuche.discovery.representation;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleStatus;
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
        Expr target = parser.parseTerm(candidate);
        if (!matchesEndpoints(expected, depth, parser.parseTerm(source), target)) return false;
        Set<String> permitted = premises(assumptions);
        Set<String> initial = premises(initialAssumptions);
        if (!permitted.containsAll(initial)) return false;
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

    private Set<Node> expand(Set<Node> frontier, String rule, Expr required, Set<String> permitted, Work remaining) {
        var next = new LinkedHashSet<Node>();
        for (var node : frontier) {
            var moves = engines.get(rule).transform(node.expression());
            if (!remaining.admit(moves.size() + 1)) return Set.of();
            for (var move : moves) {
                if (!move.primitiveRuleIds().equals(List.of(rule)) || !move.equivalencePreservingByConstruction()) continue;
                if (required != null && !parser.parseTerm(move.transformedExpression()).equals(required)) continue;
                var obligations = new LinkedHashSet<>(node.premises());
                obligations.addAll(premises(move.assumptions()));
                if (permitted.containsAll(obligations)) next.add(new Node(move.transformedExpression(), Set.copyOf(obligations)));
            }
        }
        return next;
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
