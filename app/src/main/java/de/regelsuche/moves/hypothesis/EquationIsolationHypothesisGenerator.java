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
 * Recognises equations and proposes the inverse operations that isolate a
 * variable.
 *
 * <ul>
 *   <li>{@code x - T = 0} → {@code +T} (additive inverse, both sides)</li>
 *   <li>{@code A*x = B} → {@code /A} (multiplicative inverse, when allowed)</li>
 * </ul>
 */
public final class EquationIsolationHypothesisGenerator implements ParameterHypothesisGenerator {

    @Override
    public String id() {
        return "equation-isolation";
    }

    @Override
    public List<ParameterHypothesis> propose(ParameterContext context) {
        Equation equation = context.inputEquation().orElse(null);
        if (equation == null) {
            return List.of();
        }
        Map<String, ParameterHypothesis> distinct = new LinkedHashMap<>();
        if (context.allows(RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES)) {
            additiveInverses(equation, distinct);
        }
        if (context.allows(RewriteMoveKind.MULTIPLY_SAME_TERM_BOTH_SIDES)) {
            multiplicativeInverses(equation, distinct);
        }
        List<ParameterHypothesis> result = new ArrayList<>(distinct.values());
        result.sort(ParameterHypothesis.CANONICAL_ORDER);
        return List.copyOf(result);
    }

    private void additiveInverses(Equation equation, Map<String, ParameterHypothesis> out) {
        List<HypothesisExpressions.SignedTerm> terms = new ArrayList<>();
        terms.addAll(HypothesisExpressions.additiveTerms(equation.left()));
        terms.addAll(HypothesisExpressions.additiveTerms(equation.right()));
        for (HypothesisExpressions.SignedTerm term : terms) {
            if (HypothesisExpressions.isZero(term.expr())) {
                continue;
            }
            Expr expr = term.expr();
            String value;
            if (expr instanceof NumberExpr number) {
                double signed = term.positive() ? number.value() : -number.value();
                double inverse = -signed;
                value = (inverse > 0 ? "+" : "") + HypothesisExpressions.formatNumber(inverse);
            } else {
                String canonical = HypothesisExpressions.format(expr);
                String body = HypothesisExpressions.isComposite(expr) ? "(" + canonical + ")" : canonical;
                value = (term.positive() ? "-" : "+") + body;
            }
            ParameterHypothesis hypothesis = new ParameterHypothesis(
                    RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES,
                    "isolate",
                    value,
                    value,
                    HypothesisSource.EQUATION_ISOLATION,
                    0.9,
                    "moves summand " + HypothesisExpressions.format(expr) + " to the other side",
                    List.of("summand=" + HypothesisExpressions.format(expr)));
            out.putIfAbsent(hypothesis.dedupeKey(), hypothesis);
        }
    }

    private void multiplicativeInverses(Equation equation, Map<String, ParameterHypothesis> out) {
        for (Expr side : List.of(equation.left(), equation.right())) {
            List<Expr> factors = HypothesisExpressions.multiplicativeFactors(side);
            if (factors.size() < 2) {
                continue;
            }
            for (Expr factor : factors) {
                if (HypothesisExpressions.isZero(factor)) {
                    continue;
                }
                String canonical = HypothesisExpressions.format(factor);
                String body = HypothesisExpressions.isComposite(factor) ? "(" + canonical + ")" : canonical;
                String value = "/" + body;
                ParameterHypothesis hypothesis = new ParameterHypothesis(
                        RewriteMoveKind.MULTIPLY_SAME_TERM_BOTH_SIDES,
                        "isolate",
                        value,
                        value,
                        HypothesisSource.EQUATION_ISOLATION,
                        0.85,
                        "divides both sides by factor " + canonical,
                        List.of("factor=" + canonical));
                out.putIfAbsent(hypothesis.dedupeKey(), hypothesis);
            }
        }
    }
}
