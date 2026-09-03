package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Effect;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Source-language controls; a rounded AST must never certify an exact effect. */
@Timeout(10)
class ExactResidualSourceProvenanceTest {
    private final ExactPolynomialResidualComposer composer =
        new ExactPolynomialResidualComposer();
    private final ExactResidualPolynomialArithmetic arithmetic =
        new ExactResidualPolynomialArithmetic();

    @Test
    void keepsAdjacentIntegersAboveBinary64PrecisionDistinct() {
        String lower = "9007199254740992*x";
        String higher = "9007199254740993*x";
        assertNotEquals(arithmetic.parse(lower), arithmetic.parse(higher));
        assertTrue(composer.additiveComponents(higher).getFirst()
            .expression().contains("9007199254740993"));
        assertThrows(IllegalArgumentException.class, () -> composer.effect(
            "rounded-integer", composer.additiveComponents(lower),
            higher, higher, List.of(), List.of("untrusted"), List.of("move")));
    }

    @Test
    void doesNotRoundDecimalCoefficientsOrExponents() {
        assertNotEquals(arithmetic.parse("0.10000000000000001*x"),
            arithmetic.parse("0.1*x"));
        assertEquals(arithmetic.parse("0.1*x"), arithmetic.parse("x/10"));
        assertThrows(IllegalArgumentException.class, () -> composer.effect(
            "rounded-decimal", composer.additiveComponents("0.1*x"),
            "0.10000000000000001*x", "0.10000000000000001*x",
            List.of(), List.of("untrusted"), List.of("move")));
        assertThrows(IllegalArgumentException.class, () ->
            composer.additiveComponents("x^2.00000000000000001"));
        assertEquals(arithmetic.parse("x^2.0"), arithmetic.parse("x*x"));
    }

    @Test
    void matchesStructuredSubtreesUsingExactNumericProvenance() {
        String source = "9007199254740992*x + x";
        assertThrows(IllegalArgumentException.class, () -> composer.effect(
            "rounded-subtree", composer.additiveComponents(source), source,
            "9007199254740993*x", List.of(),
            List.of("untrusted"), List.of("move")));
    }

    @Test
    void rationalResidualsSurviveExactReparsingDuringComposition() {
        String source = "x/3 + y/3";
        var components = composer.additiveComponents(source);
        Effect first = composer.effect("first", List.of(components.get(0)),
            "(x/3 + 1/3) - 1/3", "x/3 + 1/3",
            List.of(), List.of("add-subtract"), List.of("first-move"));
        Effect second = composer.effect("second", List.of(components.get(1)),
            "(y/3 - 1/3) + 1/3", "y/3 - 1/3",
            List.of(), List.of("add-subtract"), List.of("second-move"));
        assertEquals("-1/3", first.residualNormalForm());
        assertEquals("1/3", second.residualNormalForm());
        var candidates = composer.compose(source, components,
            List.of(first, second), 2, 8);
        assertEquals(1, candidates.size());
        assertEquals(arithmetic.parse(source),
            arithmetic.parse(candidates.getFirst().candidateExpression()));
        assertEquals("0", candidates.getFirst().combinedResidualNormalForm());
    }

    @Test
    void supportsLargeExactCoefficientsInACompletedComposition() {
        String source = "9007199254740993*x + y";
        var components = composer.additiveComponents(source);
        Effect first = composer.effect("first", List.of(components.get(0)),
            "(9007199254740993*x + 1) - 1", "9007199254740993*x + 1",
            List.of(), List.of("add-subtract"), List.of("first-move"));
        Effect second = composer.effect("second", List.of(components.get(1)),
            "(y - 1) + 1", "y - 1",
            List.of(), List.of("add-subtract"), List.of("second-move"));
        var candidates = composer.compose(source, components,
            List.of(first, second), 2, 8);
        assertEquals(1, candidates.size());
        assertTrue(candidates.getFirst().candidateExpression()
            .contains("9007199254740993"));
        assertEquals(arithmetic.parse(source),
            arithmetic.parse(candidates.getFirst().candidateExpression()));
    }

    @Test
    void rejectsUndefinedOrUnsupportedAlgebraInsteadOfIgnoringIt() {
        for (String expression : List.of("x/x", "x/0", "sin(x)",
                "x^-1", "x^33", "x^(1+1)")) {
            assertThrows(IllegalArgumentException.class,
                () -> arithmetic.parse(expression), expression);
        }
        assertEquals(arithmetic.parse("-x"), arithmetic.parse("0-x"));
        assertEquals(arithmetic.parse("x/(-3)"), arithmetic.parse("-x/3"));
    }

    @Test
    void boundsInputRecursionAndPolynomialExpansion() {
        assertThrows(IllegalArgumentException.class,
            () -> arithmetic.parse("(".repeat(257) + "x" + ")".repeat(257)));
        assertThrows(IllegalArgumentException.class,
            () -> arithmetic.parse("x".repeat(16_385)));
        assertThrows(IllegalArgumentException.class,
            () -> arithmetic.parse("(x+y+z+w)^32"));
        assertEquals(arithmetic.parse("(x+y)^2"),
            arithmetic.parse("x^2+2*x*y+y^2"));
    }
}
