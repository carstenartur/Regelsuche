package de.regelsuche.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ExprValueFactoryTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void acValuesIgnoreGroupingAndOrderInsideOneScope() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue leftGrouped = factory.fromExpr(parser.parseTerm("(a + b) + c"));
            ExprValue rightGrouped = factory.fromExpr(parser.parseTerm("a + (b + c)"));
            ExprValue permuted = factory.fromExpr(parser.parseTerm("c + a + b"));

            assertSame(leftGrouped, rightGrouped);
            assertSame(leftGrouped, permuted);
            assertEquals(leftGrouped.key(), permuted.key());
        }
    }

    @Test
    void acValuesPreserveMultiplicity() {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValue repeated = factory.fromExpr(parser.parseTerm("a + a + b"));
            AssociativeCommutativeValue sum =
                    assertInstanceOf(AssociativeCommutativeValue.class, repeated);

            assertEquals(3, sum.operandCount());
            assertEquals(2, sum.multiplicityOf(factory.variable("a")));
            assertNotEquals(repeated, factory.fromExpr(parser.parseTerm("a + b")));
        }
    }

    @Test
    void orderedOperatorsRetainOperandRoles() {
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
    void referenceIdentityIsScopedButKeysSurviveScopes() {
        Expr expression = parser.parseTerm("c + (a + b) + a");
        try (ExprValueFactory first = new ExprValueFactory();
                ExprValueFactory second = new ExprValueFactory()) {
            assertSame(first.variable("a"), first.variable("a"));

            ExprValue firstValue = first.fromExpr(expression);
            ExprValue secondValue = second.fromExpr(expression);
            assertNotSame(firstValue, secondValue);
            assertEquals(firstValue, secondValue);
            assertEquals(firstValue.key(), secondValue.key());
            assertTrue(firstValue.key().encoded().startsWith(ValueKey.FORMAT_VERSION));
            assertEquals(firstValue.key(), new ValueKey(firstValue.key().encoded()));
        }
    }

    @Test
    void factoryScopeHasAHardCapacityAndExplicitLifecycle() {
        ExprValueFactory bounded = new ExprValueFactory(1);
        bounded.variable("a");
        assertThrows(IllegalStateException.class, () -> bounded.variable("b"));
        bounded.close();
        assertThrows(IllegalStateException.class, bounded::size);
    }

    @Test
    void projectionKeepsOccurrencesSeparateWhileSharingValues() {
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
    void sharedValuesExposeNoOccurrenceOrSourceMetadata() {
        for (Class<?> type : new Class<?>[] {
                ExprValue.class,
                VariableValue.class,
                NumberValue.class,
                OrderedValue.class,
                AssociativeCommutativeValue.class
        }) {
            assertFalse(Arrays.stream(type.getMethods())
                    .map(Method::getName)
                    .anyMatch(name -> name.equals("parent")
                            || name.equals("source")
                            || name.equals("position")
                            || name.equals("metadata")),
                    () -> type.getSimpleName() + " must remain occurrence-independent");
        }
    }
}
