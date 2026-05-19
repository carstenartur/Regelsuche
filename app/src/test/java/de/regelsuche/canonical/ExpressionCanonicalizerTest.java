package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExpressionCanonicalizerTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void canonicalizesCommutativeExpressions() {
        assertEquals(canonicalizer.stableHash("a + b"), canonicalizer.stableHash("b + a"));
        assertEquals("x", canonicalizer.canonicalize("x*1"));
        assertEquals("x + y + z", canonicalizer.canonicalize("(x+y)+z"));
        assertEquals("x ^ 2", canonicalizer.canonicalize("x*x"));
    }
}
