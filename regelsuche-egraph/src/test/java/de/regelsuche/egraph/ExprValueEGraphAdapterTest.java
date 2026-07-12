package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExprValueEGraphAdapterTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void equalValuesFromDifferentFactoriesReuseOneEClassRepresentation() {
        EGraph graph = new EGraph();
        ENode.ExprValueAdapter adapter = new ENode.ExprValueAdapter(graph);
        try (ExprValueFactory firstFactory = new ExprValueFactory();
                ExprValueFactory secondFactory = new ExprValueFactory()) {
            ExprValue first = firstFactory.fromExpr(parser.parseTerm("(a + b) + c"));
            ExprValue second = secondFactory.fromExpr(parser.parseTerm("c + a + b"));

            assertNotSame(first, second);
            assertEquals(first.key(), second.key());
            EClassId firstClass = adapter.add(first);
            EClassId secondClass = adapter.add(second);

            assertEquals(graph.find(firstClass), graph.find(secondClass));
            assertEquals(graph.find(firstClass), adapter.classFor(first.key()).orElseThrow());
            assertEquals(4, adapter.mappedValueCount(),
                "a, b, c and their shared n-ary sum are mapped independently");
        }
    }

    @Test
    void multiplicityRemainsPartOfTheEGraphRepresentation() {
        EGraph graph = new EGraph();
        ENode.ExprValueAdapter adapter = new ENode.ExprValueAdapter(graph);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue repeated = factory.fromExpr(parser.parseTerm("a + a + b"));
            ExprValue single = factory.fromExpr(parser.parseTerm("a + b"));

            EClassId repeatedClass = adapter.add(repeated);
            EClassId singleClass = adapter.add(single);

            assertFalse(graph.areEquivalent(repeatedClass, singleClass));
            Expr extracted = graph.extract(repeatedClass, node -> 1);
            assertEquals(repeated.key(), factory.fromExpr(extracted).key());
        }
    }

    @Test
    void existingAstInsertionRemainsCompatibleWithDeterministicValueInsertion() {
        EGraph graph = new EGraph();
        ENode.ExprValueAdapter adapter = new ENode.ExprValueAdapter(graph);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            Expr syntax = parser.parseTerm("a + b + c");
            EClassId fromValue = adapter.add(factory.fromExpr(syntax));
            EClassId fromAst = graph.addExpression(syntax);

            assertEquals(graph.find(fromValue), graph.find(fromAst));
        }
    }

    @Test
    void adapterPreservesAndChecksAssumptionSignatures() {
        EGraph graph = new EGraph();
        ENode.ExprValueAdapter adapter = new ENode.ExprValueAdapter(graph);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue value = factory.fromExpr(parser.parseTerm("x / x"));
            EClassId id = adapter.add(value, List.of("0 != x"));

            assertEquals("x != 0", graph.assumptionsFor(id).fingerprint());
            assertThrows(IllegalArgumentException.class,
                () -> adapter.add(value, List.of("x > 0")));
        }
    }

    @Test
    void valueKeyAndEClassIdRemainDifferentIdentityDomains() {
        EGraph graph = new EGraph();
        ENode.ExprValueAdapter adapter = new ENode.ExprValueAdapter(graph);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue value = factory.fromExpr(parser.parseTerm("a + b"));
            EClassId eClass = adapter.add(value);

            assertNotEquals(value.key().toString(), eClass.toString());
            assertEquals(eClass, adapter.classFor(value.key()).orElseThrow());
        }
    }
}
