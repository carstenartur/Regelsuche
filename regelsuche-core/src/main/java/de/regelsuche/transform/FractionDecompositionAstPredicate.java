package de.regelsuche.transform;

/** Structural predicate for decomposed fractions. */
public final class FractionDecompositionAstPredicate {
    private FractionDecompositionAstPredicate() {
    }

    public static boolean containsFractionDecomposition(String expression) {
        return TelescopingDifferenceAstPredicate.containsTelescopingDifference(expression);
    }
}
