package de.regelsuche.equivalence;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.algebra.QuadraticCoefficients;
import java.util.Optional;

public class SymPyEquivalenceService implements EquivalenceService {
    @Override
    public boolean areEquivalent(String leftExpression, String rightExpression) {
        if (leftExpression == null || rightExpression == null) {
            return false;
        }
        if (leftExpression.equals(rightExpression)) {
            return true;
        }
        Optional<QuadraticCoefficients> left = QuadraticAnalyzer.analyze(leftExpression);
        Optional<QuadraticCoefficients> right = QuadraticAnalyzer.analyze(rightExpression);
        return left.isPresent() && right.isPresent() && left.orElseThrow().equals(right.orElseThrow());
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        return areEquivalent(leftExpression, rightExpression)
            ? "matching normalized quadratic coefficients"
            : "no equivalence evidence found";
    }
}
