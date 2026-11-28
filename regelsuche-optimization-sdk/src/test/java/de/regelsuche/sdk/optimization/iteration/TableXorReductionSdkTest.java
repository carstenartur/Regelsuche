package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.math.algorithms.iteration.OrderedInputPrefix.Schedule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.regelsuche.ast.iteration.TableXorReduction.Axis.INPUT;
import static de.regelsuche.ast.iteration.TableXorReduction.Axis.OUTPUT;
import static de.regelsuche.ast.iteration.TableXorReduction.Axis.POSITION;
import static de.regelsuche.ast.iteration.TableXorReduction.Storage.FIRST_STORE_THEN_XOR;
import static de.regelsuche.ast.iteration.TableXorReduction.Storage.ZERO_XOR_REDUCE_THEN_STORE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableXorReductionSdkTest {
    @Test
    void qualifiesTwoPolynomialBasesAndChecksEveryEntry() {
        for (int modulus : List.of(0x11b, 0x11d)) {
            var table = productTable(modulus);
            var evidence = TableXorReductionSdk.verifyBinaryField(table).orElseThrow();
            assertEquals(modulus, evidence.modulus());
            assertEquals(65536, evidence.productsChecked());
            table[255][255] ^= 1;
            assertTrue(TableXorReductionSdk.verifyBinaryField(table).isEmpty());
            table[255][255] ^= 1;
            table[128][2] ^= 6;
            assertTrue(TableXorReductionSdk.verifyBinaryField(table).isEmpty());
        }
    }

    @Test
    void rejectsReduciblePolynomialsAndIncompleteTables() {
        // (x^4 + x + 1)^2 has a factor of degree four, but no linear factor.
        assertTrue(TableXorReductionSdk.verifyBinaryField(productTable(0x105)).isEmpty());
        assertTrue(TableXorReductionSdk.verifyBinaryField(null).isEmpty());
        assertTrue(TableXorReductionSdk.verifyBinaryField(new byte[255][256]).isEmpty());
        var table = new byte[256][256];
        table[255] = null;
        assertTrue(TableXorReductionSdk.verifyBinaryField(table).isEmpty());
        table[255] = new byte[255];
        assertTrue(TableXorReductionSdk.verifyBinaryField(table).isEmpty());
    }

    @Test
    void evaluatesUnsignedIndicesAndOffsetOnAnAsymmetricNonFieldTable() {
        var table = new byte[256][256];
        table[255][128] = (byte) 0x81;
        table[255][255] = 0x34;
        table[0][128] = 0x08;
        table[0][255] = (byte) 0xf0;
        byte[][] coefficients = {{-1, 0}, {0, -1}};
        byte[][] inputs = {{42, -128, -1, 10}, {99, -1, -128, 30}};
        assertTrue(TableXorReductionSdk.verifyBinaryField(table).isEmpty());
        var orders = List.of(List.of(OUTPUT, POSITION, INPUT), List.of(POSITION, OUTPUT, INPUT),
                List.of(OUTPUT, INPUT, POSITION), List.of(POSITION, INPUT, OUTPUT),
                List.of(INPUT, OUTPUT, POSITION), List.of(INPUT, POSITION, OUTPUT));
        for (var order : orders) {
            var first = TableXorReductionSdk.describe(order, FIRST_STORE_THEN_XOR);
            assertEquals(order, first.order());
            assertEquals(FIRST_STORE_THEN_XOR, first.storage());
            var actual = TableXorReductionSdk.evaluate(first, table, coefficients, inputs, 2, 2, 1, 2);
            assertArrayEquals(new byte[]{0x71, 0x3c}, actual[0]);
            assertArrayEquals(new byte[]{0x3c, 0x71}, actual[1]);
            if (order.getLast() == INPUT) {
                var scalar = TableXorReductionSdk.describe(order, ZERO_XOR_REDUCE_THEN_STORE);
                var scalarResult = TableXorReductionSdk.evaluate(scalar, table, coefficients, inputs, 2, 2, 1, 2);
                assertArrayEquals(actual[0], scalarResult[0]);
                assertArrayEquals(actual[1], scalarResult[1]);
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> TableXorReductionSdk.describe(order, ZERO_XOR_REDUCE_THEN_STORE));
            }
        }
    }

    @Test
    void validatesDomainsAndPreservesSourceOrder() {
        var order = new ArrayList<>(List.of(POSITION, OUTPUT, INPUT));
        var scalar = TableXorReductionSdk.describe(order, ZERO_XOR_REDUCE_THEN_STORE);
        order.set(0, INPUT);
        assertEquals(List.of(POSITION, OUTPUT, INPUT), scalar.order());
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.describe(order, FIRST_STORE_THEN_XOR));
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.describe(List.of(INPUT), FIRST_STORE_THEN_XOR));

        var table = new byte[256][256];
        byte[][] coefficients = {{0}};
        byte[][] inputs = {{0}};
        assertArrayEquals(new byte[]{0, 0}, TableXorReductionSdk.evaluate(scalar, table, coefficients, inputs, 0, 1, 0, 2)[0]);
        assertEquals(0, TableXorReductionSdk.evaluate(scalar, table, coefficients, inputs, 1, 1, 1, 0)[0].length);
        var first = TableXorReductionSdk.describe(List.of(INPUT, OUTPUT, POSITION), FIRST_STORE_THEN_XOR);
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.evaluate(first, table, coefficients, inputs, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.evaluate(scalar, table, coefficients, inputs, 1, 1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.evaluate(scalar, table, coefficients, inputs, 1, 1, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.evaluate(scalar, table, new byte[1][0], inputs, 1, 1, 0, 1));
        table[255] = new byte[128];
        assertThrows(IllegalArgumentException.class, () -> TableXorReductionSdk.evaluate(scalar, table, coefficients, inputs, 1, 1, 0, 1));
    }

    @Test
    void prefixAndConditionalTailReplayTheOriginalIndices() {
        for (var order : List.of(List.of(OUTPUT, POSITION, INPUT), List.of(POSITION, OUTPUT, INPUT))) {
            var source = TableXorReductionSdk.describe(order, ZERO_XOR_REDUCE_THEN_STORE);
            for (int width : List.of(1, 2, 4, 16)) {
                var plan = TableXorReductionSdk.orderedPrefixPlan(source, width).orElseThrow();
                assertTrue(TableXorReductionSdk.verifyOrderedPrefix(source, plan));
                for (int count : List.of(Integer.MIN_VALUE, -1, 0, 1, width - 1, width, width + 1, 31)) {
                    var expected = new ArrayList<Integer>();
                    for (int index = 0; index < count; index++) expected.add(index);
                    var actual = new ArrayList<Integer>();
                    if (count >= plan.prefixThreshold()) actual.addAll(plan.prefixIndices());
                    int tail = count >= plan.tailThreshold() ? plan.fastTailStart() : plan.fallbackTailStart();
                    for (int index = tail; index < count; index++) actual.add(index);
                    assertEquals(expected, actual);
                }
            }
        }
    }

    @Test
    void rejectsSchedulesThatSkipDuplicateOrReorderReads() {
        var source = TableXorReductionSdk.describe(List.of(POSITION, OUTPUT, INPUT), ZERO_XOR_REDUCE_THEN_STORE);
        var canonical = List.of(0, 1, 2, 3);
        var invalid = List.of(new Schedule(3, canonical, 4, 4, 0),
                new Schedule(4, canonical, 5, 4, 0),
                new Schedule(4, canonical, 4, 3, 0),
                new Schedule(4, canonical, 4, 4, 1),
                new Schedule(4, List.of(0, 2, 1, 3), 4, 4, 0),
                new Schedule(4, List.of(0, 1, 1, 3), 4, 4, 0),
                new Schedule(4, List.of(0, 1, 3), 4, 4, 0));
        for (var schedule : invalid) assertFalse(TableXorReductionSdk.verifyOrderedPrefix(source, schedule));
        assertTrue(TableXorReductionSdk.orderedPrefixPlan(source, 0).isEmpty());
        assertTrue(TableXorReductionSdk.orderedPrefixPlan(source, Integer.MAX_VALUE).isEmpty());
        var writes = TableXorReductionSdk.describe(List.of(OUTPUT, INPUT, POSITION), FIRST_STORE_THEN_XOR);
        assertTrue(TableXorReductionSdk.orderedPrefixPlan(writes, 4).isEmpty());
        assertFalse(TableXorReductionSdk.verifyOrderedPrefix(writes, new Schedule(4, canonical, 4, 4, 0)));
    }

    // Independent recurrence: multiply one operand by x and reduce after each shift.
    private static byte[][] productTable(int modulus) {
        var table = new byte[256][256];
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                int sum = 0;
                int term = a;
                for (int bits = b; bits != 0; bits >>>= 1) {
                    if ((bits & 1) != 0) sum ^= term;
                    term <<= 1;
                    if (term >= 256) term ^= modulus;
                }
                table[a][b] = (byte) sum;
            }
        }
        return table;
    }
}
