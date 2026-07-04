package de.regelsuche.equivalence;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.algebra.QuadraticCoefficients;
import java.util.Optional;

public class SymPyEquivalenceService implements EquivalenceService {
    private final DeterministicNumericEquivalence numericEquivalence = new DeterministicNumericEquivalence();

    @Override
    public boolean areEquivalent(String leftExpression, String rightExpression) {
        Boolean sampled = numericEquivalence.areEquivalent(leftExpression, rightExpression);
        if (sampled != null) {
            return sampled;
        }
        Optional<QuadraticCoefficients> left = QuadraticAnalyzer.analyze(leftExpression);
        Optional<QuadraticCoefficients> right = QuadraticAnalyzer.analyze(rightExpression);
        return left.isPresent() && right.isPresent() && left.orElseThrow().equals(right.orElseThrow());
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        Boolean sampled = numericEquivalence.areEquivalent(leftExpression, rightExpression);
        if (Boolean.TRUE.equals(sampled)) {
            return "validated by deterministic numeric samples";
        }
        if (Boolean.FALSE.equals(sampled)) {
            return "not equivalent under deterministic numeric samples";
        }
        Optional<QuadraticCoefficients> left = QuadraticAnalyzer.analyze(leftExpression);
        Optional<QuadraticCoefficients> right = QuadraticAnalyzer.analyze(rightExpression);
        if (left.isPresent() && right.isPresent() && left.orElseThrow().equals(right.orElseThrow())) {
            return "matching normalized quadratic coefficients";
        }
        return "no equivalence evidence found";
    }
}
