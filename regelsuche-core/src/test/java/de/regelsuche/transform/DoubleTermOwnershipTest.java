package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DoubleTermOwnershipTest {
    private static final VariableExpr X = new VariableExpr("x");
    private static final VariableExpr Y = new VariableExpr("y");
    private static final VariableExpr Z = new VariableExpr("z");
    private static final List<Expr> TERMS = List.of(X, Y, X, Z);
    private static final Expr SOURCE = add(add(X, Y), add(X, Z));
    private static final Expr PRODUCT = new BinaryExpr(new NumberExpr(2), BinaryOperator.MUL, X);
    private static final Expr TARGET = add(add(Y, Z), PRODUCT);

    private static Expr add(Expr left, Expr right) {
        return new BinaryExpr(left, BinaryOperator.ADD, right);
    }

    private static RewriteRule rule() {
        return AstRewriteTransformationEngine.defaultRules().stream()
            .filter(candidate -> candidate.id().equals("ast_double_term")).findFirst().orElseThrow();
    }

    private static final class WorkLimit extends RuntimeException { }
    private static final class CleanupLimit extends RuntimeException { }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        final WorkLimit primary = new WorkLimit();
        final CleanupLimit cleanup = new CleanupLimit();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        long work;
        int equalTermLists;
        boolean indicesSeen, productSeen, targetSeen, emptyListSeen;
        boolean failFlattenDebit, flattenDebitFailed, failCopyDebit, copyDebitFailed;
        boolean abortIndices, abortProduct, aborted, failCleanup, repeatPrimary, cleanupFailed;

        @Override public void executionWork(long units) {
            work = Math.addExact(work, units);
            if (aborted && failCleanup && !cleanupFailed && units == 4) {
                cleanupFailed = true;
                throw repeatPrimary ? primary : cleanup;
            }
            if (scope == null) return;
            if (failFlattenDebit && units == 2 && StackWalker.getInstance().walk(stack -> stack.anyMatch(
                    frame -> frame.getClassName().endsWith("$DoubleTermRule")
                        && frame.getMethodName().equals("flattenAddition")))) {
                failFlattenDebit = false;
                flattenDebitFailed = true;
                throw primary;
            }
            if (failCopyDebit) {
                inspect();
                if (equalTermLists >= 2) {
                    failCopyDebit = false;
                    copyDebitFailed = true;
                    throw primary;
                }
            }
        }

        @Override public void validationWork(long units) { work = Math.addExact(work, units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }

        @Override public void checkpoint() {
            inspect();
            if (abortIndices && indicesSeen || abortProduct && productSeen) {
                aborted = true;
                throw primary;
            }
        }

        private void inspect() {
            RetainedGraph.measure(scope);
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            int termLists = 0;
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!seen.add(value)) continue;
                if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
                if (value instanceof ArrayList<?> list) {
                    if (list.isEmpty()) emptyListSeen = true;
                    if (sameTerms(list)) termLists++;
                }
                if (value instanceof int[] indices && indices.length == 2 && indices[0] == 0 && indices[1] == 2)
                    indicesSeen = true;
                if (value instanceof BinaryExpr binary) {
                    if (isProduct(binary)) productSeen = true;
                    if (binary.operator() == BinaryOperator.ADD && isProduct(binary.right())
                            && binary.left() instanceof BinaryExpr left && left.operator() == BinaryOperator.ADD
                            && left.left() == Y && left.right() == Z) targetSeen = true;
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
                else if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Collection<?> collection) collection.forEach(visitor::reference);
                else if (value instanceof Map<?, ?> map) map.forEach((key, entry) -> {
                    visitor.reference(key); visitor.reference(entry);
                });
                else if (value instanceof Object[] array) for (Object entry : array) visitor.reference(entry);
            }
            equalTermLists = Math.max(equalTermLists, termLists);
        }

        // Observer classification must not re-enter the paid Expr equality path.
        private static boolean sameTerms(List<?> values) {
            if (values.size() != TERMS.size()) return false;
            for (int index = 0; index < values.size(); index++)
                if (values.get(index) != TERMS.get(index)) return false;
            return true;
        }

        private static boolean isProduct(Expr value) {
            return value instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL
                && binary.right() == X && binary.left() instanceof NumberExpr number
                && number.value().numerator().equals(BigInteger.TWO)
                && number.value().denominator().equals(BigInteger.ONE);
        }
    }

    private static void assertReleased(Observation observation) {
        assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(observation.scope).retained());
        for (var frame : observation.frames)
            assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
    }

    @Test void discardedMatchingIndicesRemainObservableBeforeMatchReturns() {
        var observation = new Observation(); observation.abortIndices = true;
        var rule = rule();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(observation.primary, assertThrows(WorkLimit.class, () -> rule.matches(SOURCE)));
        }
        assertTrue(observation.indicesSeen, "matches allocates an actual index array even though it returns only a boolean");
        assertTrue(observation.work > 1);
        assertReleased(observation);
        assertTrue(rule.matches(SOURCE), "aborted observation cannot leak into ordinary application");
    }

    @Test void failedFlattenAllocationDebitStillObservesTheActualEmptyList() {
        var observation = new Observation(); observation.failFlattenDebit = true;
        var rule = rule();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(observation.primary, assertThrows(WorkLimit.class, () -> rule.matches(SOURCE)));
        }
        assertTrue(observation.flattenDebitFailed);
        assertTrue(observation.emptyListSeen, "completed list allocation is owned before a debit may throw");
        assertReleased(observation);
    }

    @Test void failedTermCopyDebitKeepsBothActualListsAndTheSelectedIndices() {
        var observation = new Observation(); observation.failCopyDebit = true;
        var rule = rule();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(observation.primary, assertThrows(WorkLimit.class, () -> rule.apply(SOURCE)));
        }
        assertTrue(observation.copyDebitFailed);
        assertTrue(observation.equalTermLists >= 2, "flattened terms overlap their mutable replacement copy");
        assertTrue(observation.indicesSeen);
        assertFalse(observation.productSeen, "an aborted copy must not construct the later replacement");
        assertReleased(observation);
    }

    @Test void replacementAndEveryRebuiltParentAreObservedWithoutChangingFirstPairOrder() {
        var observation = new Observation();
        Expr actual;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            actual = rule().apply(SOURCE);
        }
        assertEquals(TARGET, actual);
        assertSame(X, ((BinaryExpr) ((BinaryExpr) actual).right()).right(), "the duplicate itself is shared");
        assertTrue(observation.productSeen);
        assertTrue(observation.targetSeen);
        assertTrue(observation.equalTermLists >= 2);
        assertReleased(observation);
    }

    @Test void productAbortPreservesThePrimaryFailureWhenCleanupAlsoFails() {
        for (boolean repeatedPrimary : List.of(false, true)) {
            var observation = new Observation(); observation.abortProduct = true;
            observation.failCleanup = true; observation.repeatPrimary = repeatedPrimary;
            var rule = rule();
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                assertSame(observation.primary, assertThrows(WorkLimit.class, () -> rule.apply(SOURCE)));
            }
            assertTrue(observation.productSeen);
            assertTrue(observation.cleanupFailed);
            assertEquals(repeatedPrimary ? List.of() : List.of(observation.cleanup),
                List.of(observation.primary.getSuppressed()));
            assertReleased(observation);
            assertEquals(TARGET, rule.apply(SOURCE));
        }
    }

    @Test void nonMatchingAdditionKeepsAttemptedWorkAndReleasesItsTerms() {
        var source = add(add(X, Y), Z);
        var rule = rule();
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertFalse(rule.matches(source));
            assertThrows(IllegalArgumentException.class, () -> rule.apply(source));
        }
        assertTrue(observation.work > 4, "failed matching and failed application still execute and settle work");
        assertTrue(observation.emptyListSeen);
        assertFalse(observation.indicesSeen);
        assertReleased(observation);
    }

    @Test void nativeTransportMeasuresTheRegisteredRuleAndPreservesItsOrderedCandidates() {
        var transport = new AstRewriteTransport(List.of(rule()), 64, 128);
        var expected = transport.generate(SOURCE);
        assertEquals(1, expected.size());
        assertEquals(TARGET, expected.getFirst().target());
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(expected, transport.generate(SOURCE));
        }
        assertTrue(observation.indicesSeen, "the registered primitive dispatch must reach the instrumented matcher");
        assertTrue(observation.productSeen);
        assertReleased(observation);
    }
}
