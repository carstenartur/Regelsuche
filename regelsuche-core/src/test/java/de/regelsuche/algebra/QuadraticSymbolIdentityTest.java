package de.regelsuche.algebra;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.symbol.SymbolId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuadraticSymbolIdentityTest {
    private static final String SYMBOL = new SymbolId(
        UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), 19).identifier();

    @ParameterizedTest
    @ValueSource(strings = {"x2y", "velocity9axis", "rsym_0123456789abcdef0123456789abcdef_19"})
    void wholeIdentifiersAreNeverSplitByImplicitMultiplication(String variable) {
        String source = variable + "^2+2*" + variable + "+1";
        assertEquals(source, QuadraticAnalyzer.canonicalInput(source));
        var quadratic = QuadraticAnalyzer.analyzePolynomial(source).orElseThrow();
        assertEquals(variable, quadratic.variable());
        assertEquals(1, quadratic.quadratic());
        assertEquals(2, quadratic.linear());
        assertEquals(1, quadratic.constant());
    }

    @Test void shorthandCoefficientsStillInsertMultiplicationBeforeAWholeIdentifier() {
        assertEquals("2*x2y^2+3*x2y+1", QuadraticAnalyzer.canonicalInput("2x2y^2 + 3x2y + 1"));
        assertEquals("2*" + SYMBOL + "^2+3*" + SYMBOL + "+1",
            QuadraticAnalyzer.canonicalInput("2" + SYMBOL + "^2+3" + SYMBOL + "+1"));
        assertEquals("(x+1)*(x-1)", QuadraticAnalyzer.canonicalInput("(x+1)(x-1)"));
        assertEquals("2*x^2+3*x+1", QuadraticAnalyzer.canonicalInput("2x**2+3x+1"));
    }

    @Test void squareAndDifferenceRecognitionRetainTheExactSymbol() {
        var square = QuadraticAnalyzer.analyzePerfectSquare("(" + SYMBOL + "+3)^2").orElseThrow();
        assertEquals(SYMBOL, square.variable());
        assertEquals(6, square.linear());
        assertEquals(9, square.constant());
        var difference = QuadraticAnalyzer.analyzeDifferenceProduct("(" + SYMBOL + "+3)*(" + SYMBOL + "-3)").orElseThrow();
        assertEquals(SYMBOL, difference.variable());
        assertEquals(-9, difference.constant());
        var completion = QuadraticAnalyzer.analyzeCompletion("(" + SYMBOL + "+3)^2-4").orElseThrow();
        assertEquals(SYMBOL, completion.variable());
        assertEquals(5, completion.constant());
    }

    @Test void differentSymbolIdsMustNotBecomeTheSameQuadraticVariable() {
        String other = new SymbolId(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), 20).identifier();
        assertTrue(QuadraticAnalyzer.analyzePolynomial(SYMBOL + "^2+2*" + other + "+1").isEmpty());
        assertTrue(QuadraticAnalyzer.analyzeDifferenceProduct("(" + SYMBOL + "+3)*(" + other + "-3)").isEmpty());
        assertEquals(SYMBOL + "+" + other, QuadraticAnalyzer.canonicalInput(SYMBOL + "+" + other));
    }
}
