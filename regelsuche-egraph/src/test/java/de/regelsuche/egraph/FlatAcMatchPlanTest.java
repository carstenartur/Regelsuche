package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.PatternExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlatAcMatchPlanTest {
    @Test
    void multiplicityBindingsAgreeWithTheCompleteFiniteReference() {
        for (var operator : List.of(BinaryOperator.ADD, BinaryOperator.MUL)) {
            var graph = EGraph.exactScalarAcV1();
            List<Expr> atoms = List.of(new VariableExpr("a"), new VariableExpr("b"), new VariableExpr("c"));
            var ids = atoms.stream().map(graph::addExpression).toList();
            var x = PatternExpr.var("X");
            var y = PatternExpr.var("Y");
            var plan = FlatAcMatchPlan.compile(PatternExpr.op(operator, x, PatternExpr.op(operator, y, x)));

            Set<EGraphPatternMatcher.Match> oracle = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                for (int j = i; j < 3; j++) {
                    for (int k = j; k < 3; k++) {
                        var root = graph.addExpression(new BinaryExpr(atoms.get(i), operator,
                            new BinaryExpr(atoms.get(j), operator, atoms.get(k))));
                        var tuple = List.of(i, j, k);
                        // Independent exhaustive assignments, without using matcher frequencies.
                        for (int a = 0; a < 3; a++) {
                            for (int b = 0; b < 3; b++) {
                                if (List.of(a, b, a).stream().sorted().toList().equals(tuple)) {
                                    oracle.add(new EGraphPatternMatcher.Match(root, Map.of("X", ids.get(a), "Y", ids.get(b))));
                                }
                            }
                        }
                    }
                }
            }
            var matcher = new EGraphPatternMatcher(graph);
            var result = matcher.matchFlatAc(plan, 10_000);
            assertTrue(result.complete());
            assertEquals(oracle, new HashSet<>(result.matches()));
            assertEquals(oracle.size(), result.matches().size());
            assertEquals(result, matcher.matchFlatAc(plan, 10_000));
            assertEquals(result, matcher.matchFlatAc(plan, result.work()));
            var almost = matcher.matchFlatAc(plan, result.work() - 1);
            assertFalse(almost.complete());
            assertTrue(oracle.containsAll(almost.matches()));
            var limited = matcher.matchFlatAc(plan, 4);
            assertFalse(limited.complete());
            assertEquals(4, limited.work());
            assertTrue(oracle.containsAll(limited.matches()));
        }
    }

    @Test
    void equalClassAssignmentsAndRebuildPreserveMultiplicity() {
        var graph = EGraph.exactScalarAcV1();
        var a = new VariableExpr("a");
        var b = new VariableExpr("b");
        var root = graph.addExpression(new BinaryExpr(a, BinaryOperator.ADD, b));
        var x = PatternExpr.var("X");
        var plan = FlatAcMatchPlan.compile(PatternExpr.op(BinaryOperator.ADD, x, x));
        var matcher = new EGraphPatternMatcher(graph);
        assertTrue(matcher.matchFlatAc(plan, 100).matches().isEmpty());
        var id = graph.union(graph.addExpression(a), graph.addExpression(b));
        graph.rebuild();
        assertEquals(List.of(new EGraphPatternMatcher.Match(graph.find(root), Map.of("X", graph.find(id)))),
            matcher.matchFlatAc(plan, 100).matches());
    }

    @Test
    void fragmentAndLimitsAreExplicit() {
        var x = PatternExpr.var("X");
        var y = PatternExpr.var("Y");
        var sum = PatternExpr.op(BinaryOperator.ADD, x, y);
        var plan = FlatAcMatchPlan.compile(sum);
        assertEquals(plan, FlatAcMatchPlan.compile(PatternExpr.op(BinaryOperator.ADD, y, x)));
        assertNotEquals(plan, FlatAcMatchPlan.compile(PatternExpr.op(BinaryOperator.MUL, x, y)));
        assertThrows(IllegalArgumentException.class, () -> FlatAcMatchPlan.compile(PatternExpr.op(BinaryOperator.DIV, x, y)));
        assertThrows(IllegalArgumentException.class, () -> FlatAcMatchPlan.compile(PatternExpr.op(BinaryOperator.ADD, x, PatternExpr.num(1))));
        PatternExpr excessive = x;
        for (int i = 0; i < FlatAcMatchPlan.MAX_ARITY; i++) excessive = PatternExpr.op(BinaryOperator.ADD, excessive, x);
        var large = excessive;
        assertThrows(IllegalArgumentException.class, () -> FlatAcMatchPlan.compile(large));
        assertThrows(IllegalArgumentException.class, () -> new EGraphPatternMatcher(EGraph.exactScalarAcV1())
            .matchFlatAc(plan, FlatAcMatchPlan.MAX_WORK + 1));
        assertThrows(IllegalArgumentException.class, () -> new EGraphPatternMatcher(new EGraph()).matchFlatAc(plan, 100));
        assertThrows(IllegalArgumentException.class, () -> new EGraphPatternMatcher(EGraph.exactScalarAcV1()).matchFlatAc(plan, -1));
    }
}
