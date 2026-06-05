package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Uses the target expression (when present) to propose parameters that explain
 * the structural difference between input and target.
 *
 * <p>It reduces both input and target to a signed multiset of additive summands
 * (equations are folded to {@code left - right}) and proposes {@code +T} for
 * summands the target gains and {@code -T} for summands it drops.</p>
 *
 * <p>Example: input {@code x}, target {@code x + 1} → {@code +1}.</p>
 */
public final class TargetDifferenceHypothesisGenerator implements ParameterHypothesisGenerator {

    @Override
    public String id() {
        return "target-difference";
    }

    @Override
    public List<ParameterHypothesis> propose(ParameterContext context) {
        if (!context.allows(RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES)) {
            return List.of();
        }
        if (context.targetExpression().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> input = signedMultiset(context.inputAst().orElse(null), context.inputEquation().orElse(null));
        Map<String, Integer> target = signedMultiset(context.targetAst().orElse(null), context.targetEquation().orElse(null));
        if (input == null || target == null) {
            return List.of();
        }

        TreeMap<String, Integer> delta = new TreeMap<>(target);
        input.forEach((key, count) -> delta.merge(key, -count, Integer::sum));

        List<ParameterHypothesis> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : delta.entrySet()) {
            int net = entry.getValue();
            if (net == 0) {
                continue;
            }
            String canonical = entry.getKey();
            String body = canonical.indexOf(' ') >= 0 ? "(" + canonical + ")" : canonical;
            String value = (net > 0 ? "+" : "-") + body;
            result.add(new ParameterHypothesis(
                    RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES,
                    "target-delta",
                    value,
                    value,
                    HypothesisSource.TARGET_DIFF,
                    0.95,
                    net > 0 ? "target gains summand " + canonical : "target drops summand " + canonical,
                    List.of("net=" + net)));
        }
        result.sort(ParameterHypothesis.CANONICAL_ORDER);
        return List.copyOf(result);
    }

    /** @return a canonical -> net-sign map, or {@code null} when nothing parsed. */
    private Map<String, Integer> signedMultiset(Expr ast, Equation equation) {
        List<HypothesisExpressions.SignedTerm> terms;
        if (equation != null) {
            terms = new ArrayList<>(HypothesisExpressions.additiveTerms(equation.left()));
            // Fold "left = right" into "left - right" so both sides contribute.
            for (HypothesisExpressions.SignedTerm right : HypothesisExpressions.additiveTerms(equation.right())) {
                terms.add(new HypothesisExpressions.SignedTerm(!right.positive(), right.expr()));
            }
        } else if (ast != null) {
            terms = HypothesisExpressions.additiveTerms(ast);
        } else {
            return null;
        }
        TreeMap<String, Integer> multiset = new TreeMap<>();
        for (HypothesisExpressions.SignedTerm term : terms) {
            if (HypothesisExpressions.isZero(term.expr())) {
                continue;
            }
            String canonical = HypothesisExpressions.format(term.expr());
            multiset.merge(canonical, term.positive() ? 1 : -1, Integer::sum);
        }
        return multiset;
    }
}
