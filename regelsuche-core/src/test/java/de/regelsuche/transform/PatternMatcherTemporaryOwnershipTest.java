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
        int alternatives, tasks;
        boolean inputMissing, sawBoundChoice, abortBoundChoice;
        @Override public void executionWork(long units) { work = Math.addExact(work, units); }
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
            int currentAlternatives = 0, currentTasks = 0;
            boolean bound = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove();
                if (!seen.add(value)) continue;
                if (value.getClass().getEnclosingClass() == EquivalenceAwarePatternMatcher.class) {
                    if (value.getClass().getSimpleName().equals("Alternative")) currentAlternatives++;
                    if (value.getClass().getSimpleName().endsWith("Task")) currentTasks++;
                }
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map) {
                    bound |= map.containsKey("A");
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                } else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
            }
            alternatives = Math.max(alternatives,currentAlternatives);
            tasks = Math.max(tasks,currentTasks);
            inputMissing |= !seen.contains(input);
            sawBoundChoice |= bound && currentAlternatives >= 2;
            if (abortBoundChoice && bound && currentAlternatives >= 2) throw new MatchAbort();
        }
    }

    private static PatternExpr pattern() {
        return PatternExpr.fn("f",PatternExpr.op(ADD,PatternExpr.var("A"),PatternExpr.var("B")),PatternExpr.var("A"));
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
        assertTrue(observation.alternatives >= 2,"the selected and saved actual alternatives overlap");
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
}
