package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.OperatorLaws;
import de.regelsuche.value.ExprValueFactory.ValueOperator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAcGraphTest {
    @Test
    void semanticValueInsertionUsesOneNodeForTheWholeSum() {
        var graph = EGraph.exactScalarAcV1();
        var adapter = new ENode.ExprValueAdapter(graph);
        try (var factory = new ExprValueFactory()) {
            var sum = factory.fromExpr(parse("a + b + a + c"));
            var root = adapter.add(sum);
            assertEquals(4, graph.nodeCount(), "three leaves and one AC node");
            assertEquals(root, graph.addExpression(parse("c + (a + (b + a))")));
            assertEquals(sum.key(), factory.fromExpr(graph.extract(root, node -> 1)).key());
            assertEquals(sum.key(), factory.fromExpr(graph.representatives(root, node -> 1).getFirst()).key());
            assertNotEquals(root, graph.addExpression(parse("a + b + c")));
        }
    }

    @Test
    void repetitionsAndGroupingDoNotAllocateIntermediateProducts() {
        var graph = EGraph.exactScalarAcV1();
        Expr x = new VariableExpr("x");
        Expr term = x;
        for (int i = 1; i < 1024; i++) term = new BinaryExpr(term, BinaryOperator.MUL, x);
        var root = graph.addExpression(term);
        assertEquals(2, graph.nodeCount());
        assertEquals(Set.of(root), Set.copyOf(graph.classesWith(new ENodeSignature("ac:MUL", 1024))));
        assertEquals(1024, graph.classOrThrow(root).nodes().iterator().next().children().size());
    }

    @Test
    void nativeInsertionPreservesOrderedRolesAndTheOperatorBoundary() {
        var graph = EGraph.exactScalarAcV1();
        for (String op : List.of("-", "/", "^")) {
            assertNotEquals(graph.addExpression(parse("a" + op + "b")), graph.addExpression(parse("b" + op + "a")));
        }
        assertNotEquals(graph.addExpression(parse("f(a,b)")), graph.addExpression(parse("f(b,a)")));
        assertNotEquals(graph.addExpression(parse("a*(b+c)")), graph.addExpression(parse("a+b*c")));
        var a = graph.addExpression(parse("a"));
        assertThrows(IllegalArgumentException.class, () -> graph.add(new ENode("op:MUL", List.of(a, a))));
        var ordered = new EGraph();
        var x = ordered.addExpression(parse("x"));
        assertThrows(IllegalArgumentException.class, () -> ordered.add(new ENode("ac:ADD", List.of(x, x))));
    }

    @Test
    void customOperatorLawsAreRejectedBeforeItsNodeIsInterned() {
        var graph = EGraph.exactScalarAcV1();
        var adapter = new ENode.ExprValueAdapter(graph);
        try (var factory = new ExprValueFactory()) {
            var args = List.of(factory.variable("a"), factory.variable("b"));
            var orderedMul = new ValueOperator("mul", 2, 2, OperatorLaws.NONE);
            var acSub = new ValueOperator("sub", 2, 5, OperatorLaws.ASSOCIATIVE_COMMUTATIVE);
            assertThrows(IllegalArgumentException.class, () -> adapter.add(factory.ordered(orderedMul, args)));
            assertThrows(IllegalArgumentException.class, () -> adapter.add(factory.ordered(acSub, args)));
            assertEquals(0, graph.nodeCount());
        }
    }

    @Test
    void rebuildResortsAliasedChildIdsAndPreservesMultiplicity() {
        var graph = EGraph.exactScalarAcV1();
        var a = graph.addExpression(parse("a"));
        graph.addExpression(parse("b"));
        var c = graph.addExpression(parse("c"));
        var first = graph.addExpression(parse("a + b + a"));
        var second = graph.addExpression(parse("b + c + c"));
        var different = graph.addExpression(parse("a + b"));
        graph.union(a, c);
        graph.rebuild();
        assertTrue(graph.areEquivalent(first, second));
        assertFalse(graph.areEquivalent(first, different));
        assertEquals(graph.find(second), graph.addExpression(parse("c + b + a")));
    }

    @Test
    void valueInsertionAndPermutedSyntaxUseTheSameAssumptionContract() {
        var graph = EGraph.exactScalarAcV1();
        var adapter = new ENode.ExprValueAdapter(graph);
        try (var factory = new ExprValueFactory()) {
            var value = factory.fromExpr(parse("x + y + z"));
            var root = adapter.add(value, List.of("x != 0"));
            assertEquals(root, graph.addExpression(parse("z + x + y"), List.of("0 != x")));
            assertThrows(IllegalArgumentException.class,
                () -> graph.addExpression(parse("z + y + x"), List.of("x > 0")));
        }
    }

    @Test
    void syntaxMatcherCannotReturnASpuriousFixpointForASemanticGraph() {
        var graph = EGraph.exactScalarAcV1();
        var root = graph.addExpression(parse("x + 0"));
        var x = PatternExpr.var("X");
        var rule = new PatternRewriteRule("add-zero", PatternExpr.op(BinaryOperator.ADD, x, PatternExpr.num(0)), x);
        var runner = new EqualitySaturation(List.of(rule));
        assertThrows(IllegalArgumentException.class, () -> runner.saturate(graph, root, node -> 1));
        assertFalse(graph.areEquivalent(root, graph.addExpression(parse("x"))));
        assertTrue(new EqualitySaturation(List.of()).saturate(graph, root, node -> 1).stats().saturated());
    }

    private static Expr parse(String text) {
        return new ExpressionParser().parse(new InputRequest(InputType.TERM, text)).terms().getFirst();
    }
}
