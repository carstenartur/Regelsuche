package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;

/** Stable, source-independent scalar structure goals. Equivalence remains the independent verifier's job. */
public enum StructuralTaskObjective implements TypedSourceOnlySearch.Objective {
    FACTORIZATION("factor-v1"), SUM_OF_TWO_SQUARES("two-squares-v1");

    private final String key;
    StructuralTaskObjective(String key) { this.key = key; }
    public String key() { return key; }
    public CheckedSchemaTaskSolver.Goal goal() { return new CheckedSchemaTaskSolver.Goal(this, 0); }

    @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) {
        var work = new CheckedSchemaSupport.Work();
        try {
            CheckedSchemaSupport.domain(state.expression(), CheckedLearnedSchemaModel.Bounds.defaults(), work);
            var exact = new ExactPolynomialAnalysis(work::add);
            long score = score(state.expression(), exact, work);
            return new TypedSourceOnlySearch.Score(score, Math.addExact(work.units, 1));
        } catch (IllegalArgumentException unsupported) {
            return new TypedSourceOnlySearch.Score(8, Math.addExact(work.units, 1));
        }
    }

    private long score(Expr expression, ExactPolynomialAnalysis exact, CheckedSchemaSupport.Work work) {
        if (!(expression instanceof BinaryExpr root)) return 6;
        if (this == FACTORIZATION && root.operator() == BinaryOperator.MUL
                && nonconstant(root.left(), exact, work) && nonconstant(root.right(), exact, work)) return 0;
        if (this == SUM_OF_TWO_SQUARES && root.operator() == BinaryOperator.ADD
                && nonconstantSquare(root.left(), exact, work) && nonconstantSquare(root.right(), exact, work)) return 0;
        // Generic preparation landmarks, not comparison with a stored historical answer.
        if (root.operator() == BinaryOperator.SUB && isSquare(root.left())) return isSquare(root.right()) ? 1 : 2;
        if (root.operator() == BinaryOperator.ADD) return 5 - (isSquare(root.left()) ? 1 : 0) - (isSquare(root.right()) ? 1 : 0);
        return 6;
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
