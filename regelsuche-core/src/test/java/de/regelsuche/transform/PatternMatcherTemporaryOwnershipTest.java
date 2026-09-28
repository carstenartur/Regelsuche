package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatternMatcherTemporaryOwnershipTest {
    private static final class MatchAbort extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        Expr input;
        long work;
        int alternatives, tasks, mutableBindingMaps;
        int nonGrowingCheckpoints;
        Set<Object> previousObjects;
        boolean inputMissing, sawBoundChoice, abortBoundChoice;
        boolean abortResultClose;
        int outcomeBranches;
        MatchAbort closeFailure;
        long workAfterCloseFailure;
        Map<String,Expr> callerBindings;
        boolean sawPublishedBindings;
        @Override public void executionWork(long units) {
            work = Math.addExact(work, units);
            if (closeFailure != null) workAfterCloseFailure += units;
            else if (abortResultClose && outcomeBranches > 0 && units == 4) {
                closeFailure = new MatchAbort();
                throw closeFailure;
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            int currentAlternatives = 0, currentTasks = 0, currentMutableBindingMaps = 0;
            boolean bound = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove();
                if (!seen.add(value)) continue;
                if (value instanceof EquivalenceAwarePatternMatcher.MatchAttempt attempt)
                    outcomeBranches = attempt.visitedBranches();
                if (value.getClass().getEnclosingClass() == EquivalenceAwarePatternMatcher.class) {
                    if (value.getClass().getSimpleName().equals("Alternative")) currentAlternatives++;
                    if (value.getClass().getSimpleName().endsWith("Task")) currentTasks++;
                }
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map) {
                    if (value instanceof HashMap<?,?>) currentMutableBindingMaps++;
                    bound |= map.containsKey("A");
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                } else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
            }
            alternatives = Math.max(alternatives,currentAlternatives);
            tasks = Math.max(tasks,currentTasks);
            mutableBindingMaps = Math.max(mutableBindingMaps,currentMutableBindingMaps);
            sawPublishedBindings |= callerBindings != null && !callerBindings.isEmpty()
                && seen.contains(callerBindings)
                && seen.stream().anyMatch(value -> value instanceof EquivalenceAwarePatternMatcher.MatchAttempt);
            if (previousObjects != null && previousObjects.containsAll(seen)) nonGrowingCheckpoints++;
            previousObjects = seen;
            inputMissing |= !seen.contains(input);
            sawBoundChoice |= bound && currentAlternatives >= 1;
            if (abortBoundChoice && bound && currentAlternatives >= 1) throw new MatchAbort();
        }
    }

    private static PatternExpr pattern() {
        return PatternExpr.fn("f",PatternExpr.op(ADD,PatternExpr.var("A"),PatternExpr.var("B")),PatternExpr.var("A"));
    }

    @Test void aStraightMatchUsesOnePrivateBindingMapWithoutASavedChoice() {
        Expr input = new VariableExpr("x");
        var initial = Map.<String,Expr>of("seed",input);
        var observation = new Observation(); observation.input = input;
        EquivalenceAwarePatternMatcher.MatchAttempt result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = EquivalenceAwarePatternMatcher.matchDetailed(
                PatternExpr.var("A"),input,initial,RecognitionProfile.arithmeticAc());
        }
        assertTrue(result.matched());
        assertEquals(Map.of("seed",input,"A",input),result.bindings());
        assertEquals(Map.of("seed",input),initial);
        assertEquals(0,result.visitedBranches());
        assertEquals(0,observation.alternatives,"only a real deferred choice needs an alternative");
        assertEquals(1,observation.mutableBindingMaps,"the invocation already owns a private working map");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void consumingLiteralTasksDoesNotRescanAShrinkingOwnedGraph() {
        Expr input = new ExpressionParser().parseTerm("f(x,y)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(
                PatternExpr.fn("f",new PatternExpr.LiteralVariable("x"),new PatternExpr.LiteralVariable("y")),
                input,Map.of(),RecognitionProfile.exact()).matched());
        }
        assertEquals(0,observation.nonGrowingCheckpoints,
            "literal comparisons only consume already observed tasks; they create no owned graph growth");
        assertTrue(observation.tasks >= 3,"new continuation chains still require a peak observation");
        assertFalse(observation.inputMissing);
    }

    @Test void actualBacktrackingContinuationAndBindingCopiesRemainOwned() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        EquivalenceAwarePatternMatcher.MatchAttempt result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = EquivalenceAwarePatternMatcher.matchDetailed(pattern(),input,Map.of(),RecognitionProfile.arithmeticAc());
        }
        assertTrue(result.matched());
        assertEquals(Map.of("A",new VariableExpr("y"),"B",new VariableExpr("x")),result.bindings());
        assertTrue(result.visitedBranches() > 2,"the later constraint must reopen an earlier AC choice");
        assertTrue(observation.alternatives >= 1,"a saved actual alternative remains owned while the current branch executes");
        assertTrue(observation.tasks >= 3,"the real continuation chain is retained");
        assertTrue(observation.sawBoundChoice,"a current binding overlaps its saved alternative");
        assertFalse(observation.inputMissing);
        assertTrue(observation.work > 0);
        assertEquals(2,RetainedGraph.measure(result).retained().nodes(),"the returned binding graph is independently describable");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void abortWithAStoredChoiceKeepsPaidWorkAndDoesNotPublishPartialBindings() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var bindings = new HashMap<String,Expr>();
        var observation = new Observation(); observation.input = input; observation.abortBoundChoice = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(MatchAbort.class,() -> EquivalenceAwarePatternMatcher.matchDetailed(
                pattern(),input,bindings,RecognitionProfile.arithmeticAc()));
            assertTrue(bindings.isEmpty());
            assertTrue(observation.work > 0);
            assertTrue(observation.sawBoundChoice);
            assertFalse(observation.inputMissing);
            observation.abortBoundChoice = false;
            assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(pattern(),input,bindings,RecognitionProfile.arithmeticAc()).matched());
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void aFailedResultHandoffSettlesBranchesThatNoCallerCanReceive() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var bindings = new HashMap<String,Expr>();
        var observation = new Observation(); observation.input = input; observation.abortResultClose = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> EquivalenceAwarePatternMatcher.matchDetailed(
                pattern(),input,bindings,RecognitionProfile.arithmeticAc()));
            assertSame(observation.closeFailure,failure,"the failed handoff preserves its original error");
            assertTrue(observation.outcomeBranches > 2);
            assertEquals(observation.outcomeBranches,observation.workAfterCloseFailure,
                "no outcome was returned: its delegated branch work must be settled exactly once here");
            assertTrue(bindings.isEmpty());
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void booleanCompatibilityPublicationKeepsCallerAndResultBindingsOwned() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var bindings = new HashMap<String,Expr>();
        var observation = new Observation(); observation.input = input; observation.callerBindings = bindings;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(EquivalenceAwarePatternMatcher.match(pattern(),input,bindings,RecognitionProfile.arithmeticAc()));
        }
        assertEquals(Map.of("A",new VariableExpr("y"),"B",new VariableExpr("x")),bindings);
        assertTrue(observation.sawPublishedBindings,
            "the boolean API must observe its actual caller/result binding overlap before returning");
        assertTrue(observation.outcomeBranches > 2);
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
}
