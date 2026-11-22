package de.regelsuche.math.algorithms.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FiniteConstraintCounterTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 100_000, 10_000);

    @Test void localExclusionsCountAndConditionExactly() {
        var factors = List.of(factor(List.of(0, 1), 1, 1, 1, 0), factor(List.of(1, 2), 1, 1, 1, 0));
        var problem = new FiniteConstraintCounter.Problem(3, factors, Map.of());
        assertCount(problem, 5);
        assertCount(new FiniteConstraintCounter.Problem(3, factors, Map.of(1, true)), 1);
        assertCount(new FiniteConstraintCounter.Problem(3, factors, Map.of(1, false)), 4);
    }

    @Test void constantsFreeVariablesAndWeightsHaveTheirDeclaredSemantics() {
        assertCount(new FiniteConstraintCounter.Problem(0, List.of(), Map.of()), 1);
        assertCount(new FiniteConstraintCounter.Problem(5, List.of(), Map.of(2, true)), 16);
        assertCount(new FiniteConstraintCounter.Problem(2,
            List.of(factor(List.of(0), 2, 3), factor(List.of(1), 5, 7)), Map.of()), 60);
        assertCount(new FiniteConstraintCounter.Problem(2,
            List.of(factor(List.of(), 0)), Map.of()), 0);
        assertCount(new FiniteConstraintCounter.Problem(1,
            List.of(factor(List.of(0), 1, 0), factor(List.of(0), 0, 1)), Map.of()), 0);
    }

    @Test void arbitrarySmallFactorsAgreeWithIndependentCompleteEnumeration() {
        var random = new Random(378);
        for (int trial = 0; trial < 70; trial++) {
            int n = 1 + random.nextInt(5);
            var factors = new ArrayList<FiniteConstraintCounter.Factor>();
            for (int k = 0; k < 1 + random.nextInt(5); k++) {
                var scope = new TreeSet<Integer>();
                for (int j = 0, length = random.nextInt(n + 1); j < length; j++) scope.add(random.nextInt(n));
                var values = new ArrayList<BigInteger>();
                for (int j = 0; j < (1 << scope.size()); j++) values.add(BigInteger.valueOf(random.nextInt(4)));
                factors.add(new FiniteConstraintCounter.Factor(List.copyOf(scope), values));
            }
            var fixed = trial % 3 == 0 ? Map.of(random.nextInt(n), random.nextBoolean()) : Map.<Integer, Boolean>of();
            var problem = new FiniteConstraintCounter.Problem(n, factors, fixed);
            var result = FiniteConstraintCounter.count(problem, LIMITS, () -> false);
            assertEquals(FiniteConstraintCounter.Status.EXACT, result.status(), result.reason());
            assertEquals(enumerate(problem), result.count().orElseThrow());
            assertTrue(FiniteConstraintVerifier.verify(problem, result.certificate().orElseThrow(), LIMITS, () -> false).valid());
        }
    }

    @Test void verifierRejectsChangedSourceTraceTotalAndWitness() {
        var problem = new FiniteConstraintCounter.Problem(2, List.of(factor(List.of(0, 1), 1, 0, 0, 0)), Map.of());
        var certificate = FiniteConstraintCounter.count(problem, LIMITS, () -> false).certificate().orElseThrow();
        var wrongTotal = new FiniteConstraintCounter.Certificate(problem, certificate.steps(), BigInteger.TWO, certificate.witness());
        assertFalse(FiniteConstraintVerifier.verify(problem, wrongTotal, LIMITS, () -> false).valid());
        var missingStep = new FiniteConstraintCounter.Certificate(problem, certificate.steps().subList(1, certificate.steps().size()), certificate.total(), certificate.witness());
        assertFalse(FiniteConstraintVerifier.verify(problem, missingStep, LIMITS, () -> false).valid());
        var wrongWitness = new FiniteConstraintCounter.Certificate(problem, certificate.steps(), certificate.total(), Optional.of(Map.of(0, true, 1, true)));
        assertFalse(FiniteConstraintVerifier.verify(problem, wrongWitness, LIMITS, () -> false).valid());
        var changed = new FiniteConstraintCounter.Problem(2, List.of(), Map.of());
        assertFalse(FiniteConstraintVerifier.verify(changed, certificate, LIMITS, () -> false).valid());
    }

    @Test void exhaustionAndCancellationNeverLookLikeZeroModels() {
        var problem = new FiniteConstraintCounter.Problem(3, List.of(), Map.of());
        for (int budget = 1; budget <= 30; budget++) {
            var result = FiniteConstraintCounter.count(problem, new IterationLimits(budget, 100, 10_000), () -> false);
            assertEquals(FiniteConstraintCounter.Status.UNAVAILABLE, result.status());
            assertTrue(result.count().isEmpty());
            assertTrue(result.certificate().isEmpty());
        }
        var result = FiniteConstraintCounter.count(problem, LIMITS, () -> true);
        assertEquals("CANCELLED", result.reason());
        assertTrue(result.count().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> factor(List.of(0), 1, -1));
    }

    @Test void forgedLocalTableAndResourceBoundariesAreRejected() {
        var problem = new FiniteConstraintCounter.Problem(2, List.of(factor(List.of(0, 1), 2, 3, 5, 7)), Map.of());
        var proof = FiniteConstraintCounter.count(problem, LIMITS, () -> false).certificate().orElseThrow();
        var steps = new ArrayList<>(proof.steps());
        var first = steps.get(0);
        steps.set(0, new FiniteConstraintCounter.Step(first.variable(), first.inputs(), factor(List.of(1), 5, 11)));
        var forged = new FiniteConstraintCounter.Certificate(problem, steps, proof.total(), proof.witness());
        assertEquals("LOCAL_SUM_MISMATCH", FiniteConstraintVerifier.verify(problem, forged, LIMITS, () -> false).reason());
        assertEquals(FiniteConstraintVerifier.Status.UNAVAILABLE,
            FiniteConstraintVerifier.verify(problem, proof, new IterationLimits(1, 100, 10_000), () -> false).status());
        assertEquals("CANCELLED", FiniteConstraintVerifier.verify(problem, proof, LIMITS, () -> true).reason());
        assertEquals("TERM_LIMIT", FiniteConstraintCounter.count(problem, new IterationLimits(10_000, 1, 10_000), () -> false).reason());
        var huge = new FiniteConstraintCounter.Factor(List.of(), List.of(BigInteger.ONE.shiftLeft(65_536)));
        assertEquals("RUNTIME_BIT_LIMIT", FiniteConstraintCounter.count(
            new FiniteConstraintCounter.Problem(0, List.of(huge), Map.of()), LIMITS, () -> false).reason());
        assertThrows(IllegalArgumentException.class, () -> factor(List.of(1, 0), 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FiniteConstraintCounter.Problem(1,
            List.of(factor(List.of(1), 1, 1)), Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> proof.witness().orElseThrow().put(0, true));
        assertThrows(UnsupportedOperationException.class, () -> proof.steps().clear());
    }

    @Test void matchingFixedSourcePollsDuringComparisonBeforeCheckingSteps() {
        int selected = 64;
        var fixed = new HashMap<Integer, Boolean>();
        for (int i = 0; i < selected / 2; i++) {
            fixed.put(i, true);
            fixed.put(i + 2 * selected, true);
        }
        int size = 5 * selected / 2;
        var source = new FiniteConstraintCounter.Problem(size, List.of(), fixed);
        var differentSize = new FiniteConstraintCounter.Problem(size + 1, List.of(), fixed);
        var sameSource = new FiniteConstraintCounter.Problem(size, List.of(), fixed);
        var control = new FiniteConstraintCounter.Certificate(differentSize, List.of(), BigInteger.ONE, Optional.empty());
        var deferredStepCheck = new FiniteConstraintCounter.Certificate(sameSource, List.of(), BigInteger.ONE,
            Optional.empty());
        var polls = new AtomicInteger();
        assertEquals("SOURCE_MISMATCH", FiniteConstraintVerifier.verify(source, control, LIMITS, () -> {
            polls.incrementAndGet();
            return false;
        }).reason());
        int cancelAfter = polls.get() + 8;
        polls.set(0);
        var cancelled = FiniteConstraintVerifier.verify(source, deferredStepCheck, LIMITS,
            () -> polls.incrementAndGet() > cancelAfter);
        assertEquals(FiniteConstraintVerifier.Status.UNAVAILABLE, cancelled.status(), cancelled.reason());
        assertEquals("CANCELLED", cancelled.reason());
        assertEquals("STEP_COUNT", FiniteConstraintVerifier.verify(source, deferredStepCheck, LIMITS, () -> false).reason());
    }

    @Test void finalWitnessCheckPollsForConstantFactors() {
        var one = factor(List.of(), 1);
        var problem = new FiniteConstraintCounter.Problem(0, java.util.Collections.nCopies(64, one), Map.of());
        var wrongShape = new FiniteConstraintCounter.Certificate(problem, List.of(), BigInteger.ONE,
            Optional.of(Map.of(0, true)));
        var validWitness = new FiniteConstraintCounter.Certificate(problem, List.of(), BigInteger.ONE,
            Optional.of(Map.of()));
        var polls = new AtomicInteger();
        assertEquals("WITNESS_SHAPE", FiniteConstraintVerifier.verify(problem, wrongShape, LIMITS, () -> {
            polls.incrementAndGet();
            return false;
        }).reason());
        int cancelAfter = polls.get() + 8;
        polls.set(0);
        var cancelled = FiniteConstraintVerifier.verify(problem, validWitness, LIMITS,
            () -> polls.incrementAndGet() > cancelAfter);
        assertEquals(FiniteConstraintVerifier.Status.UNAVAILABLE, cancelled.status(), cancelled.reason());
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(FiniteConstraintVerifier.verify(problem, validWitness, LIMITS, () -> false).valid());
    }

    @Test void oversizedBucketStopsGrowingAtTheWidthBoundary() {
        var distinct = new ArrayList<FiniteConstraintCounter.Factor>();
        for (int member = 1; member <= 21; member++) {
            distinct.add(factor(List.of(0, member), 1, 1, 1, 1));
        }
        var repeated = java.util.Collections.nCopies(256, distinct.getFirst());
        var early = new ArrayList<>(distinct);
        early.addAll(repeated);
        var late = new ArrayList<>(repeated);
        late.addAll(distinct);

        var polls = new AtomicInteger();
        var earlyResult = FiniteConstraintCounter.count(new FiniteConstraintCounter.Problem(22, early, Map.of()),
            LIMITS, () -> {
                polls.incrementAndGet();
                return false;
            });
        assertEquals("WIDTH_LIMIT", earlyResult.reason());
        int cancelAfter = polls.get() + 8;
        polls.set(0);
        var lateResult = FiniteConstraintCounter.count(new FiniteConstraintCounter.Problem(22, late, Map.of()),
            LIMITS, () -> polls.incrementAndGet() > cancelAfter);
        assertEquals("CANCELLED", lateResult.reason());
        assertTrue(earlyResult.certificate().isEmpty());
        assertTrue(lateResult.certificate().isEmpty());
    }

    @Test void fixedAndWitnessSnapshotsRetainUnmodifiableMapContract() {
        var original = new HashMap<Integer, Boolean>(Map.of(0, true));
        var problem = new FiniteConstraintCounter.Problem(1, List.of(), original);
        var certificate = new FiniteConstraintCounter.Certificate(problem, List.of(), BigInteger.ONE, Optional.of(original));
        original.put(0, false);
        var witness = certificate.witness().orElseThrow();
        assertEquals(Map.of(0, true), problem.fixed());
        assertEquals(Map.of(0, true), witness);

        assertThrows(UnsupportedOperationException.class, () -> problem.fixed().remove(99));
        assertThrows(UnsupportedOperationException.class, () -> witness.putAll(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> witness.keySet().remove(99));
        assertThrows(NullPointerException.class, () -> problem.fixed().get(null));
        assertThrows(NullPointerException.class, () -> witness.containsValue(null));
        original.put(0, null);
        assertThrows(NullPointerException.class, () -> new FiniteConstraintCounter.Problem(1, List.of(), original));
        assertThrows(NullPointerException.class, () ->
            new FiniteConstraintCounter.Certificate(problem, List.of(), BigInteger.ONE, Optional.of(original)));
    }

    @Test void rejectsDuplicateNumericFixedVariablesFromIdentityKeyedInput() throws ReflectiveOperationException {
        var constructor = Integer.class.getConstructor(int.class);
        var fixed = new java.util.IdentityHashMap<Integer, Boolean>();
        fixed.put(constructor.newInstance(1_000), true);
        fixed.put(constructor.newInstance(1_000), false);
        assertThrows(IllegalArgumentException.class,
            () -> new FiniteConstraintCounter.Problem(1_001, List.of(), fixed));
    }

    private static void assertCount(FiniteConstraintCounter.Problem problem, long expected) {
        var result = FiniteConstraintCounter.count(problem, LIMITS, () -> false);
        assertEquals(FiniteConstraintCounter.Status.EXACT, result.status(), result.reason());
        assertEquals(BigInteger.valueOf(expected), result.count().orElseThrow());
        var proof = result.certificate().orElseThrow();
        assertTrue(FiniteConstraintVerifier.verify(problem, proof, LIMITS, () -> false).valid());
        assertEquals(expected > 0, proof.witness().isPresent());
    }

    private static FiniteConstraintCounter.Factor factor(List<Integer> scope, long... values) {
        var table = new ArrayList<BigInteger>();
        for (long value : values) table.add(BigInteger.valueOf(value));
        return new FiniteConstraintCounter.Factor(scope, table);
    }

    private static BigInteger enumerate(FiniteConstraintCounter.Problem problem) {
        BigInteger total = BigInteger.ZERO;
        for (int assignment = 0; assignment < (1 << problem.variableCount()); assignment++) {
            boolean allowed = true;
            for (var fixed : problem.fixed().entrySet()) if (((assignment >>> fixed.getKey()) & 1) != (fixed.getValue() ? 1 : 0)) allowed = false;
            if (!allowed) continue;
            BigInteger product = BigInteger.ONE;
            for (var factor : problem.factors()) {
                int index = 0;
                for (int i = 0; i < factor.variables().size(); i++) index |= ((assignment >>> factor.variables().get(i)) & 1) << i;
                product = product.multiply(factor.values().get(index));
            }
            total = total.add(product);
        }
        return total;
    }
}
