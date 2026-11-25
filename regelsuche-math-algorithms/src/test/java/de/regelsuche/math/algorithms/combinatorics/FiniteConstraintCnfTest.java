package de.regelsuche.math.algorithms.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class FiniteConstraintCnfTest {
    private static final IterationLimits LIMITS = new IterationLimits(10_000_000, 100_000, 10_000);

    @Test void countsOriginalDeclaredSpaceAndWideDisjunction(TestReporter report) {
        var wide = IntStream.rangeClosed(1, 62).boxed().toList();
        var count = count(62, List.of(wide), Map.of());
        assertEquals(BigInteger.ONE.shiftLeft(62).subtract(BigInteger.ONE), count.count().orElseThrow());
        var proof = count.certificate().orElseThrow();
        report.publishEntry("wide OR", count.work() + " work; " + proof.elimination().source().variableCount()
            + " lowered variables; " + proof.elimination().source().factors().size() + " factors");
        assertTrue(proof.elimination().source().factors().stream().allMatch(f -> f.values().size() <= 8));
        assertTrue(FiniteConstraintCnf.verify(proof.source(), proof, LIMITS, () -> false).valid());
        assertEquals(BigInteger.valueOf(32), count(5, List.of(), Map.of()).count().orElseThrow());
        assertEquals(BigInteger.ZERO, count(5, List.of(List.of()), Map.of()).count().orElseThrow());
        assertEquals(BigInteger.ONE, count(0, List.of(), Map.of()).count().orElseThrow());
    }

    @Test void signedRepeatedLiteralsAndFixedVariablesAgreeWithEnumeration() {
        var random = new Random(125);
        for (int trial = 0; trial < 100; trial++) {
            int n = 1 + random.nextInt(5);
            var clauses = new ArrayList<List<Integer>>();
            for (int c = random.nextInt(6); c > 0; c--) {
                var clause = new ArrayList<Integer>();
                for (int i = random.nextInt(9); i > 0; i--) {
                    int variable = 1 + random.nextInt(n);
                    clause.add(random.nextBoolean() ? variable : -variable);
                }
                clauses.add(clause);
            }
            Map<Integer, Boolean> fixed = trial % 2 == 0 ? Map.of() : Map.of(random.nextInt(n), random.nextBoolean());
            long expected = 0;
            for (int mask = 0; mask < (1 << n); mask++) {
                final int bits = mask;
                boolean allowed = fixed.entrySet().stream().allMatch(e -> e.getValue() == ((bits & 1 << e.getKey()) != 0));
                if (allowed && clauses.stream().allMatch(clause -> clause.stream().anyMatch(literal ->
                        ((bits & 1 << (Math.abs(literal) - 1)) != 0) == (literal > 0)))) expected++;
            }
            assertEquals(BigInteger.valueOf(expected), count(n, clauses, fixed).count().orElseThrow(), "trial " + trial);
        }
    }

    @Test void eachSatisfyingOriginalAssignmentHasOneExtensionEvenWithOppositeOrRepeatedLiterals() {
        var source = new FiniteConstraintCnf.Source(3,
            List.of(List.of(-2, 3, 3, 1, -3), List.of(1, 1, -2, 1)), Map.of());
        var problem = FiniteConstraintCnf.lower(source, LIMITS, () -> false).problem().orElseThrow();
        for (int original = 0; original < 8; original++) {
            int extensions = 0;
            for (int bits = original; bits < (1 << problem.variableCount()); bits += 8) {
                boolean allowed = true;
                for (var factor : problem.factors()) {
                    int row = 0;
                    for (int j = 0; j < factor.variables().size(); j++)
                        row |= ((bits >>> factor.variables().get(j)) & 1) << j;
                    allowed &= factor.values().get(row).signum() == 1;
                }
                if (allowed) extensions++;
            }
            assertEquals((original & 1) != 0 || (original & 2) == 0 ? 1 : 0, extensions);
        }
    }

    @Test void checksAllAuxiliaryGateRowsEvenWhenThereIsAValidWitness() {
        var source = new FiniteConstraintCnf.Source(4, List.of(List.of(1, 2, 3, 4)), Map.of());
        var lowered = FiniteConstraintCnf.lower(source, LIMITS, () -> false).problem().orElseThrow();
        var factors = new ArrayList<>(lowered.factors());
        var gate = factors.getFirst();
        var rows = new ArrayList<>(gate.values());
        rows.set(4, BigInteger.ONE); // falsely permit output true when both inputs are false
        factors.set(0, new FiniteConstraintCounter.Factor(gate.variables(), rows));
        var altered = new FiniteConstraintCounter.Problem(lowered.variableCount(), factors, lowered.fixed());
        var elimination = FiniteConstraintCounter.count(altered, LIMITS, () -> false).certificate().orElseThrow();
        var forged = new FiniteConstraintCnf.Certificate(source, elimination);
        assertEquals(FiniteConstraintVerifier.Status.INVALID, FiniteConstraintCnf.verify(source, forged,
            LIMITS, () -> false).status());
    }

    @Test void rejectsInvalidIdsAndDefensivelyBindsMutableInput() {
        for (int literal : new int[]{0, 4, -4, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () ->
                new FiniteConstraintCnf.Source(3, List.of(List.of(literal)), Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new FiniteConstraintCnf.Source(3, List.of(), Map.of(3, true)));
        var literals = new ArrayList<>(List.of(1, 2));
        var source = new FiniteConstraintCnf.Source(2, List.of(literals), Map.of());
        literals.clear();
        assertEquals(BigInteger.valueOf(3), FiniteConstraintCnf.count(source, LIMITS, () -> false).count().orElseThrow());
    }

    @Test void rejectsWrongSourceAndIndependentlyValidButWrongFactorTranslation() {
        var proof = count(4, List.of(List.of(1, 2, 3, 4)), Map.of()).certificate().orElseThrow();
        var changed = new FiniteConstraintCnf.Source(4, List.of(List.of(-1, 2, 3, 4)), Map.of());
        assertFalse(FiniteConstraintCnf.verify(changed, proof, LIMITS, () -> false).valid());
        var rebound = new FiniteConstraintCnf.Certificate(changed, proof.elimination());
        assertEquals(FiniteConstraintVerifier.Status.INVALID,
            FiniteConstraintCnf.verify(changed, rebound, LIMITS, () -> false).status());
    }

    @Test void resourceStopsCarryNoAnswerAndIncludeWork() {
        var source = new FiniteConstraintCnf.Source(5, List.of(List.of(1, 2, -3, -4, 5)), Map.of());
        var exact = FiniteConstraintCnf.count(source, LIMITS, () -> false);
        for (IterationLimits limits : List.of(LIMITS.withMaximumWork(exact.work() - 1),
                new IterationLimits(100_000, 6, 5_000))) {
            var stopped = FiniteConstraintCnf.count(source, limits, () -> false);
            assertEquals(FiniteConstraintCounter.Status.UNAVAILABLE, stopped.status());
            assertTrue(stopped.certificate().isEmpty());
            assertTrue(stopped.count().isEmpty());
            assertTrue(stopped.work() <= limits.maximumWork());
        }
        assertEquals("CANCELLED", FiniteConstraintCnf.count(source, LIMITS, () -> true).reason());
        assertEquals("CANCELLED", FiniteConstraintCnf.verify(source, exact.certificate().orElseThrow(),
            LIMITS, () -> true).reason());
    }

    @Test void cancellationOnFinalReturnIncludesAllNestedWork() {
        var source = new FiniteConstraintCnf.Source(5, List.of(List.of(1, 2, -3, -4, 5)), Map.of());
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var completed = FiniteConstraintCnf.count(source, LIMITS, () -> { polls.incrementAndGet(); return false; });
        int finalPoll = polls.get();
        polls.set(0);
        var stopped = FiniteConstraintCnf.count(source, LIMITS, () -> polls.incrementAndGet() >= finalPoll);
        assertEquals("CANCELLED", stopped.reason());
        assertEquals(completed.work(), stopped.work());
        assertTrue(stopped.count().isEmpty());
    }

    @Test void indexedAndHistoricalOrdersProduceIdenticalTracesWithLessWorkOnSparseGraphs(TestReporter report) {
        for (int stride : List.of(1, 2)) {
            var factors = new ArrayList<FiniteConstraintCounter.Factor>();
            for (int v = 0; v < 99; v += stride) {
                factors.add(new FiniteConstraintCounter.Factor(List.of(v, v + 1),
                    List.of(BigInteger.TWO, BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE)));
            }
            var problem = new FiniteConstraintCounter.Problem(100, factors, Map.of(0, true));
            for (var strategy : FiniteConstraintPlanner.Strategy.values()) {
                var baseline = FiniteConstraintCounter.count(problem, strategy, LIMITS, () -> false);
                var indexed = FiniteConstraintCounter.countIndexed(problem, strategy, LIMITS, () -> false);
                assertEquals(baseline.certificate().orElseThrow(), indexed.certificate().orElseThrow());
                assertTrue(indexed.work() < baseline.work());
                report.publishEntry((stride == 1 ? "chain " : "pairs ") + strategy,
                    baseline.work() + " scan, " + indexed.work() + " indexed");
            }
        }
    }

    private static FiniteConstraintCnf.Result count(int n, List<List<Integer>> clauses, Map<Integer, Boolean> fixed) {
        return FiniteConstraintCnf.count(new FiniteConstraintCnf.Source(n, clauses, fixed), LIMITS, () -> false);
    }
}
