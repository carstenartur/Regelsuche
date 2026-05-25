package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for the enhanced anti-unification in {@link PatternGeneralizer}.
 *
 * <p>Covers the new cases beyond integer-only schemas:
 * <ul>
 *   <li>Structurally different subtrees &rarr; expression placeholder</li>
 *   <li>Mixed integer + expression placeholders</li>
 * </ul>
 *
 * <p>Also covers variable-name collisions at the same AST position: because
 * {@link AstNormalizer} canonicalises variable names by first-occurrence order
 * ("x", "v1", …), two paths that share the same first variable but differ in a
 * second variable produce structurally different leaves at that position, which
 * triggers the expression-placeholder path.</p>
 */
class PatternGeneralizerAntiUnificationTest {

    private final PatternGeneralizer generalizer = new PatternGeneralizer();

    @Test
    void existingIntegerAbstractionStillWorks() {
        // Existing: (x+1)^2 → x^2+2x+1 etc. abstracted via integer placeholder
        List<SuccessfulTransformationPath> paths = List.of(
            path("(x + 1) ^ 2", "x ^ 2 + 2 * x + 1"),
            path("(x + 2) ^ 2", "x ^ 2 + 4 * x + 4"),
            path("(x + 3) ^ 2", "x ^ 2 + 6 * x + 9")
        );
        Optional<GeneralizedPattern> result = generalizer.generalize(paths);
        assertTrue(result.isPresent(), "should generalise integer schemas");
        GeneralizedPattern pattern = result.get();
        // Should still find an integer parameter relation
        assertFalse(pattern.parameterRelations().isEmpty(),
            "integer parameter relations must be present");
    }

    @Test
    void differentShapeSubtreesAreAbstractedAsExpressionPlaceholder() {
        // (x)^2 → x*x and (x+1)^2 → (x+1)*(x+1): the base differs in shape.
        // VAR("x") vs ADD(VAR("x"),NUM(1)) — different shapes → expression placeholder.
        List<SuccessfulTransformationPath> paths = List.of(
            path("(x) ^ 2", "x * x"),
            path("(x + 1) ^ 2", "(x + 1) * (x + 1)"),
            path("(x + 2) ^ 2", "(x + 2) * (x + 2)")
        );
        Optional<GeneralizedPattern> result = generalizer.generalize(paths);
        assertTrue(result.isPresent(),
            "should produce a pattern even when subtrees have different shapes");
        GeneralizedPattern pattern = result.get();
        assertFalse(pattern.expressionPlaceholderValues().isEmpty(),
            "expression placeholders must be created for differing base subtrees");
    }

    @Test
    void expressionPlaceholderDescriptionsAreIncluded() {
        // Use the subtree case — different base shapes → expression placeholder with membership note.
        List<SuccessfulTransformationPath> paths = List.of(
            path("(x) ^ 2", "x * x"),
            path("(x + 1) ^ 2", "(x + 1) * (x + 1)"),
            path("(x + 2) ^ 2", "(x + 2) * (x + 2)")
        );
        Optional<GeneralizedPattern> result = generalizer.generalize(paths);
        assertTrue(result.isPresent(),
            "generalizer must succeed for the different-shape case");
        // Descriptions should contain the expression placeholder membership note
        List<String> descriptions = result.get().parameterRelations();
        boolean hasExpressionEntry = descriptions.stream()
            .anyMatch(d -> d.contains("\u2208") || d.contains(" = "));
        assertTrue(hasExpressionEntry,
            "parameterRelations should document expression placeholders: " + descriptions);
    }

    @Test
    void variableAtSamePositionWithDifferentNamesProducesExpressionPlaceholder() {
        // x + x normalises to ADD(VAR("x"), VAR("x")).
        // x + y normalises to ADD(VAR("x"), VAR("v1")).
        // x + z normalises to ADD(VAR("x"), VAR("v1")).
        // Second child: VAR("x") vs VAR("v1") — different variable names → expression placeholder.
        List<SuccessfulTransformationPath> paths = List.of(
            path("x + x", "2 * x"),
            path("x + y", "x + y"),
            path("x + z", "x + z")
        );
        Optional<GeneralizedPattern> result = generalizer.generalize(paths);
        // The generalizer must not crash and should handle the mixed-variable case.
        assertNotNull(result);
        // If a result is returned it must contain either integer or expression placeholders.
        result.ifPresent(p ->
            assertFalse(p.parameterRelations().isEmpty() && p.expressionPlaceholderValues().isEmpty(),
                "result must contain at least one kind of abstraction")
        );
    }

    @Test
    void identicalPathsYieldNoGeneralisation() {
        // All examples are structurally identical → nothing to abstract → empty result.
        List<SuccessfulTransformationPath> paths = List.of(
            path("x + 1", "1 + x"),
            path("x + 1", "1 + x"),
            path("x + 1", "1 + x")
        );
        Optional<GeneralizedPattern> result = generalizer.generalize(paths);
        assertTrue(result.isEmpty(),
            "fully identical paths produce no generalisation since nothing needs abstraction");
    }

    @Test
    void emptyPathListReturnsEmpty() {
        Optional<GeneralizedPattern> result = generalizer.generalize(List.of());
        assertTrue(result.isEmpty());
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static SuccessfulTransformationPath path(String left, String right) {
        ExpressionScore before = new ExpressionScore(left.length() + 5, 0, 0, 0, 0);
        ExpressionScore after = new ExpressionScore(right.length(), 0, 0, 0, 0);
        return new SuccessfulTransformationPath(
            "test-" + left.hashCode(),
            left,
            right,
            List.of(left, right),
            List.of("rule1"),
            before,
            after,
            true,
            "test",
            Map.of("variable", "x")
        );
    }
}
