package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds additive terms {@code +T}/{@code -T} that neutralise a summand of the
 * expression. The cancelling term is the additive inverse of the summand and is
 * deliberately structural: {@code T} may be a number, a variable or an arbitrary
 * complex subtree.
 *
 * <ul>
 *   <li>{@code x - 1 = 0} → {@code +1}</li>
 *   <li>{@code x - T = 0} → {@code +T}</li>
 *   <li>{@code x + a = b} → {@code -a}</li>
 * </ul>
 */
public final class CancellationHypothesisGenerator implements ParameterHypothesisGenerator {

    @Override
    public String id() {
        return "cancellation";
    }

    @Override
    public List<ParameterHypothesis> propose(ParameterContext context) {
        if (!context.allows(RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES)) {
            return List.of();
        }
        List<HypothesisExpressions.SignedTerm> terms = new ArrayList<>();
        Equation equation = context.inputEquation().orElse(null);
        if (equation != null) {
            terms.addAll(HypothesisExpressions.additiveTerms(equation.left()));
            terms.addAll(HypothesisExpressions.additiveTerms(equation.right()));
        } else if (context.inputAst().isPresent()) {
            terms.addAll(HypothesisExpressions.additiveTerms(context.inputAst().get()));
        } else {
            return List.of();
        }

        Map<String, ParameterHypothesis> distinct = new LinkedHashMap<>();
        for (HypothesisExpressions.SignedTerm term : terms) {
            if (HypothesisExpressions.isZero(term.expr())) {
                continue;
            }
            ParameterHypothesis hypothesis = cancellationFor(term);
            if (hypothesis != null) {
                distinct.putIfAbsent(hypothesis.canonicalValue(), hypothesis);
            }
        }
        List<ParameterHypothesis> result = new ArrayList<>(distinct.values());
        result.sort(ParameterHypothesis.CANONICAL_ORDER);
        return List.copyOf(result);
    }

    private ParameterHypothesis cancellationFor(HypothesisExpressions.SignedTerm term) {
        Expr expr = term.expr();
        String value;
        if (expr instanceof NumberExpr number) {
            double signed = term.positive() ? number.value() : -number.value();
            double cancellation = -signed;
            value = (cancellation > 0 ? "+" : "") + HypothesisExpressions.formatNumber(cancellation);
        } else {
            // To cancel a positive summand we subtract it; to cancel a negative
            // summand we add it.
            String canonical = HypothesisExpressions.format(expr);
            String body = HypothesisExpressions.isComposite(expr) ? "(" + canonical + ")" : canonical;
            value = (term.positive() ? "-" : "+") + body;
        }
        return new ParameterHypothesis(
                RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES,
                "cancel",
                value,
                value,
                HypothesisSource.CANCELLATION,
                0.85,
                "neutralises summand " + HypothesisExpressions.format(expr),
                List.of("summand=" + HypothesisExpressions.format(expr)));
    }
}
