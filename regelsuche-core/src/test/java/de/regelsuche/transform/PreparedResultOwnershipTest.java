package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PreparedResultOwnershipTest {
    private static final Expr TARGET = new VariableExpr("x");
    private static final Expr SOURCE = new BinaryExpr(TARGET, BinaryOperator.ADD, new NumberExpr(0));
    private static final List<String> EXPRESSIONS = List.of("denominator != 0");

    private static final class GuardedRule implements RewriteRule, RetainedGraph.View {
        @Override public String id() { return "guarded"; }
        @Override public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        @Override public boolean mayIncreaseComplexity() { return false; }
        @Override public int estimatedCostDelta() { return -2; }
        @Override public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override public boolean matches(Expr subtree) { return SOURCE.equals(subtree); }
        @Override public Expr apply(Expr subtree) { return TARGET; }
        @Override public List<Assumption> assumptions(Expr subtree) {
            return new ArrayList<>(List.of(Assumption.nonZero("denominator")));
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    private static AstRewriteTransport transport() {
        return new AstRewriteTransport(List.of(new GuardedRule(), new GuardedRule()), 64, 128);
    }

    private static final class WorkLimit extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        final WorkLimit primary = new WorkLimit();
        final Set<AstRewriteTransport.Step> actualSteps = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<RuleDescriptor> descriptors = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean copiedAssumptions, mappedExpressionsOverlap, abortAssumptionCopy, abortStepDebit;
        boolean aborted, failCleanup, cleanupFailed;
        long work;

        @Override public void executionWork(long units) {
            work = Math.addExact(work, units);
            if (aborted && failCleanup && !cleanupFailed && units == 4) {
                cleanupFailed = true;
                throw primary;
            }
            if (scope != null && abortStepDebit) {
                inspect();
                if (!actualSteps.isEmpty()) {
                    abortStepDebit = false;
                    aborted = true;
                    throw primary;
                }
            }
        }
        @Override public void validationWork(long units) { work = Math.addExact(work, units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            inspect();
            if (abortAssumptionCopy && copiedAssumptions) {
                aborted = true;
                throw primary;
            }
        }

        private void inspect() {
            RetainedGraph.measure(scope);
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var assumptionLists = new ArrayList<List<?>>();
            int expressionLists = 0;
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!seen.add(value)) continue;
                if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
                if (value instanceof AstRewriteTransport.Step step) actualSteps.add(step);
                if (value instanceof RuleDescriptor descriptor) descriptors.add(descriptor);
                if (value instanceof List<?> list) {
                    if (list.size() == 1 && list.getFirst() instanceof Assumption) assumptionLists.add(list);
                    if (list.size() == 1 && list.getFirst() instanceof String text
                            && text.equals(EXPRESSIONS.getFirst())) expressionLists++;
                }
                if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
                else if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Collection<?> collection) collection.forEach(visitor::reference);
                else if (value instanceof Map<?, ?> map) map.forEach((key, entry) -> {
                    visitor.reference(key); visitor.reference(entry);
                });
                else if (value instanceof Object[] array) for (Object entry : array) visitor.reference(entry);
            }
            for (var original : assumptionLists) for (var copy : assumptionLists)
                if (original instanceof ArrayList<?> && original != copy && !(copy instanceof ArrayList<?>)
                        && original.equals(copy)) copiedAssumptions = true;
            mappedExpressionsOverlap |= expressionLists >= 2;
        }
    }

    private static void assertReleased(Observation observation) {
        assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(observation.scope).retained());
        for (var frame : observation.frames)
            assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
    }

    @Test void discardedEqualStepAndAssumptionCopiesRemainOwnedDuringNativeAssembly() {
        var transport = transport();
        var expected = transport.generate(SOURCE);
        assertEquals(1, expected.size(), "equal generated steps still collapse in the existing ordered set");
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(expected, transport.generate(SOURCE));
        }
        assertTrue(observation.copiedAssumptions, "the rule's mutable assumptions and the RewriteResult copy overlap");
        assertTrue(observation.mappedExpressionsOverlap, "mapped expressions remain owned during Step normalization");
        assertEquals(2, observation.actualSteps.size(), "the rejected duplicate Step still existed and must be measured");
        assertEquals(4, observation.descriptors.size(), "both original descriptor calls per step remain observable");
        assertReleased(observation);
    }

    @Test void assumptionCopyCanAbortBeforeAnyAuthorizedStepIsConstructed() {
        var observation = new Observation(); observation.abortAssumptionCopy = true;
        var transport = transport();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(observation.primary, assertThrows(WorkLimit.class, () -> transport.generate(SOURCE)));
        }
        assertTrue(observation.copiedAssumptions);
        assertTrue(observation.actualSteps.isEmpty());
        assertReleased(observation);
        assertEquals(1, transport.generate(SOURCE).size());
    }

    @Test void failedCompletedStepDebitPreservesTheSamePrimaryFailureDuringCleanup() {
        var observation = new Observation(); observation.abortStepDebit = true; observation.failCleanup = true;
        var transport = transport();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(observation.primary, assertThrows(WorkLimit.class, () -> transport.generate(SOURCE)));
        }
        assertTrue(observation.aborted);
        assertTrue(observation.cleanupFailed);
        assertEquals(1, observation.actualSteps.size());
        assertEquals(0, observation.primary.getSuppressed().length, "rethrowing the same failure must not self-suppress");
        assertReleased(observation);
        assertEquals(1, transport.generate(SOURCE).size());
    }
}
