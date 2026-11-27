package de.regelsuche.sdk.optimization.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaCenterSearch;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.util.List;
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

    @Test void discoversAndVerifiesAnIdentityWithNoScalarCenter() {
        var x = new RationalFormula.Variable(17);
        var y = new RationalFormula.Variable(-2);
        var one = new RationalFormula.Scalar(ExactRational.ONE);
        var commutator = new RationalFormula.Sum(new RationalFormula.Product(x, y),
            new RationalFormula.Product(new RationalFormula.Scalar(ExactRational.NEGATIVE_ONE),
                new RationalFormula.Product(y, x)));
        var identity = new RationalFormula.Product(new RationalFormula.Inverse(commutator), commutator);
        var limits = new IterationLimits(100_000_000, 1_000_000, 60_000);
        var policy = new RationalFormulaCenterSearch.MatrixPolicy(
            List.of(ExactRational.ZERO, ExactRational.ONE), List.of(1, 2), 40);
        var result = RationalFormulaSdk.compare(identity, one, policy, limits, CancellationToken.NONE);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        assertEquals(2, result.center().orElseThrow().order());
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, RationalFormulaSdk.verifyComparison(identity, one,
            result.center().orElseThrow(), result.evidence().orElseThrow(), limits, CancellationToken.NONE).status());
    }
}
