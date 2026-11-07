package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PairReductionTest {
    private static final UUID NAMESPACE = new UUID(1, 2);
    private static final PairReduction.Column X = column(1);
    private static final PairReduction.Column W = column(3);
    private static final OptimizationBudget BUDGET = new OptimizationBudget(8_000_000, 128, 1, 30_000);
    private static final CancellationToken NEVER = CancellationToken.NONE;
    private static final PairReductionOptimizer OPTIMIZER = new PairReductionOptimizer();
    private static final PairReductionVerifier VERIFIER = new PairReductionVerifier();

    @Test
    void discoversTwoRunningSumsAndReadsEachOfOneHundredThousandRowsOnce() {
        var checked = checked(source("(xi-xj)^2", X));
        assertEquals(1, checked.plan().divisor());
        assertEquals(List.of(new PairReductionPlan.Moment(List.of(0)),
            new PairReductionPlan.Moment(List.of(0, 0))), checked.plan().accumulators());
        var opened = new AtomicInteger();
        var delivered = new AtomicInteger();
        Iterable<List<BigInteger>> rows = () -> {
            assertEquals(1, opened.incrementAndGet());
            return IntStream.range(0, 100_000).mapToObj(index -> {
                delivered.incrementAndGet();
                return row(index % 2);
            }).iterator();
        };
        var result = checked.evaluate(rows, BUDGET, NEVER);
        assertEquals(100_000, delivered.get());
        assertEquals(100_000L, result.rows());
        assertEquals(new BigInteger("4999950000"), PairReduction.originalPairs(result.rows()));
        assertEquals(new BigInteger("2500000000"), result.value());
    }

    @Test
    void derivesTheWeightedKernelFromAnAdditionalInputColumn() {
        var checked = checked(source("wi*wj*(xj-xi)^2", X, W));
        assertEquals(3, checked.plan().accumulators().size());
        assertEquals(BigInteger.valueOf(-99),
            checked.evaluate(List.of(row(2, 3), row(-1, -2), row(5, 1)), BUDGET, NEVER).value());
    }

    @Test
    void removesTheDiagonalAndKeepsExactDivisionWhenNeeded() {
        var checked = checked(source("xi*xj", X));
        assertEquals(2, checked.plan().divisor());
        assertEquals(BigInteger.valueOf(3),
            checked.evaluate(List.of(row(2), row(-1), row(5)), BUDGET, NEVER).value());
        assertEquals(BigInteger.ZERO, checked.evaluate(List.of(), BUDGET, NEVER).value());
        assertEquals(BigInteger.ZERO, checked.evaluate(List.of(row(-3)), BUDGET, NEVER).value());
    }

    @Test
    void rejectsAPlanThatAgreesForTwoRowsButInventsASingleRowContribution() {
        var source = source("(xi-xj)^2", X);
        var correct = checked(source).plan();
        var count = new PairReductionPlan.Moment(List.of());
        var terms = new ArrayList<>(correct.terms());
        terms.add(new PairReductionPlan.Term(BigInteger.ONE, List.of(count, count)));
        terms.add(new PairReductionPlan.Term(BigInteger.TWO.negate(), List.of(count)));
        assertEquals("DIAGONAL_MISMATCH",
            VERIFIER.verify(source, new PairReductionPlan(terms, 1), BUDGET, NEVER).reason());
    }

    @Test
    void rejectsAPlanForADifferentPairPolynomialEvenWhenDiagonalsMatch() {
        var original = source("(xi-xj)^2", X);
        assertEquals("PAIR_IDENTITY_MISMATCH",
            VERIFIER.verify(source("2*(xi-xj)^2", X), checked(original).plan(), BUDGET, NEVER).reason());
    }

    @Test
    void failsClosedForAsymmetryUnsupportedTermsBadRowsAndBudgetExhaustion() {
        var source = source("xi*xj", X);
        assertEquals("ASYMMETRIC_SUMMAND", OPTIMIZER.search(source("xi-xj", X), BUDGET, NEVER).reason());
        assertEquals("UNSUPPORTED_OPERATOR", OPTIMIZER.search(source("(xi+xj)/2", X), BUDGET, NEVER).reason());
        assertEquals("CANCELLED", OPTIMIZER.search(source, BUDGET, () -> true).reason());
        assertEquals("WORK_LIMIT",
            OPTIMIZER.search(source, new OptimizationBudget(1, 128, 1, 30_000), NEVER).reason());
        var checked = checked(source);
        assertThrows(IllegalArgumentException.class, () -> checked.evaluate(List.of(row(1, 2)), BUDGET, NEVER));
        assertThrows(IllegalArgumentException.class, () -> checked.evaluate(List.of(row(1)), BUDGET, () -> true));
    }

    @Test
    void reportsCancellationAtEndOfTheInputEvenForAZeroPlan() {
        var checked = checked(source("0", X));
        var cancelled = new AtomicBoolean();
        Iterable<List<BigInteger>> rows = () -> new Iterator<>() {
            @Override public boolean hasNext() {
                cancelled.set(true);
                return false;
            }
            @Override public List<BigInteger> next() { throw new AssertionError(); }
        };
        var failure = assertThrows(IllegalArgumentException.class,
            () -> checked.evaluate(rows, BUDGET, cancelled::get));
        assertEquals("CANCELLED", failure.getMessage());
    }

    private static PairReductionVerifier.Verified checked(PairReduction source) {
        var search = OPTIMIZER.search(source, BUDGET, NEVER);
        assertTrue(search.plan().isPresent(), search.reason());
        var check = VERIFIER.verify(source, search.plan().orElseThrow(), BUDGET, NEVER);
        assertTrue(check.verified().isPresent(), check.reason());
        return check.verified().orElseThrow();
    }

    private static PairReduction source(String expression, PairReduction.Column... columns) {
        String scoped = expression.replace("xi", X.left().identifier()).replace("xj", X.right().identifier())
            .replace("wi", W.left().identifier()).replace("wj", W.right().identifier());
        return new PairReduction(List.of(columns), new ExpressionParser().parseExactTerm(scoped).expression());
    }

    private static PairReduction.Column column(long ordinal) {
        return new PairReduction.Column(new SymbolId(NAMESPACE, ordinal), new SymbolId(NAMESPACE, ordinal + 1));
    }

    private static List<BigInteger> row(long... values) {
        return java.util.Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }
}
