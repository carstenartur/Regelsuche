package de.regelsuche.sdk.optimization.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AffineReductionSdkTest {
    @Test
    void evaluatesCheckedPrefixMaximaThroughThePublicSdk() {
        var domain = List.of(new Form(0, 0, List.of(1)), new Form(0, 1, List.of(-1)));
        var reduction = new AffineReduction(Operator.MAX, List.of(), domain, 1, domain, 1,
            List.of(new Form(0, 0, List.of(0, 1)), new Form(0, 0, List.of(1, -1))), 0);
        var plan = AffineReductionSdk.prepare(reduction, OptimizationBudget.DEFAULT, CancellationToken.NONE).plan();
        var result = AffineReductionSdk.evaluate(plan, 2,
            List.of(BigInteger.valueOf(-7), BigInteger.valueOf(-4), BigInteger.valueOf(-9)),
            OptimizationBudget.DEFAULT, CancellationToken.NONE);
        assertEquals(List.of(BigInteger.valueOf(-7), BigInteger.valueOf(-4), BigInteger.valueOf(-4)), result.values());
    }

    @Test
    void inputSnapshotReadsAreBoundedByBudgetAndCancellation() {
        var inputDomain = List.of(new Form(0, 0, List.of(1)), new Form(0, 1, List.of(-1)));
        var output = List.of(new Form(0, 0, List.of(1)), new Form(0, 0, List.of(-1)));
        var readsAtZero = List.of(new Form(0, 0, List.of(0, 1)), new Form(0, 0, List.of(0, -1)));
        var source = new AffineReduction(Operator.SUM, List.of(), inputDomain, 1, output, 1, readsAtZero, 0);
        var plan = AffineReductionSdk.prepare(source, OptimizationBudget.DEFAULT, CancellationToken.NONE).plan();
        var reads = new AtomicInteger();
        var input = new AbstractList<BigInteger>() {
            @Override public int size() { return 1_024; }

            @Override public BigInteger get(int index) {
                Objects.checkIndex(index, size());
                reads.incrementAndGet();
                return BigInteger.ONE;
            }
        };
        var limited = new OptimizationBudget(24, 128, 32, 5_000);
        assertEquals("WORK_LIMIT", assertThrows(IllegalArgumentException.class,
            () -> AffineReductionSdk.evaluate(plan, input.size() - 1, input, limited, CancellationToken.NONE)).getMessage());
        assertTrue(reads.get() > 0 && reads.get() <= limited.maximumWork(), "read count " + reads.get());

        reads.set(0);
        assertEquals("CANCELLED", assertThrows(IllegalArgumentException.class,
            () -> AffineReductionSdk.evaluate(plan, input.size() - 1, input, OptimizationBudget.DEFAULT,
                () -> reads.get() > 0)).getMessage());
        assertEquals(1, reads.get());
    }
}
