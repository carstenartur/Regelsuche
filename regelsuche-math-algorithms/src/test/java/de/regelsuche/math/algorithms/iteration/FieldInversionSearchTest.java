package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FieldInversionReduction;
import de.regelsuche.ast.iteration.FieldInversionReduction.Factor;
import de.regelsuche.ast.iteration.FieldInversionReduction.FixedPower;
import de.regelsuche.ast.iteration.FieldInversionReduction.Kind;
import de.regelsuche.ast.iteration.FieldInversionReduction.NonzeroInverse;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldInversionSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(1000, 16, 5000);

    @Test
    void groupsAnyNumberOfInvertedFactorsAcrossTheProduct() {
        var source = source(Kind.PRODUCT, new Factor(0, false, false), new Factor(1, false, true),
            new Factor(2, true, true));
        var result = FieldInversionSearch.search(source, LIMITS, 2, () -> false);
        var checked = result.verified().orElseThrow();
        assertEquals(List.of("group-field-product-inversions"),
            result.witness().stream().map(FieldInversionSearch.CheckedMove::ruleId).toList());
        assertEquals(List.of(0), checked.plan().ordinaryFactors());
        assertEquals(List.of(1, 2), checked.plan().groupedInverses());
        assertEquals(List.of(), checked.plan().rowInverses());
        assertTrue(checked.plan().requiresNonempty());
        assertEquals(BigInteger.valueOf(74), checked.cost(37).originalInversions());
        assertEquals(BigInteger.ONE, checked.cost(37).plannedInversions());
        assertEquals(BigInteger.valueOf(111), checked.cost(37).originalMultiplications());
        assertEquals(BigInteger.valueOf(112), checked.cost(37).plannedMultiplications());
        assertEquals(BigInteger.ZERO, checked.cost(0).plannedInversions());
        assertEquals(result.metrics().verificationWork(), result.witness().getFirst().verificationWork());
    }

    @Test
    void hoistsOnlyTheInvariantPartOfEachElement() {
        var source = source(Kind.PER_ELEMENT, new Factor(0, false, false), new Factor(1, true, true),
            new Factor(2, false, true), new Factor(3, true, true));
        var result = FieldInversionSearch.search(source, LIMITS, 2, () -> false);
        var checked = result.verified().orElseThrow();
        assertEquals(List.of(1, 3), checked.plan().groupedInverses());
        assertEquals(List.of(2), checked.plan().rowInverses());
        assertEquals(BigInteger.valueOf(21), checked.cost(7).originalInversions());
        assertEquals(BigInteger.valueOf(8), checked.cost(7).plannedInversions());
        assertEquals(BigInteger.valueOf(15), checked.cost(7).plannedMultiplications());
        assertEquals(BigInteger.ZERO, checked.cost(0).plannedInversions());
        assertFalse(FieldInversionSearch.search(source(Kind.PER_ELEMENT, new Factor(0, false, true)),
            LIMITS, 2, () -> false).verified().isPresent());
    }

    @Test
    void independentlyRejectsChangedFactorBindingsAndMissingEmptyGuard() {
        var source = source(Kind.PER_ELEMENT, new Factor(0, false, false), new Factor(1, true, true),
            new Factor(2, false, true));
        var forged = new FieldInversionPlan(Kind.PER_ELEMENT, List.of(0), List.of(1, 2), List.of(), true);
        assertEquals("INVALID_INVERSION_SCHEDULE", FieldInversionSearch.verify(source, forged, LIMITS, () -> false).reason());
        var unguarded = new FieldInversionPlan(Kind.PER_ELEMENT, List.of(0), List.of(1), List.of(2), false);
        assertEquals("INVALID_INVERSION_SCHEDULE", FieldInversionSearch.verify(source, unguarded, LIMITS, () -> false).reason());
        var duplicate = new FieldInversionPlan(Kind.PER_ELEMENT, List.of(0, 0), List.of(1), List.of(2), true);
        assertFalse(FieldInversionSearch.verify(source, duplicate, LIMITS, () -> false).verified().isPresent());
        assertThrows(IllegalArgumentException.class, () ->
            source(Kind.PRODUCT, new Factor(0, false, false), new Factor(0, true, true)));
    }

    @Test
    void preservesZeroAndNonzeroValuesInTheBinaryExtensionField() {
        var input = source(Kind.PRODUCT, new Factor(0, false, false), new Factor(1, false, true));
        var plan = FieldInversionSearch.search(input, LIMITS, 2, () -> false).verified().orElseThrow().plan();
        var random = new Random(8128);
        for (int test = 0; test < 300; test++) {
            int direct = 1, numerator = 1, denominator = 1;
            int selected = test % 19;
            for (int row = 0; row < selected; row++) {
                int[] values = {random.nextInt(256), row == 0 && test % 3 == 0 ? 0 : random.nextInt(256)};
                direct = multiply(direct, multiply(values[0], power(values[1], 254)));
                for (int index : plan.ordinaryFactors()) numerator = multiply(numerator, values[index]);
                for (int index : plan.groupedInverses()) denominator = multiply(denominator, values[index]);
            }
            int planned = selected == 0 && plan.requiresNonempty()
                ? 1 : multiply(numerator, power(denominator, 254));
            assertEquals(direct, planned);
        }
    }

    @Test
    void groupsInversesOnUnitsInAPrimeField() {
        var input = new FieldInversionReduction(Kind.PRODUCT, NonzeroInverse.INSTANCE,
            List.of(new Factor(0, false, true)));
        var checked = FieldInversionSearch.search(input, LIMITS, 2, () -> false).verified().orElseThrow();
        var prime = BigInteger.valueOf(101);
        var a = BigInteger.valueOf(7);
        var b = BigInteger.valueOf(19);
        assertEquals(a.modInverse(prime).multiply(b.modInverse(prime)).mod(prime),
            a.multiply(b).mod(prime).modInverse(prime));
        assertEquals(List.of(0), checked.plan().groupedInverses());
        assertEquals(BigInteger.ONE, checked.cost(2).plannedInversions());
    }

    @Test
    void chargesDerivationAndProofAndHonorsInterruption() {
        var input = source(Kind.PRODUCT, new Factor(0, false, false), new Factor(1, false, true));
        var accepted = FieldInversionSearch.search(input, LIMITS, 2, () -> false);
        assertTrue(accepted.verified().isPresent());
        var shortBudget = LIMITS.withMaximumWork(accepted.metrics().totalWork() - 1);
        assertFalse(FieldInversionSearch.search(input, shortBudget, 2, () -> false).verified().isPresent());
        assertEquals("CANCELLED", FieldInversionSearch.search(input, LIMITS, 2, () -> true).reason());
    }

    private static FieldInversionReduction source(Kind kind, Factor... factors) {
        return new FieldInversionReduction(kind, new FixedPower(254), List.of(factors));
    }

    private static int power(int value, int exponent) {
        int result = 1;
        for (int i = 0; i < exponent; i++) result = multiply(result, value);
        return result;
    }

    private static int multiply(int a, int b) {
        int product = 0;
        for (int bit = 0; bit < 8; bit++) {
            if ((b & (1 << bit)) != 0) product ^= a << bit;
        }
        for (int bit = 14; bit >= 8; bit--) {
            if ((product & (1 << bit)) != 0) product ^= 0x11b << (bit - 8);
        }
        return product;
    }
}
