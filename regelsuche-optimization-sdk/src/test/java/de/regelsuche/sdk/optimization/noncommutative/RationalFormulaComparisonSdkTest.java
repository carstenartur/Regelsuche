package de.regelsuche.sdk.optimization.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RationalFormulaComparisonSdkTest {
    @Test void compareAndVerifyConstants() {
        var one = new RationalFormula.Scalar(ExactRational.ONE);
        var two = new RationalFormula.Sum(one, one);
        var limits = new IterationLimits(100_000, 15_000, 10_000);
        var center = new Assignment(1, Map.of());
        var r = RationalFormulaSdk.compare(one, two, center, limits, CancellationToken.NONE);
        assertEquals(Status.DISTINCT, r.status());
        assertEquals(Status.DISTINCT, RationalFormulaSdk.verifyComparison(one, two, center,
            r.evidence().orElseThrow(), limits, CancellationToken.NONE).status());
    }
}
