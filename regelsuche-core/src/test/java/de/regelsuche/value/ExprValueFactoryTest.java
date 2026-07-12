package de.regelsuche.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExprValueFactoryTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void equalValuesAreReferenceIdenticalInsideOneScope() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            assertSame(factory.variable("a"), factory.variable("a"));
            assertSame(factory.fromExpr(parser.parseTerm("a + b")),
                    factory.fromExpr(parser.parseTerm("b + a")));
        }
    }

    @Test
    void associativeCommutativeIdentityIgnoresOrderAndGrouping() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue leftGrouped = factory.fromExpr(parser.parseTerm("(a + b) + c"));
            ExprValue rightGrouped = factory.fromExpr(parser.parseTerm("a + (b + c)"));
            ExprValue permuted = factory.fromExpr(parser.parseTerm("c + a + b"));

            assertSame(leftGrouped, rightGrouped);
            assertSame(leftGrouped, permuted);
            AssociativeCommutativeValue sum =
                    assertInstanceOf(AssociativeCommutativeValue.class, leftGrouped);
            assertEquals(3, sum.operandCount());
        }
    }

    @Test
    void multiplicityRemainsPartOfMathematicalValueIdentity() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue repeated = factory.fromExpr(parser.parseTerm("a + a + b"));
            ExprValue single = factory.fromExpr(parser.parseTerm("a + b"));

            assertNotEquals(repeated, single);
            AssociativeCommutativeValue sum =
                    assertInstanceOf(AssociativeCommutativeValue.class, repeated);
            assertEquals(2, sum.multiplicityOf(factory.variable("a")));
            assertEquals(3, sum.operandCount());
        }
    }

    @Test
    void nonCommutativeOperandRolesRemainOrdered() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            assertNotEquals(
                    factory.fromExpr(parser.parseTerm("a - b")),
                    factory.fromExpr(parser.parseTerm("b - a")));
            assertNotEquals(
                    factory.fromExpr(parser.parseTerm("a / b")),
                    factory.fromExpr(parser.parseTerm("b / a")));
        }
    }

    @Test
    void stableKeysCompareAcrossFactoryScopes() {
        Expr expression = parser.parseTerm("c + (a + b) + a");
        try (ExprValueFactory first = new ExprValueFactory();
                ExprValueFactory second = new ExprValueFactory()) {
            ExprValue firstValue = first.fromExpr(expression);
            ExprValue secondValue = second.fromExpr(expression);

            assertNotSame(firstValue, secondValue);
            assertEquals(firstValue, secondValue);
            assertEquals(firstValue.key(), secondValue.key());
            assertTrue(firstValue.key().encoded().startsWith(ValueKey.FORMAT_VERSION));
        }
    }

    @Test
    void valueKeySurvivesJsonPersistenceRoundTrip() throws Exception {
        ValueKey key;
        try (ExprValueFactory factory = new ExprValueFactory()) {
            key = factory.fromExpr(parser.parseTerm("(a + b) * (a + b)")).key();
        }

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(key);
        assertEquals(key, mapper.readValue(json, ValueKey.class));
    }

    @Test
    void projectionKeepsSeparateSyntaxOccurrencesPointingToOneValue() {
        BinaryExpr syntax = (BinaryExpr) parser.parseTerm("a + a");
        assertNotSame(syntax.left(), syntax.right());

        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValueProjection projection = factory.project(syntax);
            ExprValue left = projection.valueOf(syntax.left()).orElseThrow();
            ExprValue right = projection.valueOf(syntax.right()).orElseThrow();

            assertSame(left, right);
            assertEquals(3, projection.valuesBySyntaxIdentity().size());
        }
    }

    @Test
    void factoryCapacityAndLifecycleAreBounded() {
        ExprValueFactory factory = new ExprValueFactory(1);
        factory.variable("a");
        assertThrows(IllegalStateException.class, () -> factory.variable("b"));

        factory.clear();
        factory.variable("b");
        factory.close();
        assertThrows(IllegalStateException.class, factory::size);
    }

    @Test
    void orderedFactoryRoutesAcOperatorsThroughMultiplicityModel() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue a = factory.variable("a");
            ExprValue b = factory.variable("b");
            assertSame(factory.sum(List.of(a, b)), factory.ordered(ValueOperator.ADD, List.of(a, b)));
        }
    }
}
