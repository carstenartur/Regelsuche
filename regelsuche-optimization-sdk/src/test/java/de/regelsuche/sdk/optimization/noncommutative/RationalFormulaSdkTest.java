package de.regelsuche.sdk.optimization.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaCenterSearch;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RationalFormulaSdkTest {
    @Test void discoversACenterAndReplaysTheOriginalDomainAwareCertificate() {
        var source = new Inverse(new Inverse(new Variable(12)));
        var target = new Variable(12);
        var limits = new IterationLimits(1_000_000, 100_000, 5_000);
        var found = RationalFormulaSdk.compare(source, target, RationalFormulaCenterSearch.Policy.defaults(),
            limits, CancellationToken.NONE);
        assertEquals(2, found.centersTried());
        assertEquals(RationalFormulaComparison.Status.EQUAL_ON_SHARED_DOMAIN,
            RationalFormulaSdk.verifyComparison(source, target, found.center().orElseThrow(),
                found.evidence().orElseThrow(), limits, CancellationToken.NONE).status());
    }

    @Test void realizesVerifiesAndEvaluatesAnInverseOverRationals() {
        var formula = new Inverse(new Variable(12));
        var limits = new IterationLimits(100_000, 10_000, 5_000);
        var certificate = RationalFormulaSdk.realize(formula, limits, CancellationToken.NONE)
            .certificate().orElseThrow();
        assertEquals(Status.VERIFIED, RationalFormulaSdk.verify(formula, certificate, limits, CancellationToken.NONE)
            .status());
        var assignment = new Assignment(1, Map.of(12, new Matrix(1, List.of(ExactRational.integer(2)))));
        var evaluated = RationalFormulaSdk.evaluate(formula, certificate, assignment, limits, CancellationToken.NONE);
        assertEquals(Status.DEFINED, evaluated.status(), evaluated.reason());
        assertEquals(new Matrix(1, List.of(new ExactRational(BigInteger.ONE, BigInteger.TWO))),
            evaluated.value().orElseThrow());
        assertEquals(Status.CANCELLED, RationalFormulaSdk.evaluate(formula, certificate, assignment, limits, () -> true)
            .status());
    }
}
