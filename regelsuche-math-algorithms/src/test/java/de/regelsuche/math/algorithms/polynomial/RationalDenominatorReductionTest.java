package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.math.algorithms.polynomial.RationalDenominatorReduction.Coefficient;
import de.regelsuche.math.algorithms.polynomial.RationalDenominatorReduction.GuardedPlan;
import de.regelsuche.math.algorithms.polynomial.RationalDenominatorReduction.Plan;
import de.regelsuche.polynomial.PolynomialWorkSink;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RationalDenominatorReductionTest {
    private static final BigInteger PRIME = new BigInteger(
        "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);
    private static final PolynomialWorkSink UNMETERED = PolynomialWorkSink.none();

    @Test
    void discoversAndVerifiesDenominatorOver381BitModulus() {
        // (t^2 - t + 2)^2 and (t^2 - t + 2)^3, in ascending order.
        List<Coefficient> x = real(4, -4, 5, -2, 1);
        List<Coefficient> y = real(8, -12, 18, -13, 9, -3, 1);

        Plan plan = RationalDenominatorReduction.discover(x, y, PRIME, UNMETERED).orElseThrow();

        assertEquals(new Plan(List.of(coefficient(2, 0),
            new Coefficient(PRIME.subtract(BigInteger.ONE), BigInteger.ZERO), coefficient(1, 0))), plan);
        assertTrue(RationalDenominatorReduction.verify(x, y, PRIME, plan, UNMETERED));
    }

    @Test
    void discoversAndVerifiesDenominatorOverQuadraticExtension() {
        // (t + 2 + 3u)^2 and (t + 2 + 3u)^3, where u^2 = -1.
        List<Coefficient> x = List.of(coefficient(-5, 12), coefficient(4, 6), coefficient(1, 0));
        List<Coefficient> y = List.of(coefficient(-46, 9), coefficient(-15, 36),
            coefficient(6, 9), coefficient(1, 0));

        Plan plan = RationalDenominatorReduction.discover(x, y, PRIME, UNMETERED).orElseThrow();

        assertEquals(new Plan(List.of(coefficient(2, 3), coefficient(1, 0))), plan);
        assertTrue(RationalDenominatorReduction.verify(x, y, PRIME, plan, UNMETERED));
    }

    @Test
    void rejectsExactQuotientWhenXIsNotItsSquare() {
        List<Coefficient> x = real(1, 0, 1);
        List<Coefficient> y = real(2, 1, 2, 1);

        assertTrue(RationalDenominatorReduction.discover(x, y, PRIME, UNMETERED).isEmpty());
        assertFalse(RationalDenominatorReduction.verify(x, y, PRIME, new Plan(real(2, 1)), UNMETERED));
    }

    @Test
    void rejectsCorruptCubeAndForgedPlan() {
        List<Coefficient> x = real(4, 4, 1);
        List<Coefficient> y = real(8, 12, 6, 1);
        List<Coefficient> corrupted = List.of(coefficient(8, 1), coefficient(12, 0),
            coefficient(6, 0), coefficient(1, 0));

        assertTrue(RationalDenominatorReduction.discover(x, corrupted, PRIME, UNMETERED).isEmpty());
        assertFalse(RationalDenominatorReduction.verify(x, corrupted, PRIME, new Plan(real(2, 1)), UNMETERED));
        assertFalse(RationalDenominatorReduction.verify(x, y, PRIME, new Plan(real(3, 1)), UNMETERED));
        assertFalse(RationalDenominatorReduction.verify(x, y, PRIME, new Plan(real(2, 2)), UNMETERED));
    }

    @Test
    void rejectsNonmonicAndConstantCandidates() {
        assertTrue(RationalDenominatorReduction.discover(real(4, 4, 2), real(8, 12, 6, 1), PRIME, UNMETERED)
            .isEmpty());
        assertTrue(RationalDenominatorReduction.discover(real(4, 4, 1), real(8, 12, 6, 0), PRIME, UNMETERED)
            .isEmpty());
        assertTrue(RationalDenominatorReduction.discover(real(1), real(1), PRIME, UNMETERED).isEmpty());
        assertTrue(RationalDenominatorReduction.discover(List.of(), List.of(), PRIME, UNMETERED).isEmpty());
    }

    @Test
    void usesSuppliedModulusWithoutAssumingInvertibility() {
        List<Coefficient> x = real(4, 4, 1);
        List<Coefficient> y = real(0, 4, 6, 1);
        Plan expected = new Plan(real(2, 1));
        BigInteger composite = BigInteger.valueOf(8);

        assertEquals(expected, RationalDenominatorReduction.discover(x, y, composite, UNMETERED).orElseThrow());
        assertTrue(RationalDenominatorReduction.verify(x, y, composite, expected, UNMETERED));
        assertFalse(RationalDenominatorReduction.verify(x, y, PRIME, expected, UNMETERED));
        for (BigInteger unusable : List.of(BigInteger.ONE, BigInteger.ZERO, PRIME.negate())) {
            assertTrue(RationalDenominatorReduction.discover(x, y, unusable, UNMETERED).isEmpty());
            assertFalse(RationalDenominatorReduction.verify(x, y, unusable, expected, UNMETERED));
        }
    }

    @Test
    void discoveryAndReplayRespectWorkLimits() {
        List<Coefficient> x = real(4, 4, 1);
        List<Coefficient> y = real(8, 12, 6, 1);
        PolynomialWorkBudget discovery = new PolynomialWorkBudget(1000);
        Plan plan = RationalDenominatorReduction.discover(x, y, PRIME, discovery).orElseThrow();
        PolynomialWorkBudget verification = new PolynomialWorkBudget(1000);
        assertTrue(RationalDenominatorReduction.verify(x, y, PRIME, plan, verification));
        assertTrue(discovery.totalWorkUnits() > verification.totalWorkUnits());
        PolynomialWorkBudget limitedDiscovery = new PolynomialWorkBudget(discovery.totalWorkUnits() - 1);
        PolynomialWorkBudget limitedVerification = new PolynomialWorkBudget(verification.totalWorkUnits() - 1);

        assertThrows(PolynomialWorkBudget.LimitReached.class,
            () -> RationalDenominatorReduction.discover(x, y, PRIME, limitedDiscovery));
        assertThrows(PolynomialWorkBudget.LimitReached.class,
            () -> RationalDenominatorReduction.verify(x, y, PRIME, plan, limitedVerification));
    }

    @Test
    void infersGuardFromIntegerResidualsAndReplaysModularIdentities() {
        // The least nonnegative residues of (t^2 - t + 2)^2 and (t^2 - t + 2)^3 modulo 11.
        List<Coefficient> x = real(4, 7, 5, 9, 1);
        List<Coefficient> y = real(8, 10, 7, 9, 9, 8, 1);

        GuardedPlan guarded = RationalDenominatorReduction.inferModulus(x, y, UNMETERED).orElseThrow();

        assertEquals(new GuardedPlan(BigInteger.valueOf(11), new Plan(real(2, 10, 1))), guarded);
        assertTrue(RationalDenominatorReduction.verify(x, y, guarded.modulus(), guarded.plan(), UNMETERED));
    }

    @Test
    void inferenceReturnsTheResidualGcdAsAnExactGuard() {
        var guarded = RationalDenominatorReduction.inferModulus(real(9, 6, 1), real(5, 5, 9, 1), UNMETERED)
            .orElseThrow();

        assertEquals(new GuardedPlan(BigInteger.valueOf(22), new Plan(real(3, 1))), guarded);
    }

    @Test
    void inferenceRejectsMissingModulusEvidenceAndOversizedInput() {
        List<Coefficient> x = real(4, 4, 1);
        List<Coefficient> exactCube = real(8, 12, 6, 1);
        List<Coefficient> coprimeResidual = real(8, 11, 6, 1);
        List<Coefficient> oversized = List.of(new Coefficient(BigInteger.ONE.shiftLeft(16384), BigInteger.ZERO),
            coefficient(4, 0), coefficient(1, 0));
        List<Coefficient> quadratic = List.of(coefficient(4, 1), coefficient(4, 0), coefficient(1, 0));

        assertTrue(RationalDenominatorReduction.inferModulus(x, exactCube, UNMETERED).isEmpty());
        assertTrue(RationalDenominatorReduction.inferModulus(x, coprimeResidual, UNMETERED).isEmpty());
        assertTrue(RationalDenominatorReduction.inferModulus(oversized, exactCube, UNMETERED).isEmpty());
        assertTrue(RationalDenominatorReduction.inferModulus(quadratic, exactCube, UNMETERED).isEmpty());
        assertThrows(PolynomialWorkBudget.LimitReached.class,
            () -> RationalDenominatorReduction.inferModulus(x, exactCube, new PolynomialWorkBudget(1)));
    }

    private static List<Coefficient> real(long... values) {
        return java.util.Arrays.stream(values).mapToObj(value -> coefficient(value, 0)).toList();
    }

    private static Coefficient coefficient(long real, long imaginary) {
        return new Coefficient(BigInteger.valueOf(real), BigInteger.valueOf(imaginary));
    }
}
