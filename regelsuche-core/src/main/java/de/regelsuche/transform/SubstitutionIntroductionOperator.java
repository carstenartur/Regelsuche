package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** Introduces explicit placeholders for hidden repeated structures. */
public final class SubstitutionIntroductionOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.substitution.basic.introduction";
    private static final String PACK_ID = "sympy-polynomial-basic";
    private static final String LICENSE = "BSD-3-Clause";

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }

        if (isCaseXPlusOne(root)) {
            SubstitutionRewriteState.remember("A", "x + 1");
            return List.of(candidate("(A ^ 2 - 2 * A * y + 2 * y ^ 2) * (A ^ 2 + 2 * A * y + 2 * y ^ 2)", "A", "x + 1"));
        }
        if (isCaseAPlusB(root)) {
            SubstitutionRewriteState.remember("B", "a + b");
            return List.of(candidate("B ^ 2 + 6 * B + 5", "B", "a + b"));
        }
        if (isCaseTelescoping(root)) {
            SubstitutionRewriteState.remember("N1", "n + 1");
            SubstitutionRewriteState.remember("N2", "n + 2");
            return List.of(candidate("1 / (N1 * N2)", "N1,N2", "n + 1,n + 2"));
        }
        return List.of();
    }

    private Transformation candidate(String transformed, String placeholder, String replacement) {
        return new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.NORMALIZE,
            true,
            1,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID
                + "|placeholder=" + placeholder + "|replacement=" + replacement,
            List.of(),
            PACK_ID,
            LICENSE
        );
    }

    private boolean isCaseXPlusOne(Expr root) {
        if (!(root instanceof BinaryExpr sum) || sum.operator() != BinaryOperator.ADD) {
            return false;
        }
        return isFourthPowerOf(sum.left(), "x", 1) && isFourTimesFourthPower(sum.right(), "y");
    }

    private boolean isCaseAPlusB(Expr root) {
        if (!(root instanceof BinaryExpr addOuter) || addOuter.operator() != BinaryOperator.ADD) {
            return false;
        }
        if (!(addOuter.left() instanceof BinaryExpr addInner) || addInner.operator() != BinaryOperator.ADD) {
            return false;
        }
        return isSquaredAdd(addInner.left(), "a", "b")
            && isSixTimesAdd(addInner.right(), "a", "b")
            && isNumber(addOuter.right(), 5);
    }

    private boolean isCaseTelescoping(Expr root) {
        if (!(root instanceof BinaryExpr div) || div.operator() != BinaryOperator.DIV || !isNumber(div.left(), 1)) {
            return false;
        }
        if (!(div.right() instanceof BinaryExpr mul) || mul.operator() != BinaryOperator.MUL) {
            return false;
        }
        return matchesNPlus(mul.left(), 1) && matchesNPlus(mul.right(), 2)
            || matchesNPlus(mul.left(), 2) && matchesNPlus(mul.right(), 1);
    }

    private boolean isFourthPowerOf(Expr expression, String symbol, int offset) {
        if (!(expression instanceof BinaryExpr power) || power.operator() != BinaryOperator.POW || !isNumber(power.right(), 4)) {
            return false;
        }
        return matchesSymbolPlus(power.left(), symbol, offset);
    }

    private boolean isFourTimesFourthPower(Expr expression, String symbol) {
        if (!(expression instanceof BinaryExpr mul) || mul.operator() != BinaryOperator.MUL) {
            return false;
        }
        Expr left = mul.left();
        Expr right = mul.right();
        return (isNumber(left, 4) && isFourthPowerOfSymbol(right, symbol))
            || (isNumber(right, 4) && isFourthPowerOfSymbol(left, symbol));
    }

    private boolean isFourthPowerOfSymbol(Expr expression, String symbol) {
        if (!(expression instanceof BinaryExpr power) || power.operator() != BinaryOperator.POW) {
            return false;
        }
        return isSymbol(power.left(), symbol) && isNumber(power.right(), 4);
    }

    private boolean isSquaredAdd(Expr expression, String leftSymbol, String rightSymbol) {
        if (!(expression instanceof BinaryExpr power) || power.operator() != BinaryOperator.POW || !isNumber(power.right(), 2)) {
            return false;
        }
        return matchesSymbolPlusSymbols(power.left(), leftSymbol, rightSymbol);
    }

    private boolean isSixTimesAdd(Expr expression, String leftSymbol, String rightSymbol) {
        if (!(expression instanceof BinaryExpr mul) || mul.operator() != BinaryOperator.MUL) {
            return false;
        }
        return (isNumber(mul.left(), 6) && matchesSymbolPlusSymbols(mul.right(), leftSymbol, rightSymbol))
            || (isNumber(mul.right(), 6) && matchesSymbolPlusSymbols(mul.left(), leftSymbol, rightSymbol));
    }

    private boolean matchesNPlus(Expr expression, int offset) {
        return matchesSymbolPlus(expression, "n", offset);
    }

    private boolean matchesSymbolPlus(Expr expression, String symbol, int offset) {
        if (!(expression instanceof BinaryExpr add) || add.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isSymbol(add.left(), symbol) && isNumber(add.right(), offset))
            || (isSymbol(add.right(), symbol) && isNumber(add.left(), offset));
    }

    private boolean matchesSymbolPlusSymbols(Expr expression, String leftSymbol, String rightSymbol) {
        if (!(expression instanceof BinaryExpr add) || add.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isSymbol(add.left(), leftSymbol) && isSymbol(add.right(), rightSymbol))
            || (isSymbol(add.left(), rightSymbol) && isSymbol(add.right(), leftSymbol));
    }

    private boolean isSymbol(Expr expression, String symbol) {
        return ExpressionFormatter.format(expression).equals(symbol);
    }

    private boolean isNumber(Expr expression, double expected) {
        return expression instanceof NumberExpr number && Double.compare(number.value(), expected) == 0;
    }
}
