package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.ModularKernel.Certificate;
import de.regelsuche.math.algorithms.linalg.ModularKernel.Limits;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.linalg.ModularKernel.Strength.*;
import static org.junit.jupiter.api.Assertions.*;

class ModularKernelTest {
    static final IterationLimits WORK = new IterationLimits(1_000_000, 100_000, 60_000);
    static final Limits DEFAULT = Limits.defaults(WORK);

    @Test void fractionReconstructionNeedsCompatiblePrimes() {
        var a = new ExactMatrix(List.of(List.of(q(11, 13), q(7, 13))));
        // At 11 the pivot moves to column 1, at 13 a source denominator vanishes.
        assertTrue(ModularKernel.search(a, COMPLETE_KERNEL, limits(5), () -> false).certificate().isEmpty());
        var c = ModularKernel.search(a, COMPLETE_KERNEL, limits(5, 11, 13, 7, 17, 19), () -> false)
            .certificate().orElseThrow();
        assertEquals(q(-7, 11), c.projection().get(0, 1));
        assertEquals(List.of(0), c.pivots());
        assertTrue(ModularKernel.verify(a, COMPLETE_KERNEL, c, DEFAULT, () -> false));
    }

    @Test void hiddenRankGivesOnlySubstitutionUntilAHigherRankPrimeIsTried() {
        var a = matrix(new int[][]{{0,0}, {1,0}, {0,5}});
        var substitution = ModularKernel.search(a, SUBSTITUTION, limits(5), () -> false);
        assertEquals(SUBSTITUTION, substitution.strength());
        var c = substitution.certificate().orElseThrow();
        assertEquals(List.of(1), c.selectedRows());
        assertTrue(ModularKernel.verify(a, SUBSTITUTION, c, DEFAULT, () -> false));
        assertFalse(ModularKernel.verify(a, COMPLETE_KERNEL, c, DEFAULT, () -> false));
        assertTrue(ModularKernel.search(a, COMPLETE_KERNEL, limits(5), () -> false).certificate().isEmpty());
        assertEquals(List.of(), ModularKernel.search(a, COMPLETE_KERNEL, limits(5, 7), () -> false)
            .certificate().orElseThrow().freeColumns());
    }

    @Test void rejectsTamperedWitnessAndBindsOriginalMatrix() {
        var a = matrix(new int[][]{{0,0}, {2,1}});
        var c = ModularKernel.search(a, COMPLETE_KERNEL, DEFAULT, () -> false).certificate().orElseThrow();
        assertFalse(ModularKernel.verify(matrix(new int[][]{{0,0},{4,2}}), COMPLETE_KERNEL, c, DEFAULT, () -> false));
        var bad = List.of(new Certificate(a, List.of(0), c.pivots(), c.freeColumns(), c.projection(), c.witnessPrime()),
            new Certificate(a, c.selectedRows(), List.of(1), List.of(0), c.projection(), c.witnessPrime()),
            new Certificate(a, c.selectedRows(), c.pivots(), List.of(0), c.projection(), c.witnessPrime()),
            new Certificate(a, c.selectedRows(), c.pivots(), c.freeColumns(), matrix(new int[][]{{0,1},{0,1}}), 5),
            new Certificate(a, c.selectedRows(), c.pivots(), c.freeColumns(), matrix(new int[][]{{0,0},{0,0}}), 5),
            new Certificate(a, c.selectedRows(), c.pivots(), c.freeColumns(), c.projection(), 2),
            new Certificate(a, c.selectedRows(), c.pivots(), c.freeColumns(), c.projection(), 9));
        for (var tampered : bad) assertFalse(ModularKernel.verify(a, SUBSTITUTION, tampered, DEFAULT, () -> false));
    }

    @Test void resourceLimitsAndCancellationClearEvidence() {
        var a = matrix(new int[][]{{1,2},{3,4}});
        var c = ModularKernel.search(a, COMPLETE_KERNEL, DEFAULT, () -> false).certificate().orElseThrow();
        var tiny = Limits.defaults(WORK.withMaximumWork(2));
        assertEquals(ModularKernel.Status.LIMIT, ModularKernel.search(a, COMPLETE_KERNEL, tiny, () -> false).status());
        assertFalse(ModularKernel.verify(a, COMPLETE_KERNEL, c, tiny, () -> false));
        var calls = new AtomicInteger();
        assertFalse(ModularKernel.verify(a, COMPLETE_KERNEL, c, DEFAULT, () -> calls.incrementAndGet() > 10));
        assertEquals(ModularKernel.Status.CANCELLED, ModularKernel.search(a, COMPLETE_KERNEL, DEFAULT, () -> true).status());
        assertEquals(ModularKernel.Status.LIMIT, ModularKernel.search(a, SUBSTITUTION,
            new Limits(WORK, 1, List.of(5)), () -> false).status());
        assertEquals(ModularKernel.Status.LIMIT, ModularKernel.search(a, SUBSTITUTION,
            Limits.defaults(new IterationLimits(1000, 3, 60000)), () -> false).status());
    }

    @Test void rejectsAProjectionWithANonzeroPivotInputEvenWhenEveryColumnIsInTheKernel() {
        var a = matrix(new int[][]{{2,1}});
        var p = new ExactMatrix(List.of(List.of(q(-1,1), q(-1,2)), List.of(q(2,1), q(1,1))));
        var c = new Certificate(a, List.of(0), List.of(0), List.of(1), p, 5);
        assertFalse(ModularKernel.verify(a, COMPLETE_KERNEL, c, DEFAULT, () -> false));
    }

    @Test void everyWorkCutoffInterruptsAMultiPrimeDiscoveryIncludingItsFinalCheck() {
        var a = matrix(new int[][]{{11,7},{22,14}});
        var limits = limits(5, 11, 7, 17, 19);
        var complete = ModularKernel.search(a, COMPLETE_KERNEL, limits, () -> false);
        assertTrue(complete.certificate().isPresent());
        for (int cap = 1; cap < complete.work(); cap++) {
            var bounded = new Limits(WORK.withMaximumWork(cap), limits.maximumBits(), limits.primes());
            assertEquals(ModularKernel.Status.LIMIT, ModularKernel.search(a, COMPLETE_KERNEL, bounded, () -> false).status());
            var calls = new AtomicInteger(cap);
            assertEquals(ModularKernel.Status.CANCELLED,
                ModularKernel.search(a, COMPLETE_KERNEL, limits, () -> calls.getAndDecrement() <= 0).status());
        }
    }

    @Test void seededRationalMatricesAgreeWithIndependentExactRref() {
        var random = new Random(266);
        var panel = new ArrayList<ExactMatrix>();
        panel.add(matrix(new int[][]{{0,0,0}, {0,0,0}}));
        panel.add(matrix(new int[][]{{0,2,1}, {0,4,2}}));
        for (int t = 0; t < 64; t++) {
            int m = 1 + random.nextInt(5), n = 1 + random.nextInt(5);
            var rows = new ArrayList<List<Rational>>();
            for (int i = 0; i < m; i++) rows.add(IntStream.range(0,n)
                .mapToObj(j -> q(random.nextInt(9)-4, 1+random.nextInt(4))).toList());
            panel.add(new ExactMatrix(rows));
        }
        long exactWork = 0, modularWork = 0;
        for (var a : panel) {
            var variables = IntStream.range(0,a.columns()).mapToObj(j -> "x" + j).toList();
            var origins = IntStream.range(0,a.rowCount()).mapToObj(i -> new RowOrigin(i,"r" + i)).toList();
            var oracle = new ExactRrefSolver().solveHomogeneous(a, variables, origins, new Budget(1_000_000));
            var expected = oracle.reduction().orElseThrow();
            var found = ModularKernel.search(a, COMPLETE_KERNEL, DEFAULT, () -> false);
            var c = found.certificate().orElseThrow();
            assertEquals(expected.freeVariableColumns(), c.freeColumns());
            for (int i = 0; i < c.freeColumns().size(); i++) {
                int col = c.freeColumns().get(i);
                assertEquals(expected.nullspaceBasis().get(i).values(), c.projection().rows().stream().map(r -> r.get(col)).toList());
            }
            exactWork += oracle.work().consumedWorkUnits();
            modularWork += found.work();
        }
        System.out.printf("Kernel comparison: %d cases, exact work %d, modular work %d%n", panel.size(), exactWork, modularWork);
    }

    static Limits limits(Integer... primes) { return new Limits(WORK, 4096, List.of(primes)); }
    static Rational q(long n, long d) { return new Rational(BigInteger.valueOf(n), BigInteger.valueOf(d)); }
    static ExactMatrix matrix(int[][] a) {
        return new ExactMatrix(Arrays.stream(a).map(row -> Arrays.stream(row).mapToObj(Rational::of).toList()).toList());
    }
}
