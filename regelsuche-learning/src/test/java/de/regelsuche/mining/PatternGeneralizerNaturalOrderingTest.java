package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternGeneralizerNaturalOrderingTest {
    private final PatternGeneralizer generalizer = new PatternGeneralizer();

    @Test
    void ordersCommutativeTermsByNumericValueAcrossDigitWidths() {
        String normalized = new AstNormalizer()
            .normalize("8 * x + 10 * x")
            .canonicalString();

        assertEquals("8*x + 10*x", normalized);
    }

    @Test
    void generalizesSimplifiedProductionConvergenceAcrossDigitWidths() {
        List<SuccessfulTransformationPath> paths = List.of(
            path("x * 3 + x * 5", "8 * x"),
            path("a * 4 + a * 6", "10 * a"),
            path("b * 6 + b * 8", "14 * b"),
            path("c * 8 + c * 10", "18 * c"),
            path("d * 10 + d * 12", "22 * d"),
            path("e * 12 + e * 14", "26 * e"),
            path("y * 3 + y * 5", "8 * y"),
            path("f * 5 + f * 7", "12 * f"),
            path("g * 11 + g * 13", "24 * g"),
            path("h * 17 + h * 19", "36 * h"),
            path("i * 29 + i * 31", "60 * i"),
            path("j * 41 + j * 43", "84 * j"));

        var result = generalizer.generalize(paths);

        assertTrue(result.isPresent(), "shared B=A+2 relation must generalize");
        GeneralizedPattern pattern = result.orElseThrow();
        assertFalse(pattern.parameterRelations().isEmpty());
        assertTrue(pattern.leftPattern().contains("+"), pattern.leftPattern());
        assertTrue(pattern.leftPattern().contains("*"), pattern.leftPattern());
        assertTrue(pattern.rightPattern().contains("*"), pattern.rightPattern());
    }

    private static SuccessfulTransformationPath path(String left, String right) {
        return new SuccessfulTransformationPath(
            "natural-order-" + left.hashCode(),
            left,
            right,
            List.of(left, right),
            List.of("ast_factor_common_left", "ast_canonical_normalize"),
            new ExpressionScore(left.length() + 10, 0, 0, 0, 0),
            new ExpressionScore(right.length(), 0, 0, 0, 0),
            true,
            "production-like-convergence",
            Map.of("source", "UNTARGETED_SEARCH"));
    }
}
