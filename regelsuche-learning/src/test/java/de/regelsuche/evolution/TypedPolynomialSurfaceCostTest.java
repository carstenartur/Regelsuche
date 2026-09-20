package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class TypedPolynomialSurfaceCostTest {
    @Test void countsArithmeticWithoutSimplifying() {
        assertEquals(6L, TypedPolynomialSurfaceCost.evaluate(new ExpressionParser().parseTerm("(x+y)*(x-y)+y*y+2")).value());
    }
    @Test void chargesInspectionsForZeroCostLeaves() {
        var score = TypedPolynomialSurfaceCost.evaluate(new NumberExpr(103));
        assertEquals(0L, score.value());
        assertTrue(score.work() > 0);
    }
    @Test void chargesPrintedFractionAndSignButNotFiniteDecimal() {
        assertEquals(1L, TypedPolynomialSurfaceCost.evaluate(NumberExpr.exact("1/3")).value());
        assertEquals(2L, TypedPolynomialSurfaceCost.evaluate(NumberExpr.exact("-1/3")).value());
        assertEquals(0L, TypedPolynomialSurfaceCost.evaluate(NumberExpr.exact("1/2")).value());
        assertEquals(1L, TypedPolynomialSurfaceCost.evaluate(new NumberExpr(-5)).value());
    }
    @Test void rejectsUnsupportedFunctions() {
        assertThrows(IllegalArgumentException.class, () -> TypedPolynomialSurfaceCost.evaluate(new FunctionExpr("sin", java.util.List.of(new NumberExpr(3)))));
    }
}
