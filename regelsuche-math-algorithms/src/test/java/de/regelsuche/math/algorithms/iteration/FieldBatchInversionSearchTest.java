package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FieldInversionBatch;
import de.regelsuche.math.algorithms.iteration.FieldBatchInversionPlan.Invert;
import de.regelsuche.math.algorithms.iteration.FieldBatchInversionPlan.Multiply;
import de.regelsuche.math.algorithms.iteration.FieldBatchInversionPlan.Step;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldBatchInversionSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(100_000, 64, 5_000);
    private static final BigInteger BLS_PRIME = new BigInteger(
        "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    @Test
    void eachOutputInvertsItsOwnOperandInTheBlsField() {
        var random = new Random(8128);
        for (int size : new int[] {2, 3, 9, 64}) {
            var source = new FieldInversionBatch(size);
            var result = FieldBatchInversionSearch.search(source, LIMITS, 2, () -> false);
            var checked = result.verified().orElseThrow();
            assertEquals(source, checked.source());
            assertEquals(size, checked.originalInversions());
            assertEquals(1, checked.plannedInversions());
            assertEquals(3 * (size - 1), checked.plannedMultiplications());
            assertEquals(1, result.witness().size());
            assertEquals(result.metrics().verificationWork(), result.witness().get(0).verificationWork());
            for (int sample = 0; sample < 8; sample++) {
                var operands = new ArrayList<BigInteger>();
                operands.add(BigInteger.ONE);
                operands.add(BLS_PRIME.subtract(BigInteger.ONE));
                for (int i = 2; i < size; i++) {
                    operands.add(new BigInteger(BLS_PRIME.bitLength(), random)
                        .mod(BLS_PRIME.subtract(BigInteger.ONE)).add(BigInteger.ONE));
                }
                assertEquals(operands.stream().map(value -> value.modInverse(BLS_PRIME)).toList(),
                    execute(checked.plan(), operands, BLS_PRIME));
            }
        }
    }

    @Test
    void theGroupOfUnitsAlsoAllowsCompositeModuli() {
        var modulus = BigInteger.valueOf(35);
        var operands = List.of(BigInteger.valueOf(3), BigInteger.valueOf(8), BigInteger.valueOf(9));
        var plan = FieldBatchInversionSearch.search(new FieldInversionBatch(operands.size()), LIMITS, 2, () -> false)
            .verified().orElseThrow().plan();
        assertEquals(List.of(BigInteger.valueOf(12), BigInteger.valueOf(22), BigInteger.valueOf(4)),
            execute(plan, operands, modulus));
    }

    @Test
    void rejectsSwappedOutputsEvenWhenTheCostIsIdentical() {
        var source = new FieldInversionBatch(2);
        var plan = FieldBatchInversionSearch.search(source, LIMITS, 2, () -> false).verified().orElseThrow().plan();
        assertTrue(FieldBatchInversionSearch.verify(source, plan, LIMITS, () -> false).verified().isPresent());
        var swapped = new FieldBatchInversionPlan(plan.steps(), List.of(plan.outputs().get(1), plan.outputs().get(0)));
        assertFalse(FieldBatchInversionSearch.verify(source, swapped, LIMITS, () -> false).verified().isPresent());
    }

    @Test
    void rejectsForwardReferencesAndTamperedProducts() {
        var source = new FieldInversionBatch(2);
        var plan = FieldBatchInversionSearch.search(source, LIMITS, 2, () -> false).verified().orElseThrow().plan();
        for (Step malformed : List.of(new Multiply(0, 2), new Multiply(0, 0), new Invert(-1))) {
            var steps = new ArrayList<>(plan.steps());
            steps.set(0, malformed);
            assertFalse(FieldBatchInversionSearch.verify(source, new FieldBatchInversionPlan(steps, plan.outputs()),
                LIMITS, () -> false).verified().isPresent());
        }
    }

    @Test
    void paysForReplayAndDeclinesEmptySingletonAndInterruptedWork() {
        var source = new FieldInversionBatch(4);
        var accepted = FieldBatchInversionSearch.search(source, LIMITS, 2, () -> false);
        assertTrue(accepted.verified().isPresent());
        var shortBudget = LIMITS.withMaximumWork(accepted.metrics().totalWork() - 1);
        assertFalse(FieldBatchInversionSearch.search(source, shortBudget, 2, () -> false).verified().isPresent());
        assertEquals("CANCELLED", FieldBatchInversionSearch.search(source, LIMITS, 2, () -> true).reason());
        for (int size : new int[] {0, 1}) {
            assertFalse(FieldBatchInversionSearch.search(new FieldInversionBatch(size), LIMITS, 2,
                () -> false).verified().isPresent());
        }
    }

    private static List<BigInteger> execute(FieldBatchInversionPlan plan, List<BigInteger> operands, BigInteger modulus) {
        var values = new ArrayList<>(operands);
        for (var step : plan.steps()) {
            if (step instanceof Multiply multiply) {
                values.add(values.get(multiply.left()).multiply(values.get(multiply.right())).mod(modulus));
            } else if (step instanceof Invert inverse) {
                values.add(values.get(inverse.operand()).modInverse(modulus));
            }
        }
        return plan.outputs().stream().map(values::get).toList();
    }
}
