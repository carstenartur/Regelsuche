package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ExactRationalOwnershipTest {
    private static final class DebitFailure extends RuntimeException { }
    private static final class ObservationFailure extends RuntimeException { }
    private static final class CleanupFailure extends RuntimeException { }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        final List<Set<Object>> snapshots = new ArrayList<>();
        final DebitFailure primary = new DebitFailure();
        BigInteger abortValue;
        ExactRational abortReleasedResult;
        boolean failed, failedValueObserved, failCleanup, repeatPrimary, completedResultSeen;
        int calls, failAt = -1;

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(scope);
        }
        @Override public void executionWork(long units) {
            if (scope == null) return;
            calls++;
            Set<Object> current = graph();
            boolean resultReleased = units == 4 && completedResultSeen && abortReleasedResult != null
                && current.stream().noneMatch(value -> value instanceof ExactRational rational
                    && rational.equals(abortReleasedResult));
            if (!failed && (calls == failAt || abortValue != null && containsValue(current,abortValue) || resultReleased)) {
                failed = true;
                throw primary;
            }
            if (failed && repeatPrimary && units == 4) throw primary;
            if (failed && failCleanup && units == 4) throw new CleanupFailure();
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            Set<Object> snapshot = graph();
            snapshots.add(snapshot);
            completedResultSeen |= abortReleasedResult != null && snapshot.stream().anyMatch(value ->
                value instanceof ExactRational rational && rational.equals(abortReleasedResult));
            failedValueObserved |= failed && abortValue != null && containsValue(snapshot,abortValue);
            if (failed && failCleanup) throw new ObservationFailure();
        }
        Set<Object> graph() {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var pending = new ArrayDeque<Object>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!seen.add(value)) continue;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof ExactRational rational) {
                    visitor.reference(rational.numerator());
                    visitor.reference(rational.denominator());
                } else if (value instanceof Object[] array) {
                    for (Object element : array) visitor.reference(element);
                } else if (value instanceof Optional<?> optional) {
                    optional.ifPresent(visitor::reference);
                }
            }
            return seen;
        }
        boolean saw(long... values) {
            return snapshots.stream().anyMatch(snapshot -> {
                for (long value : values) if (!containsValue(snapshot,BigInteger.valueOf(value))) return false;
                return true;
            });
        }
        boolean sawResult(ExactRational result) {
            return snapshots.stream().anyMatch(snapshot -> snapshot.contains(result));
        }
        void assertReleased() {
            assertTrue(graph().stream().noneMatch(value -> value instanceof BigInteger || value instanceof ExactRational),
                "closed arithmetic frames must not retain their input or temporary scalars");
            assertEquals(0,RetainedGraph.measure(scope).retained().characters());
        }
    }

    private static boolean containsValue(Set<Object> objects,BigInteger expected) {
        return objects.stream().anyMatch(value -> value instanceof BigInteger integer && integer.equals(expected));
    }
    private static ExactRational rational(long numerator,long denominator) {
        return new ExactRational(BigInteger.valueOf(numerator),BigInteger.valueOf(denominator));
    }
    private static <T> T observed(Observation observation,Supplier<T> operation) {
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            return operation.get();
        } finally {
            observation.assertReleased();
        }
    }

    @Test void constructorOwnsSignedInputsGcdAndNormalizedResult() {
        var observation = new Observation();
        var result = observed(observation,() -> rational(-462,-1078));
        assertEquals(rational(3,7),result);
        assertTrue(observation.saw(-462,-1078),"source numerator and denominator must be owned before normalization");
        assertTrue(observation.saw(462,1078,154),"sign-normalized operands must overlap their actual gcd");
        assertTrue(observation.saw(154,3,7),"gcd and reduced components must overlap before handoff");
        assertTrue(observation.sawResult(result),"the completed record must be owned before it is returned");
    }

    @Test void additionOwnsBothSummandsAndTheirSum() {
        var left = rational(22,39); var right = rational(35,51); var observation = new Observation();
        var result = observed(observation,() -> left.add(right));
        assertEquals(rational(829,663),result);
        assertTrue(observation.saw(3,17,13),"denominator gcd and multipliers must remain observable");
        assertTrue(observation.saw(374,455,829),"both actual cross-products must overlap the sum");
        assertTrue(observation.sawResult(result));
    }

    @Test void multiplicationOwnsCrossCancelledFactorsAndProducts() {
        var left = rational(26,35); var right = rational(77,39); var observation = new Observation();
        var result = observed(observation,() -> left.multiply(right));
        assertEquals(rational(22,15),result);
        assertTrue(observation.saw(13,7,2,11,22));
        assertTrue(observation.saw(5,3,15));
        assertTrue(observation.sawResult(result));
    }

    @Test void crossCancellationKeepsTheAbsoluteOperandUntilItsGcdIsPublished() {
        var left = rational(-26,35); var right = rational(77,39); var observation = new Observation();
        assertEquals(rational(-22,15),observed(observation,() -> left.multiply(right)));
        assertTrue(observation.saw(-26,26,13),
            "the allocated positive absolute operand must overlap its completed gcd before its slot is reused");
    }

    @Test void divisionOwnsCrossCancelledFactorsAndProducts() {
        var left = rational(26,35); var right = rational(39,77); var observation = new Observation();
        var result = observed(observation,() -> left.divide(right));
        assertEquals(rational(22,15),result);
        assertTrue(observation.saw(13,7,2,11,22));
        assertTrue(observation.saw(5,3,15));
        assertTrue(observation.sawResult(result));
    }

    @Test void comparisonOwnsBothCrossProductsUntilTheyAreCompared() {
        var left = rational(22,39); var right = rational(35,51); var observation = new Observation();
        assertEquals(-1,observed(observation,() -> left.compareTo(right)));
        assertTrue(observation.saw(3,17,13,374,455));
    }

    @Test void powerOwnsBothIntegerPowersBeforeTheRationalIsConstructed() {
        var input = rational(26,35); var observation = new Observation();
        var result = observed(observation,() -> input.pow(3));
        assertEquals(rational(17576,42875),result);
        assertTrue(observation.saw(26,35,17576,42875));
        assertTrue(observation.sawResult(result));
    }

    @Test void subtractionOwnsTheNegatedOperandWhileAdditionRuns() {
        var left = rational(22,39); var right = rational(35,51); var observation = new Observation();
        var result = observed(observation,() -> left.subtract(right));
        assertEquals(rational(-27,221),result);
        assertTrue(observation.saw(22,39,35,51,-35,374,-455));
        assertTrue(observation.sawResult(result));
    }

    @Test void negateAndReciprocalKeepCanonicalSignsAndPublishTheirResults() {
        var input = rational(-22,39); var negative = new Observation(); var reciprocal = new Observation();
        var negated = observed(negative,input::negate);
        var inverted = observed(reciprocal,input::reciprocal);
        assertEquals(rational(22,39),negated); assertEquals(rational(-39,22),inverted);
        assertTrue(negative.saw(-22,39,22)); assertTrue(negative.sawResult(negated));
        assertTrue(reciprocal.saw(-22,39,-39,22)); assertTrue(reciprocal.sawResult(inverted));
    }

    @Test void exactRootOwnsBothReturnedArraysOnSuccessfulAndRejectedPaths() {
        var square = rational(49,81); var nonSquare = rational(50,81);
        var success = new Observation(); var rejection = new Observation();
        var result = observed(success,square::sqrtExact);
        assertEquals(Optional.of(rational(7,9)),result);
        assertTrue(success.saw(49,81,7,9,0),"input components must overlap both root/remainder arrays");
        assertTrue(success.snapshots.stream().anyMatch(snapshot -> snapshot.contains(result)
            && snapshot.stream().filter(value -> value instanceof BigInteger[] array && array.length == 2).count() == 2),
            "the actual root/remainder arrays must remain owned through the Optional handoff");
        assertTrue(observed(rejection,nonSquare::sqrtExact).isEmpty());
        assertTrue(rejection.saw(50,81,7,9,1,0),"the rejected numerator remainder must stay observable");
    }

    @Test void fastAndRejectedPathsStillObserveTheirOwnedInputs() {
        var input = rational(22,39); var observation = new Observation();
        observed(observation,() -> {
            assertSame(input,input.add(ExactRational.ZERO));
            assertSame(input,input.multiply(ExactRational.ONE));
            assertSame(input,input.divide(ExactRational.ONE));
            assertSame(ExactRational.ONE,input.pow(0));
            assertSame(ExactRational.ZERO,ExactRational.ZERO.negate());
            assertEquals(0,input.compareTo(input));
            assertThrows(ArithmeticException.class,() -> input.divide(ExactRational.ZERO));
            assertThrows(IllegalArgumentException.class,() -> input.pow(-1));
            assertThrows(ArithmeticException.class,ExactRational.ZERO::reciprocal);
            assertThrows(ArithmeticException.class,() -> rational(1,0));
            return null;
        });
        assertTrue(observation.saw(22,39));
    }

    @Test void fastPathsPublishSharedResultsThatWereNotAlreadyOperands() {
        var negation = new Observation(); var power = new Observation(); var squareRoot = new Observation();
        var negative = observed(negation,ExactRational.ONE::negate);
        var one = observed(power,() -> ExactRational.ZERO.pow(0));
        var empty = observed(squareRoot,ExactRational.NEGATIVE_ONE::sqrtExact);
        assertSame(ExactRational.NEGATIVE_ONE,negative); assertSame(ExactRational.ONE,one); assertTrue(empty.isEmpty());
        assertTrue(negation.sawResult(negative),"a shared negative constant is still the actual return value");
        assertTrue(power.sawResult(one),"zero-to-the-zero uses the pre-existing canonical ONE result");
        assertTrue(squareRoot.snapshots.stream().anyMatch(snapshot -> snapshot.contains(empty)),
            "the empty Optional must be visible before the negative-root owner closes");
    }

    @Test void failedAllocationDebitStillObservesTheActualSumAndPreservesThePrimaryFailure() {
        var left = rational(22,39); var right = rational(35,51); var observation = new Observation();
        observation.abortValue = BigInteger.valueOf(829); observation.failCleanup = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DebitFailure.class,() -> left.add(right));
            assertSame(observation.primary,failure);
            assertTrue(observation.failedValueObserved,"an already allocated sum must remain owned after a failed debit");
            assertTrue(List.of(failure.getSuppressed()).stream().anyMatch(ObservationFailure.class::isInstance));
            assertTrue(List.of(failure.getSuppressed()).stream().anyMatch(CleanupFailure.class::isInstance));
            observation.assertReleased();
        }
        observation.assertReleased();
    }

    @Test void everyObservedArithmeticDebitCanAbortWithoutLeakingItsFrames() {
        var left = rational(22,39); var right = rational(35,51);
        var square = rational(49,81); var nonSquare = rational(50,81); var negative = rational(-49,81);
        List<Supplier<?>> operations = List.of(() -> rational(-462,-1078),() -> left.add(right),
            () -> left.subtract(right),() -> left.multiply(right),() -> left.divide(right),
            () -> left.negate(),() -> left.reciprocal(),() -> left.pow(3),() -> left.compareTo(right),
            square::sqrtExact,nonSquare::sqrtExact,negative::sqrtExact);
        for (Supplier<?> operation : operations) {
            var baseline = new Observation();
            int calls;
            try (var scope = RetainedOperation.open(baseline)) {
                baseline.scope = scope; operation.get(); calls = baseline.calls;
            }
            assertTrue(calls > 0,"native scalar work must contain observable ownership boundaries");
            for (int point = 1; point <= calls; point++) {
                var observation = new Observation(); observation.failAt = point;
                try (var scope = RetainedOperation.open(observation)) {
                    observation.scope = scope;
                    assertSame(observation.primary,assertThrows(DebitFailure.class,operation::get));
                    observation.assertReleased();
                }
                observation.assertReleased();
            }
        }
    }

    @Test void aRepeatedPrimaryThrowableDuringResultReleaseIsNotReplacedBySelfSuppression() {
        var left = rational(22,39); var right = rational(35,51); var observation = new Observation();
        observation.abortReleasedResult = rational(829,663); observation.repeatPrimary = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(observation.primary,assertThrows(DebitFailure.class,() -> left.add(right)));
            assertEquals(0,observation.primary.getSuppressed().length);
            observation.assertReleased();
        }
        observation.assertReleased();
    }

    @Test void aFreshValueTypeCanInitializeItsConstantsBeforeAQueryBudgetRejectsConstruction() throws Exception {
        String typeName = ExactRational.class.getName();
        var isolated = new ClassLoader(ExactRational.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if (!name.equals(typeName) && !name.startsWith(typeName + "$")) return super.loadClass(name,resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var source = getParent().getResourceAsStream(name.replace('.','/') + ".class")) {
                            if (source == null) throw new ClassNotFoundException(name);
                            byte[] bytes = source.readAllBytes();
                            loaded = defineClass(name,bytes,0,bytes.length);
                        } catch (IOException failure) { throw new ClassNotFoundException(name,failure); }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        var observation = new Observation(); observation.failAt = 1;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            Class<?> loaded = Class.forName(typeName,true,isolated);
            assertEquals(0,observation.calls,"shared constants are class initialization, not query allocations");
            var constructor = loaded.getConstructor(BigInteger.class,BigInteger.class);
            var failure = assertThrows(InvocationTargetException.class,() ->
                constructor.newInstance(BigInteger.valueOf(99),BigInteger.valueOf(101)));
            assertSame(observation.primary,failure.getCause());
            Object zero = loaded.getField("ZERO").get(null);
            Object one = loaded.getField("ONE").get(null);
            Object negativeOne = loaded.getField("NEGATIVE_ONE").get(null);
            assertEquals(BigInteger.ZERO,loaded.getMethod("numerator").invoke(zero));
            assertEquals(BigInteger.ONE,loaded.getMethod("numerator").invoke(one));
            assertEquals(BigInteger.valueOf(-1),loaded.getMethod("numerator").invoke(negativeOne));
            observation.assertReleased();
        }
        observation.assertReleased();
    }
}
