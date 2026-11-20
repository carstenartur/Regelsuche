package de.regelsuche.math.algorithms.noncommutative;

import static de.regelsuche.math.algorithms.noncommutative.NoncommutativeFormula.*;
import static de.regelsuche.math.algorithms.noncommutative.NoncommutativeIdentity.*;
import static de.regelsuche.math.algorithms.noncommutative.NoncommutativeIdentity.Status.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NoncommutativeIdentityTest {
    private static final NoncommutativeFormula X = new Variable(10);
    private static final NoncommutativeFormula Y = new Variable(20);
    private static final List<Integer> XY = List.of(10, 20);
    private static final Limits LIMITS = Limits.defaults();

    private static ExactRational fraction(long n, long d) {
        return new ExactRational(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    private static Completed run(NoncommutativeFormula f, List<Integer> order) {
        return assertInstanceOf(Completed.class, checkZero(f, order, LIMITS, () -> false));
    }

    @Test void commutatorMatchesExplicitDenseMatricesAndBindsOrder() {
        var f = subtract(multiply(X, Y), multiply(Y, X));
        var mutableOrder = new ArrayList<>(XY);
        var result = run(f, mutableOrder);
        mutableOrder.clear();
        assertSame(f, result.formula());
        assertEquals(XY, result.variableOrder());
        assertThrows(UnsupportedOperationException.class, () -> result.firstColumn().clear());
        assertEquals(DISTINCT, result.status());
        assertEquals(9, result.treeSize()); // subtraction lowers to addition and (-1) multiplication
        assertEquals(324, result.firstColumn().size());
        assertEquals(result.firstColumn().get(3).negate(), run(f, List.of(20, 10)).firstColumn().get(3));
        var a = dense(1, 8);
        var b = dense(2, 8);
        for (int row = 0; row < 8; row++) {
            ExactRational expected = ExactRational.ZERO;
            for (int k = 0; k < 8; k++) {
                expected = expected.add(a[row][k].multiply(b[k][0]))
                    .subtract(b[row][k].multiply(a[k][0]));
            }
            assertEquals(expected, result.firstColumn().get(row));
        }
    }

    private static ExactRational[][] dense(int i, int d) {
        var matrix = new ExactRational[d][d];
        for (int r = 0; r < d; r++) {
            for (int q = 0; q < d; q++) {
                matrix[r][q] = q >= r ? ExactRational.ZERO : new ExactRational(
                    BigInteger.valueOf((r - q) % 2 == 1 ? 1 : -1),
                    BigInteger.valueOf(r).multiply(BigInteger.valueOf(i).pow(r - q)));
            }
        }
        return matrix;
    }

    @Test void verifiesDistributivityAssociativityAndRationalScalars() {
        var a = new Scalar(fraction(-2, 3));
        assertEquals(IDENTICAL, run(subtract(multiply(X, add(a, Y)),
            add(multiply(X, a), multiply(X, Y))), XY).status());
        assertEquals(IDENTICAL, run(subtract(multiply(multiply(X, a), Y),
            multiply(X, multiply(a, Y))), XY).status());
        assertEquals(IDENTICAL, run(subtract(multiply(a, X), multiply(X, a)), List.of(10)).status());
    }

    @Test void singletonAndConstantCasesUseExactOneDimensionalValues() {
        assertEquals(List.of(ExactRational.ONE), run(X, List.of(10)).firstColumn());
        var a = new Scalar(fraction(7, 9));
        assertEquals(List.of(fraction(49, 81)), run(multiply(a, a), List.of()).firstColumn());
        assertEquals(IDENTICAL, run(subtract(a, a), List.of()).status());
    }

    @Test void allResourceBoundariesAndNoPartialAnswers() {
        var f = add(X, X); // n=1, s=3, d=18, work=s*d=54
        var enough = new Limits(1, 3, 2, 18, 16, 54);
        assertEquals(DISTINCT, checkZero(f, List.of(10), enough, () -> false).status());
        for (var shortLimit : List.of(new Limits(0,3,2,18,16,54), new Limits(1,2,2,18,16,54),
            new Limits(1,3,1,18,16,54), new Limits(1,3,2,17,16,54),
            new Limits(1,3,2,18,16,53), new Limits(1,3,2,18,3,54))) {
            var stop = assertInstanceOf(Stopped.class, checkZero(f, List.of(10), shortLimit, () -> false));
            assertEquals(BUDGET_EXCEEDED, stop.status());
        }
        assertEquals(CANCELLED, checkZero(X, XY, LIMITS, () -> true).status());
        var polls = new AtomicInteger();
        assertEquals(CANCELLED, checkZero(f, XY, LIMITS, () -> polls.incrementAndGet() > 25).status());
        var large = new Scalar(ExactRational.integer(256));
        var small = new Limits(0, 12, 6, 1, 8, 100);
        assertEquals(BUDGET_EXCEEDED, checkZero(large, List.of(), small, () -> false).status());
        var a = new Scalar(ExactRational.integer(17));
        assertEquals(BUDGET_EXCEEDED, checkZero(multiply(a,a), List.of(), small, () -> false).status());
    }

    @Test void sharedSubexpressionsCountEveryOccurrenceAndDeepTreesStop() {
        var shared = add(X, X);
        assertEquals(7, run(multiply(shared, shared), List.of(10)).treeSize());
        NoncommutativeFormula huge = X;
        for (int i = 0; i < 10_000; i++) huge = multiply(huge, huge);
        assertEquals(BUDGET_EXCEEDED, checkZero(huge, XY, LIMITS, () -> false).status());
        huge = X;
        for (int i = 0; i < 25; i++) huge = add(huge, huge);
        assertEquals(BUDGET_EXCEEDED, checkZero(huge, XY, LIMITS, () -> false).status());
    }

    @Test void rejectsHugeDeclaredSizeBeforeEnumeratingSharedOccurrences() {
        NoncommutativeFormula huge = X;
        for (int i = 0; i < 28; i++) huge = add(huge, huge);
        var generousSize = new Limits(2, Integer.MAX_VALUE, 64, 4096, 8192, Long.MAX_VALUE);
        var polls = new AtomicInteger();
        assertEquals(BUDGET_EXCEEDED, checkZero(huge, XY, generousSize,
            () -> polls.incrementAndGet() > 1000).status());
    }

    @Test void cachedSubtreeStillRespectsDepthAtEveryUse() {
        var shared = add(X, X);
        var f = add(shared, multiply(Y, shared));
        var limits = new Limits(2, 30, 3, 4096, 8192, 200_000);
        assertEquals(BUDGET_EXCEEDED, checkZero(f, XY, limits, () -> false).status());
    }

    @Test void malformedRequestsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Scalar(null));
        assertThrows(NullPointerException.class, () -> multiply(X, null));
        assertEquals(UNSUPPORTED, checkZero(X, List.of(20), LIMITS, () -> false).status());
        assertEquals(UNSUPPORTED, checkZero(X, List.of(10, 10), LIMITS, () -> false).status());
        assertEquals(UNSUPPORTED, checkZero(X, java.util.Arrays.asList(10, null), LIMITS, () -> false).status());
    }

    @Test void deterministicSmallTreesAgreeWithIndependentWordExpansion() {
        var random = new Random(116);
        for (int i = 0; i < 20; i++) {
            var a = generate(random, 1);
            var b = i % 3 == 0 ? a : generate(random, 1);
            var f = subtract(a,b);
            assertEquals(words(f).isEmpty() ? IDENTICAL : DISTINCT, run(f, XY).status());
        }
    }

    @Test void wordExpansionAndDimensionHaveDifferentPracticalLimits() {
        var factor = add(X, Y);
        NoncommutativeFormula product = factor;
        for (int i = 2; i <= 9; i++) {
            product = multiply(product, factor);
            if (i == 3) {
                assertEquals(8, words(product).size());
                var result = run(product, XY);
                assertEquals(DISTINCT, result.status());
                assertEquals(484, result.firstColumn().size());
            }
        }
        assertEquals(512, words(product).size());
        assertEquals(BUDGET_EXCEEDED, checkZero(product, XY, LIMITS, () -> false).status());
    }

    private static NoncommutativeFormula generate(Random random, int depth) {
        if (depth == 0) return switch (random.nextInt(3)) {
            case 0 -> X;
            case 1 -> Y;
            default -> new Scalar(fraction(random.nextInt(5) - 2, 3));
        };
        return new Binary(Op.values()[random.nextInt(3)], generate(random, depth-1), generate(random, depth-1));
    }

    private static Map<List<Integer>, ExactRational> words(NoncommutativeFormula f) {
        return switch (f) {
            case Scalar(var v) -> v.isZero() ? Map.of() : Map.of(List.of(), v);
            case Variable(var id) -> Map.of(List.of(id), ExactRational.ONE);
            case Binary(var op, var left, var right) -> {
                var l = words(left);
                var r = words(right);
                var out = new HashMap<List<Integer>, ExactRational>();
                if (op == Op.MULTIPLY) {
                    l.forEach((u,a) -> r.forEach((v,b) -> {
                        var word = new ArrayList<>(u);
                        word.addAll(v);
                        out.merge(word, a.multiply(b), ExactRational::add);
                    }));
                } else {
                    out.putAll(l);
                    r.forEach((w,v) -> out.merge(w, op == Op.ADD ? v : v.negate(), ExactRational::add));
                }
                out.values().removeIf(ExactRational::isZero);
                yield out;
            }
        };
    }
}
