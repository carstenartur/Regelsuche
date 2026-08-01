package de.regelsuche.search.strategy;

/**
 * Deterministic outer-search work vector independent of transformation-engine
 * and exact path-audit work.
 */
public record SearchWorkMetrics(
    long exploredStates,
    long expandedStates,
    long generatedTransformations,
    long enqueuedStates,
    long duplicatePrunes,
    long repeatedApplicationPrunes,
    long sameExpressionPrunes,
    long expansionBudgetPrunes,
    long primitiveBudgetPrunes,
    long candidateBudgetPrunes,
    long statesWithoutTransformations,
    long engineBatches
) {
    public static final SearchWorkMetrics ZERO = new SearchWorkMetrics(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public SearchWorkMetrics {
        if (exploredStates < 0
                || expandedStates < 0
                || generatedTransformations < 0
                || enqueuedStates < 0
                || duplicatePrunes < 0
                || repeatedApplicationPrunes < 0
                || sameExpressionPrunes < 0
                || expansionBudgetPrunes < 0
                || primitiveBudgetPrunes < 0
                || candidateBudgetPrunes < 0
                || statesWithoutTransformations < 0
                || engineBatches < 0) {
            throw new IllegalArgumentException(
                "search work metrics must not be negative");
        }
    }

    public long totalWorkUnits() {
        long total = exploredStates;
        total = add(total, expandedStates);
        total = add(total, generatedTransformations);
        total = add(total, enqueuedStates);
        total = add(total, duplicatePrunes);
        total = add(total, repeatedApplicationPrunes);
        total = add(total, sameExpressionPrunes);
        total = add(total, expansionBudgetPrunes);
        total = add(total, primitiveBudgetPrunes);
        total = add(total, candidateBudgetPrunes);
        total = add(total, statesWithoutTransformations);
        return add(total, engineBatches);
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
