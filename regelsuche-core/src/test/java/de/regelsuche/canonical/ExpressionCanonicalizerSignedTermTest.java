package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExpressionCanonicalizerSignedTermTest {
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void foldsSignsExposedByTermNormalizationInTheSamePass() {
        String expression =
            "f(x) - 2*a*(0-b) - 2*a*b";

        String canonical = canonicalizer.canonicalize(expression);

        assertEquals("f(x)", canonical);
        assertEquals(canonical, canonicalizer.canonicalize(canonical));
    }

    @Test
    void cancelsBrahmaguptaCrossTermsWithoutASecondPass() {
        String expression =
            "((a*c) + (0 - b*d))^2"
                + " - 2*(a*c)*(0 - b*d)"
                + " + ((a*d) + (b*c))^2"
                + " - 2*(a*d)*(b*c)";
        String expected =
            "(a*c - b*d)^2 + (a*d + b*c)^2";

        String canonical = canonicalizer.canonicalize(expression);

        assertEquals(canonicalizer.canonicalize(expected), canonical);
        assertEquals(canonical, canonicalizer.canonicalize(canonical));
    }
}
