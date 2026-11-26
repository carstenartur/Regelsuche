package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Step.*;
import static java.math.BigInteger.*;
import static org.junit.jupiter.api.Assertions.*;

class QuadraticRootPreparedTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 8192, 30_000);
    private static final BigInteger P = valueOf(43);
    private static final BigInteger HALF = valueOf(22);
    private static final BigInteger E = valueOf(11);
    private static final QuadraticRootProgram PROGRAM = new QuadraticRootProgram(
            List.of(constant(HALF), add(0, 2), multiply(4, 3), power(5, E), square(6)), 7, 5,
            new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(8, 9)), 6, 10, false),
            new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3), power(9, E),
                    multiply(1, 3), inverse(10), multiply(11, 12)), 10, 13, false));
    private static final QuadraticRootContract SOURCE = new QuadraticRootContract("prepared-fixture", P,
            new PrimeEvidence.SmallPrime(P), PROGRAM, QuadraticRootContract.Observation.EXACT_PAIR);

    private static QuadraticRootSearch.Certificate certificate() {
        return QuadraticRootSearch.search(SOURCE, new SharedPowerPlan.Weights(1, 1, 1000),
                64, LIMITS, () -> false).certificate().orElseThrow();
    }

    @Test void repeatedCallsOnlyPayForExecutionAndRetainBothNormSigns() {
        var cert = certificate();
        var preparation = QuadraticRootEvaluation.prepare(SOURCE, cert, LIMITS, () -> false);
        var plan = preparation.plan().orElseThrow();
        assertEquals(QuadraticRootVerifier.verify(SOURCE, cert, LIMITS, () -> false).work(), preparation.work());
        for (var norm : List.of(TWO, P.subtract(TWO))) {
            var replay = QuadraticRootEvaluation.evaluate(SOURCE, cert, ZERO, TWO, norm, LIMITS, () -> false);
            var execution = plan.evaluate(ZERO, TWO, norm, LIMITS, () -> false);
            assertEquals(new QuadraticRootEvaluation.Pair(ONE, ONE), execution.pair().orElseThrow());
            assertEquals(replay.pair(), execution.pair());
            assertEquals(preparation.work() + execution.work(), replay.work());
            assertTrue(execution.work() * 10 < replay.work());
            assertEquals(execution.pair(), plan.evaluate(ZERO, TWO, norm, LIMITS.withMaximumWork(execution.work()),
                    () -> false).pair());
            assertEquals("WORK_LIMIT", plan.evaluate(ZERO, TWO, norm, LIMITS.withMaximumWork(execution.work() - 1),
                    () -> false).reason());
        }
    }

    @Test void preparedCallsCheckActualCoordinatesNormTermsAndCancellationEveryTime() {
        var plan = QuadraticRootEvaluation.prepare(SOURCE, certificate(), LIMITS, () -> false).plan().orElseThrow();
        for (var triple : new BigInteger[][] {{null, TWO, TWO}, {ZERO, null, TWO}, {ZERO, TWO, null},
                {P, TWO, TWO}, {ZERO, TWO.negate(), TWO}, {ZERO, TWO, P}, {ZERO, ZERO, ZERO}, {ZERO, TWO, ONE}}) {
            var result = plan.evaluate(triple[0], triple[1], triple[2], LIMITS, () -> false);
            assertTrue(result.pair().isEmpty());
            assertEquals("OUTSIDE_FIELD_DOMAIN", result.reason());
        }
        assertEquals("TERM_LIMIT", plan.evaluate(ZERO, TWO, TWO, new IterationLimits(1000, 1, 1000), () -> false).reason());
        assertEquals("CANCELLED", plan.evaluate(ZERO, TWO, TWO, LIMITS, () -> true).reason());
    }

    @Test void incompleteOrStaleVerificationNeverCreatesAPlan() {
        var cert = certificate();
        var stale = new QuadraticRootContract("different-source", P, SOURCE.primeEvidence(), PROGRAM, SOURCE.observation());
        var forged = new QuadraticRootSearch.Certificate(SOURCE, cert.power(), PROGRAM);
        for (var result : List.of(QuadraticRootEvaluation.prepare(stale, cert, LIMITS, () -> false),
                QuadraticRootEvaluation.prepare(SOURCE, forged, LIMITS, () -> false),
                QuadraticRootEvaluation.prepare(SOURCE, cert, LIMITS.withMaximumWork(20), () -> false),
                QuadraticRootEvaluation.prepare(SOURCE, cert, LIMITS, () -> true))) {
            assertTrue(result.plan().isEmpty(), result.reason());
        }
    }
}
