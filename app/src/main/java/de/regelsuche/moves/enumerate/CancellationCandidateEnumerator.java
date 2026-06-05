package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates cancellation candidates: additive terms that would neutralise an
 * obvious term in the expression. For example, {@code x - 1} yields the
 * candidate {@code +1}, which cancels the {@code -1}.
 */
public final class CancellationCandidateEnumerator implements ParameterEnumerator {
    private final ExpressionParser parser = new ExpressionParser();


    @Override
    public String id() {
        return "cancellation-candidate";
    }

    @Override
    public List<MoveParameter> enumerate(String expression) {
        if (expression != null && expression.contains("=")) {
            try {
                Equation equation = parser.parseEquation(expression);
                return enumerateEquation(equation);
            } catch (IllegalArgumentException ignored) {
                // Fall back to the term-only parser below.
            }
        }
        return MoveExpressions.parse(expression).map(this::enumerate).orElseGet(List::of);
    }

    private List<MoveParameter> enumerate(Expr root) {
        List<SignedTerm> terms = new ArrayList<>();
        flatten(root, true, terms);
        return parametersForTerms(terms);
    }

    private List<MoveParameter> enumerateEquation(Equation equation) {
        List<SignedTerm> terms = new ArrayList<>();
        flatten(equation.left(), true, terms);
        flatten(equation.right(), true, terms);
        return parametersForTerms(terms);
    }

    private List<MoveParameter> parametersForTerms(List<SignedTerm> terms) {
        Map<String, MoveParameter> distinct = new LinkedHashMap<>();
        for (SignedTerm term : terms) {
            if (!(term.expr() instanceof NumberExpr number)) {
                continue;
            }
            double signedValue = term.positive() ? number.value() : -number.value();
            if (signedValue == 0.0) {
                continue;
            }
            double cancellation = -signedValue;
            String canonical = formatNumber(cancellation);
            String signed = (cancellation > 0 ? "+" : "") + canonical;
            distinct.putIfAbsent(signed, new MoveParameter(
                    "cancel",
                    MoveParameterKind.GENERATED,
                    signed,
                    canonical,
                    MoveParameter.UNSPECIFIED_INDEX,
                    id()));
        }
        List<MoveParameter> parameters = new ArrayList<>(distinct.values());
        parameters.sort(MoveParameter.CANONICAL_ORDER);
        return List.copyOf(parameters);
    }

    private void flatten(Expr expr, boolean positive, List<SignedTerm> out) {
        if (expr instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            flatten(binary.left(), positive, out);
            flatten(binary.right(), positive, out);
        } else if (expr instanceof BinaryExpr binary && binary.operator() == BinaryOperator.SUB) {
            flatten(binary.left(), positive, out);
            flatten(binary.right(), !positive, out);
        } else {
            out.add(new SignedTerm(positive, expr));
        }
    }

    private String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private record SignedTerm(boolean positive, Expr expr) {
    }
}
