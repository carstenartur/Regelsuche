package de.regelsuche.transform;

public final class PolynomialBridgeAstPredicate {
    private PolynomialBridgeAstPredicate() {
    }

    public static boolean containsBridge(String expression) {
        return SquareDifferenceAstPredicate.containsSquareDifference(expression)
            || PerfectSquareAstPredicate.containsPerfectSquare(expression);
    }
}
