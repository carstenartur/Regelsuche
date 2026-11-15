package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionTest.source;
import static org.junit.jupiter.api.Assertions.*;

class OrderedStateChoiceBoundaryTest {
    private static final IterationLimits LIMITS = new IterationLimits(10_000, 64, 10_000);

    @Test
    void retainsTheExactSourceAndProofWhenOnlyTheBlockStatisticsAreSupplied() {
        var input = source("(x0-x1)^2", 2);
        var direct = OrderedReductionSearch.searchPointUpdates(input, LIMITS, 4, () -> false);
        var supported = OrderedReductionSearch.searchWithBlockStatistics(input,
            List.of(moment(), moment(0), moment(0, 0)), LIMITS, 4, () -> false);
        var ordinaryPlan = direct.verified().orElseThrow().plan();
        var supportedPlan = supported.verified().orElseThrow().plan();
        assertEquals(ordinaryPlan.reduction(), supportedPlan.reduction());
        assertEquals(direct.metrics().verificationWork(), supported.metrics().verificationWork());
        assertTrue(supported.metrics().totalWork() < direct.metrics().totalWork());
    }

    @Test
    void rejectsASuppliedStateMissingACrossCutFactor() {
        var result = OrderedReductionSearch.searchWithBlockStatistics(source("(x0-x1)^2", 2),
            List.of(moment(), moment(0)), LIMITS, 4, () -> false);
        assertTrue(result.verified().isEmpty());
        assertEquals("MISSING_BLOCK_STATISTIC", result.reason());
        assertTrue(result.metrics().verificationWork() > 0);
    }

    private static List<Moment> moment(Integer... columns) {
        return List.of(new Moment(List.of(columns)));
    }
}
