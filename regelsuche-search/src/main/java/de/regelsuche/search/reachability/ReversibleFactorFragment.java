package de.regelsuche.search.reachability;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A versioned subset of native production rules, restricted to concrete reciprocal edges. */
final class ReversibleFactorFragment {
    static final String ID = "regelsuche.reversible-factor-add/v1";
    private static final Map<String, String> INVERSE = Map.of(
        "ast_factor_common_left", "ast_distribute_left_add", "ast_distribute_left_add", "ast_factor_common_left",
        "ast_factor_common_right", "ast_distribute_right_add", "ast_distribute_right_add", "ast_factor_common_right");
    private final ExpressionParser parser = new ExpressionParser();
    private final AstRewriteTransformationEngine engine;

    ReversibleFactorFragment() {
        var rules = AstRewriteTransformationEngine.defaultRules().stream().filter(r -> INVERSE.containsKey(r.id())).toList();
        if (rules.size() != INVERSE.size()) throw new IllegalStateException("reversible primitive inventory changed");
        engine = new AstRewriteTransformationEngine(rules, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    String endpoint(String input) {
        var term = parser.parseTerm(input);
        if (nodes(term) > 63) throw new IllegalArgumentException("endpoint outside bounded fragment");
        return ExpressionFormatter.format(term);
    }

    record Arc(Transformation outgoing, Transformation returning) {}

    List<Arc> neighbors(String source, Counter counter) {
        List<Arc> arcs = new ArrayList<>();
        for (Transformation candidate : raw(source, counter)) {
            String destination = candidate.transformedExpression();
            if (nodes(parser.parseTerm(destination)) > 63) continue;
            Optional<Transformation> inverse = raw(destination, counter).stream()
                .filter(t -> t.rule().equals(INVERSE.get(candidate.rule())) && t.transformedExpression().equals(source))
                .findFirst();
            inverse.ifPresent(t -> arcs.add(new Arc(candidate, t)));
        }
        return arcs;
    }

    private List<Transformation> raw(String source, Counter counter) {
        counter.charge();
        List<Transformation> candidates = engine.transform(source);
        counter.generated = Math.addExact(counter.generated, candidates.size());
        for (Transformation t : candidates) {
            if (!INVERSE.containsKey(t.rule()) || !t.equivalencePreservingByConstruction() || !t.assumptions().isEmpty()
                    || !t.primitiveRuleIds().equals(List.of(t.rule())) || t.exactTheoryStepCount() != 0) {
                throw new IllegalStateException("unexpected native primitive or assumption: " + t.rule());
            }
        }
        return candidates;
    }

    private static int nodes(Expr term) {
        return switch (term) {
            case BinaryExpr b -> 1 + nodes(b.left()) + nodes(b.right());
            case FunctionExpr f -> 1 + f.arguments().stream().mapToInt(ReversibleFactorFragment::nodes).sum();
            default -> 1;
        };
    }

    static final class Allowance {
        private int remaining;
        Allowance(int limit) {
            if (limit < 0) throw new IllegalArgumentException("negative work limit");
            remaining = limit;
        }
    }

    static final class Counter {
        private final Allowance allowance;
        private int calls;
        private long generated;
        Counter(Allowance allowance) { this.allowance = allowance; }
        void charge() {
            if (allowance.remaining == 0) throw new BoundedReachabilityOracle.EngineWorkExhausted("PRIMITIVE_ENGINE_CALL_LIMIT_REACHED");
            allowance.remaining--;
            calls++;
        }
        ReversibleFactorSearch.Count snapshot() { return new ReversibleFactorSearch.Count(calls, generated); }
    }
}
