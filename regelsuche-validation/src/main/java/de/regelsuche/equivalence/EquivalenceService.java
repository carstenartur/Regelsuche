package de.regelsuche.equivalence;

public interface EquivalenceService {
    boolean areEquivalent(String leftExpression, String rightExpression);

    default String evidence(String leftExpression, String rightExpression) {
        return areEquivalent(leftExpression, rightExpression) ? "equivalent" : "not equivalent";
    }
}
