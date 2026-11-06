package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;

/** Stable, source-independent scalar structure goals. Equivalence remains the independent verifier's job. */
public enum StructuralTaskObjective implements TypedSourceOnlySearch.Objective {
    FACTORIZATION("factor-v1"), SUM_OF_TWO_SQUARES("two-squares-v1"), PERFECT_SQUARE("square-v1"),
    EXPANDED_POLYNOMIAL("expanded-v1");

    private final String key;
    /** Reserved for an unsupported structural domain or exact projection. */
    static final long UNSUPPORTED_SCORE = 8;
    StructuralTaskObjective(String key) { this.key = key; }
    public String key() { return key; }
    public CheckedSchemaTaskSolver.Goal goal() { return new CheckedSchemaTaskSolver.Goal(this, 0); }
    static boolean requiresExactPolynomialSteps(TypedSourceOnlySearch.Objective objective) {
        return objective == PERFECT_SQUARE || objective == EXPANDED_POLYNOMIAL;
    }

    @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) {
        // New structural objectives retain their product ledger through method references.
        return evaluate(state, this == PERFECT_SQUARE || this == EXPANDED_POLYNOMIAL);
    }

    /** Opt-in costing for profiles which account for polynomial term products. */
    TypedSourceOnlySearch.Score evaluateWithProductWork(TypedMoveSearch.State state) {
        return evaluate(state, true);
    }

    private TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state, boolean includeProductWork) {
        var work = new CheckedSchemaSupport.Work();
        try {
            CheckedSchemaSupport.domain(state.expression(), CheckedLearnedSchemaModel.Bounds.defaults(), work);
            var exact = includeProductWork ? ExactPolynomialAnalysis.withProductWork(work::add)
                : new ExactPolynomialAnalysis(work::add);
            long score = score(state.expression(), exact, work);
            return new TypedSourceOnlySearch.Score(score, Math.addExact(work.units, 1));
        } catch (IllegalArgumentException unsupported) {
            return new TypedSourceOnlySearch.Score(UNSUPPORTED_SCORE, Math.addExact(work.units, 1));
        }
    }

    private long score(Expr expression, ExactPolynomialAnalysis exact, CheckedSchemaSupport.Work work) {
        if (this == EXPANDED_POLYNOMIAL) return expanded(expression, exact, work) ? 0 : 6;
        if (!(expression instanceof BinaryExpr root)) return 6;
        if (this == FACTORIZATION && root.operator() == BinaryOperator.MUL
                && nonconstant(root.left(), exact, work) && nonconstant(root.right(), exact, work)) return 0;
        if (this == SUM_OF_TWO_SQUARES && root.operator() == BinaryOperator.ADD
                && nonconstantSquare(root.left(), exact, work) && nonconstantSquare(root.right(), exact, work)) return 0;
        if (this == PERFECT_SQUARE && nonconstantSquare(expression, exact, work)) return 0;
        // Generic preparation landmarks, not comparison with a stored historical answer.
        if (root.operator() == BinaryOperator.SUB && isSquare(root.left())) return isSquare(root.right()) ? 1 : 2;
        if (root.operator() == BinaryOperator.ADD) return 5 - (isSquare(root.left()) ? 1 : 0) - (isSquare(root.right()) ? 1 : 0);
        return 6;
    }

    private static boolean expanded(Expr expression, ExactPolynomialAnalysis exact, CheckedSchemaSupport.Work work) {
        if (expression instanceof NumberExpr number && number.value().isZero()) {
            work.add(1);
            return true;
        }
        int terms = expandedTerms(expression, work);
        if (terms < 1) return false;
        String text = ExpressionFormatter.format(expression);
        work.add(text.length());
        return exact.normalForm(text).termCount() == terms;
    }

    private static int expandedTerms(Expr expression, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (expression instanceof BinaryExpr unary && unaryMinus(unary)) {
            work.add(1);
            return monomial(unary.right(), work) ? 1 : -1;
        }
        if (expression instanceof BinaryExpr binary
                && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB)) {
            int left = expandedTerms(binary.left(), work), right = expandedTerms(binary.right(), work);
            if (binary.operator() == BinaryOperator.SUB && binary.right() instanceof BinaryExpr nested
                    && (nested.operator() == BinaryOperator.ADD || nested.operator() == BinaryOperator.SUB)) return -1;
            return left < 0 || right < 0 ? -1 : Math.addExact(left, right);
        }
        if (expression instanceof NumberExpr number && number.value().isZero()) return -1;
        return monomial(expression, work) ? 1 : -1;
    }

    private static boolean monomial(Expr expression, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (expression instanceof NumberExpr || expression instanceof VariableExpr) return true;
        if (!(expression instanceof BinaryExpr binary)) return false;
        if (binary.operator() == BinaryOperator.MUL) {
            boolean left = monomial(binary.left(), work), right = monomial(binary.right(), work);
            return left && right;
        }
        if (unaryMinus(binary)) { work.add(1); return monomial(binary.right(), work); }
        if (binary.operator() == BinaryOperator.DIV)
            return binary.right() instanceof NumberExpr number && !number.value().isZero() && monomial(binary.left(), work);
        return binary.operator() == BinaryOperator.POW && binary.left() instanceof VariableExpr
            && binary.right() instanceof NumberExpr;
    }

    private static boolean unaryMinus(BinaryExpr expression) {
        return expression.operator() == BinaryOperator.SUB && expression.left() instanceof NumberExpr number
            && number.value().isZero();
    }

    private static boolean nonconstantSquare(Expr expression, ExactPolynomialAnalysis exact, CheckedSchemaSupport.Work work) {
        return isSquare(expression) && nonconstant(((BinaryExpr) expression).left(), exact, work);
    }
    private static boolean nonconstant(Expr expression, ExactPolynomialAnalysis exact, CheckedSchemaSupport.Work work) {
        String text = ExpressionFormatter.format(expression); work.add(text.length());
        return exact.normalForm(text).totalDegree() > 0;
    }
    static boolean isSquare(Expr expression) {
        return expression instanceof BinaryExpr power && power.operator() == BinaryOperator.POW
            && power.right() instanceof NumberExpr exponent && exponent.value().equalsInteger(2);
    }
}
