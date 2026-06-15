package de.regelsuche.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ide.RuleInspectionDto.PositionResult;
import de.regelsuche.ide.RuleInspectionDto.RuleMatch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RuleInspectionService}.
 *
 * <p>Verifies the four concrete acceptance criteria from issue #106:
 * <ol>
 *   <li>TreePosition selectable – positions are grouped by pathKey.</li>
 *   <li>Matches per position visible – each position lists its matches.</li>
 *   <li>Bindings visible – each match exposes its parameters as bindings.</li>
 *   <li>Rewrite Preview per position – at least some matches have a non-empty
 *       {@code rewriteAfter}.</li>
 * </ol>
 */
class RuleInspectionServiceTest {

    private final RuleInspectionService service = new RuleInspectionService();

    // ── TreePosition selectable ─────────────────────────────────────────────

    @Test
    void returnsRootPositionForSimpleExpression() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        assertNotNull(dto);
        assertEquals("x^2 + 6*x + 5", dto.expression());
        assertFalse(dto.positions().stream().anyMatch(PositionResult::selected),
                "selection should remain client-driven unless selectedPathKey is requested");
        assertTrue(
                dto.positions().stream().anyMatch(p -> "root".equals(p.pathKey())),
                "expected a root position: " + dto.positions().stream().map(PositionResult::pathKey).toList());
    }

    @Test
    void marksRequestedSelectedPath() {
        RuleInspectionDto dto = service.inspect("sin(x^2 + 6*x + 5)", "000");
        assertTrue(dto.positions().stream().anyMatch(p -> "000".equals(p.pathKey()) && p.selected()),
                "requested path should be marked selected");
        assertFalse(dto.positions().stream()
                .filter(p -> !"000".equals(p.pathKey()))
                .anyMatch(PositionResult::selected), "only requested path should be selected");
    }

    @Test
    void returnsNonRootPositionForNestedExpression() {
        // sin(x^2 + 6*x + 5) – the inner quadratic is at a non-root position
        RuleInspectionDto dto = service.inspect("sin(x^2 + 6*x + 5)");
        assertTrue(
                dto.positions().stream().anyMatch(p -> !p.pathKey().equals("root")),
                "expected non-root positions: " + dto.positions().stream().map(PositionResult::pathKey).toList());
    }

    @Test
    void positionHasCorrectSubtreeText() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        PositionResult root = dto.positions().stream()
                .filter(p -> "root".equals(p.pathKey()))
                .findFirst()
                .orElseThrow();
        assertNotNull(root.subtree());
        assertFalse(root.subtree().isBlank());
    }

    // ── Matches per position visible ────────────────────────────────────────

    @Test
    void rootPositionHasMatchesForQuadratic() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        PositionResult root = dto.positions().stream()
                .filter(p -> "root".equals(p.pathKey()))
                .findFirst()
                .orElseThrow();
        assertFalse(root.matches().isEmpty(),
                "expected rule matches at root: " + root);
    }

    @Test
    void matchKindIsPopulated() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        assertTrue(
                dto.positions().stream()
                        .flatMap(p -> p.matches().stream())
                        .allMatch(m -> m.kind() != null && !m.kind().isBlank()),
                "every match must have a kind");
    }

    @Test
    void matchIdIsPopulatedForEveryMatch() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        assertTrue(dto.positions().stream()
                        .flatMap(p -> p.matches().stream())
                        .allMatch(m -> m.matchId() != null && !m.matchId().isBlank()),
                "every match must have a stable matchId");
    }

    @Test
    void completeSquareMatchFoundInsideNestedExpression() {
        RuleInspectionDto dto = service.inspect("sin(x^2 + 6*x + 5)");
        boolean foundCompleteSquareNonRoot = dto.positions().stream()
                .filter(p -> !p.pathKey().equals("root"))
                .flatMap(p -> p.matches().stream())
                .anyMatch(m -> "COMPLETE_SQUARE".equals(m.kind()));
        assertTrue(foundCompleteSquareNonRoot,
                "COMPLETE_SQUARE should appear at a non-root position inside sin(…)");
    }

    // ── Bindings visible ────────────────────────────────────────────────────

    @Test
    void bindingsAreNotNullForAnyMatch() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        dto.positions().stream()
                .flatMap(p -> p.matches().stream())
                .forEach(m -> assertNotNull(m.bindings(), "bindings must not be null for " + m));
    }

    @Test
    void completeSquareMatchHasBindings() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        List<RuleMatch> csMatches = dto.positions().stream()
                .flatMap(p -> p.matches().stream())
                .filter(m -> "COMPLETE_SQUARE".equals(m.kind()))
                .toList();
        assertFalse(csMatches.isEmpty(), "expected COMPLETE_SQUARE matches");
        assertTrue(csMatches.stream().anyMatch(m -> !m.bindings().isEmpty()),
                "expected at least one COMPLETE_SQUARE match with bindings");
    }

    @Test
    void completeSquareShiftAndResidueAreGroupedIntoSingleLogicalMatch() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        assertTrue(dto.positions().stream()
                        .flatMap(p -> p.matches().stream())
                        .filter(m -> "COMPLETE_SQUARE".equals(m.kind()))
                        .map(m -> m.bindings().stream().map(binding -> binding.name()).collect(java.util.stream.Collectors.toSet()))
                        .anyMatch(names -> names.containsAll(Set.of("shift", "residue"))),
                "expected one COMPLETE_SQUARE logical match containing both shift and residue bindings");
    }

    // ── Rewrite Preview per position ────────────────────────────────────────

    @Test
    void rewriteBeforeEqualsSubtreeText() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        dto.positions().stream()
                .flatMap(p -> {
                    String text = p.subtree();
                    return p.matches().stream().filter(m -> !m.rewriteBefore().isBlank())
                            .map(m -> new Object[]{text, m.rewriteBefore()});
                })
                .forEach(pair -> assertEquals(pair[0], pair[1],
                        "rewriteBefore should match the subtree text"));
    }

    @Test
    void atLeastOneMatchHasRewritePreview() {
        RuleInspectionDto dto = service.inspect("x^2 + 6*x + 5");
        boolean hasPreview = dto.positions().stream()
                .flatMap(p -> p.matches().stream())
                .anyMatch(m -> m.rewriteAfter() != null && !m.rewriteAfter().isBlank());
        assertTrue(hasPreview, "at least one match should have a non-empty rewriteAfter");
    }

    @Test
    void completeSquareMatchIncludesSubtreeAndFullExpressionAfter() {
        RuleInspectionDto dto = service.inspect("sin(x^2 + 6*x + 5)");
        RuleMatch match = dto.positions().stream()
                .filter(p -> "000".equals(p.pathKey()))
                .flatMap(p -> p.matches().stream())
                .filter(m -> "COMPLETE_SQUARE".equals(m.kind()))
                .findFirst()
                .orElseThrow();

        assertEquals("x ^ 2 + 6 * x + 5", match.subtreeBefore());
        assertEquals("(x + 3) ^ 2 - 4", match.subtreeAfter());
        assertEquals("sin((x + 3) ^ 2 - 4)", match.expressionAfter());
        assertTrue(match.applicable(), "complete-square preview should be applicable");
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    void returnsEmptyPositionsForUnparseableInput() {
        RuleInspectionDto dto = service.inspect("(((");
        assertTrue(dto.positions().isEmpty());
    }

    @Test
    void returnsEmptyPositionsForBlankInput() {
        RuleInspectionDto dto = service.inspect("  ");
        assertTrue(dto.positions().isEmpty());
    }

    @Test
    void isNullSafeForNullInput() {
        RuleInspectionDto dto = service.inspect(null);
        assertNotNull(dto);
        assertTrue(dto.positions().isEmpty());
    }
}
