package de.regelsuche.search.estimate;

import java.util.List;

/**
 * Continuously estimates the size of a transformation search space from the
 * number of states discovered at each search depth (the per-depth frontier
 * sizes).
 *
 * <p>This implements the "Suchraumabschätzung" runtime requirement of issue #74.
 * The estimate is deliberately approximate — it only needs to help a user judge
 * the complexity of a query and warn early about a possible explosion of the
 * search space. The estimator is pure (no search state of its own): callers feed
 * it the observed frontier sizes and it returns a {@link SearchSpaceEstimate}.</p>
 *
 * <p>The growth rate (branching factor) is the geometric mean of the ratios
 * between successive non-empty frontier sizes. Using the geometric mean keeps the
 * estimate stable when individual depths fluctuate. The expected total size is the
 * known count plus a geometric projection of the deepest observed frontier up to
 * the depth bound, saturated so it never overflows {@code long}.</p>
 */
public final class SearchSpaceEstimator {

    /** Below this branching factor the frontier is considered stable. */
    static final double STABLE_BRANCHING = 1.05;
    /** At or above this branching factor the frontier grows noticeably. */
    static final double MODERATE_BRANCHING = 1.20;
    /** At or above this branching factor an explosion is likely. */
    static final double EXPLOSIVE_BRANCHING = 2.0;

    /**
     * Estimates the search space from per-depth frontier sizes.
     *
     * @param statesPerDepth number of states discovered at depth {@code 0, 1, 2, …};
     *                       index {@code i} holds the count for depth {@code i}.
     *                       Must not be {@code null}; entries must be non-negative.
     * @param maxDepth       depth bound of the search (from the heuristic); the
     *                       projection extrapolates growth up to this depth
     * @param visitBudget    maximum number of states the search may visit
     *                       (e.g. {@code SearchHeuristic.maxVisitedExpressions()}),
     *                       used to decide whether to warn about an explosion; must
     *                       be positive
     * @return the estimate; never {@code null}
     */
    public SearchSpaceEstimate estimate(List<Long> statesPerDepth, int maxDepth, long visitBudget) {
        if (statesPerDepth == null) {
            throw new IllegalArgumentException("statesPerDepth must not be null");
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative");
        }
        if (visitBudget < 1) {
            throw new IllegalArgumentException("visitBudget must be positive");
        }

        long knownStateCount = 0;
        for (Long count : statesPerDepth) {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("frontier sizes must be non-negative");
            }
            knownStateCount = saturatedAdd(knownStateCount, count);
        }

        double branchingFactor = estimateBranchingFactor(statesPerDepth);
        long deepestObservedDepth = deepestNonEmptyDepth(statesPerDepth);
        long deepestFrontier = deepestNonEmptyFrontier(statesPerDepth);
        long projected = projectTotal(
            knownStateCount, branchingFactor, deepestObservedDepth, deepestFrontier, maxDepth);

        SearchSpaceRisk risk = classifyRisk(branchingFactor, projected, visitBudget);
        String warning = buildWarning(risk, projected, visitBudget, branchingFactor);
        return new SearchSpaceEstimate(knownStateCount, branchingFactor, projected, risk, warning);
    }

    private double estimateBranchingFactor(List<Long> statesPerDepth) {
        // Geometric mean of successive non-empty frontier ratios.
        double logSum = 0.0;
        int ratios = 0;
        long previous = -1;
        for (Long count : statesPerDepth) {
            if (count == 0) {
                continue;
            }
            if (previous > 0) {
                logSum += Math.log((double) count / (double) previous);
                ratios++;
            }
            previous = count;
        }
        if (ratios == 0) {
            // Not enough observations (zero or one non-empty depth): assume a
            // stable frontier rather than guessing growth.
            return 1.0;
        }
        return Math.exp(logSum / ratios);
    }

    private long deepestNonEmptyDepth(List<Long> statesPerDepth) {
        long depth = -1;
        for (int i = 0; i < statesPerDepth.size(); i++) {
            if (statesPerDepth.get(i) > 0) {
                depth = i;
            }
        }
        return depth;
    }

    private long deepestNonEmptyFrontier(List<Long> statesPerDepth) {
        for (int i = statesPerDepth.size() - 1; i >= 0; i--) {
            long count = statesPerDepth.get(i);
            if (count > 0) {
                return count;
            }
        }
        return 0;
    }

    private long projectTotal(
            long knownStateCount,
            double branchingFactor,
            long deepestObservedDepth,
            long deepestFrontier,
            int maxDepth) {
        long remainingDepths = maxDepth - deepestObservedDepth;
        if (remainingDepths <= 0 || deepestFrontier <= 0 || branchingFactor <= 0) {
            return knownStateCount;
        }
        // A stable or shrinking frontier roughly contributes one more frontier per
        // remaining depth; modelling that as flat avoids overstating the total.
        double frontier = deepestFrontier;
        long projected = knownStateCount;
        for (long step = 0; step < remainingDepths; step++) {
            frontier *= branchingFactor;
            if (frontier >= (double) Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            projected = saturatedAdd(projected, (long) Math.ceil(frontier));
            if (projected == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
        }
        return projected;
    }

    private SearchSpaceRisk classifyRisk(double branchingFactor, long projected, long visitBudget) {
        boolean exceedsBudget = projected >= visitBudget;
        boolean farExceedsBudget = projected != Long.MAX_VALUE
            ? projected >= saturatedMultiply(visitBudget, 10)
            : true;
        if (branchingFactor >= EXPLOSIVE_BRANCHING || farExceedsBudget) {
            return SearchSpaceRisk.EXPLOSIVE;
        }
        if (branchingFactor >= MODERATE_BRANCHING || exceedsBudget) {
            return SearchSpaceRisk.HIGH;
        }
        if (branchingFactor >= STABLE_BRANCHING) {
            return SearchSpaceRisk.MODERATE;
        }
        return SearchSpaceRisk.LOW;
    }

    private String buildWarning(
            SearchSpaceRisk risk, long projected, long visitBudget, double branchingFactor) {
        if (risk == SearchSpaceRisk.LOW || risk == SearchSpaceRisk.MODERATE) {
            return null;
        }
        String size = projected == Long.MAX_VALUE ? "very large" : "~" + projected;
        return String.format(
            "Possible search-space explosion: estimated growth rate %.2f, expected %s states "
                + "exceed the visit budget of %d.",
            branchingFactor, size, visitBudget);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private static long saturatedMultiply(long a, long b) {
        long result = a * b;
        if (a != 0 && (result / a != b || (a == -1 && b == Long.MIN_VALUE))) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}
