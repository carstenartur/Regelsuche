package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashSet;

/** Recognizes exact, explicitly stated scalar zero/nonzero conflicts in a conditional path. */
final class ExplicitZeroConditions {
    private ExplicitZeroConditions() {}

    static boolean conflict(AssumptionSignature signature) {
        var terms = signature.normalizedAssumptions();
        if (terms.stream().noneMatch(term -> term.endsWith(" != 0"))) return false;
        var zeros = new HashSet<Expr>();
        var nonzeros = new HashSet<Expr>();
        var parser = new ExpressionParser();
        for (var term : terms) {
            try {
                if (term.endsWith(" != 0")) {
                    nonzeros.add(parser.parseTerm(term.substring(0, term.length() - " != 0".length())));
                } else if (term.contains("=") && !term.contains("!") && !term.contains("<") && !term.contains(">")) {
                    var equation = parser.parseEquation(term);
                    if (zero(equation.left())) zeros.add(equation.right());
                    if (zero(equation.right())) zeros.add(equation.left());
                }
            } catch (IllegalArgumentException opaquePredicate) {
                // Other assumption languages remain outside this exact structural decision.
            }
        }
        return nonzeros.stream().anyMatch(term -> zero(term) || zeros.contains(term));
    }

    private static boolean zero(Expr term) {
        return term instanceof NumberExpr number && number.value().isZero();
    }
}
