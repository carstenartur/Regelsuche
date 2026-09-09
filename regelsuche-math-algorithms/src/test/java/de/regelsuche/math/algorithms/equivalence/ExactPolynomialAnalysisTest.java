package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExactPolynomialAnalysisTest {
    private final ExactPolynomialAnalysis analysis = new ExactPolynomialAnalysis();

    @Test
    void exclusionIsSemanticAndIndependentOfVariablePermutation() {
        assertEquals(analysis.alphaIdentity("(x+2*y)^2"), analysis.alphaIdentity("4*a*a+4*a*z+z*z"));
        assertNotEquals(analysis.alphaIdentity("x+y"), analysis.alphaIdentity("x+x"));
        assertEquals(analysis.alphaIdentity("9007199254740992*x"), analysis.alphaIdentity("9007199254740992*y"));
        assertNotEquals(analysis.alphaIdentity("9007199254740992*x"), analysis.alphaIdentity("9007199254740993*x"));
        assertEquals(analysis.alphaIdentity("0.1*x+0.2*x"), analysis.alphaIdentity("3*y/10"));
    }

    @Test
    void equivalenceRetainsActualSymbolsAndRejectsUnsupportedInputs() {
        analysis.requireEquivalent("(x+y)^2", "x*x+2*x*y+y*y");
        assertThrows(IllegalArgumentException.class, () -> analysis.requireEquivalent("x+y", "x+z"));
        assertThrows(IllegalArgumentException.class, () -> analysis.alphaIdentity("sin(x)"));
        assertThrows(IllegalArgumentException.class, () -> analysis.alphaIdentity("x/y"));
        assertThrows(IllegalArgumentException.class, () -> analysis.alphaIdentity("a+b+c+d+e"));
        assertThrows(IllegalArgumentException.class, () -> analysis.alphaIdentity("x^999999999"));
    }

    @Test
    void optionalWorkReceiptCountsActualAlgebraAndRenamingWithoutChangingMathematicalResults() {
        var work = new java.util.concurrent.atomic.AtomicLong();
        var measured = new ExactPolynomialAnalysis(work::addAndGet);
        measured.requireEquivalent("(x+y)^2", "x*x+2*x*y+y*y");
        long proofWork = work.get(); assertTrue(proofWork > 2);
        assertEquals(analysis.alphaIdentity("(x+y)^2"), measured.alphaIdentity("(x+y)^2"));
        assertTrue(work.get() > proofWork);
        long beforeFailure = work.get();
        assertThrows(IllegalArgumentException.class, () -> measured.requireEquivalent("x+1", "x+2"));
        assertTrue(work.get() > beforeFailure, "unsuccessful exact work remains observed");
    }
}
