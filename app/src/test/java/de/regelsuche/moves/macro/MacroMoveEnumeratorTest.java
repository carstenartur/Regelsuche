package de.regelsuche.moves.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MacroMoveEnumeratorTest {

    private final MacroMoveEnumerator enumerator = new MacroMoveEnumerator();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    private ReusableRule learnedMacro(String left, String right) {
        return new ReusableRule(
                "macro_hidden_binomial",
                left,
                right,
                List.of("A=sin(x)"),
                CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                RuleStatus.NEW,
                3,
                2.0,
                Instant.parse("2020-01-01T00:00:00Z"),
                "hash",
                null,
                1,
                2,
                List.of("path-1"),
                0.9,
                List.of());
    }

    @Test
    void learnedMacrosAppearAsLearnedMacroKind() {
        List<RewriteMove> moves = enumerator.enumerate(
                List.of(learnedMacro("sin(x)^2 + 2*sin(x) + 1", "(sin(x) + 1)^2")),
                "sin(x)^2 + 2*sin(x) + 1");
        assertEquals(1, moves.size());
        RewriteMove move = moves.getFirst();
        assertEquals(RewriteMoveKind.LEARNED_MACRO, move.kind());
        assertFalse(move.atomic());
        assertEquals("macro_hidden_binomial", move.macroId());
        assertEquals("path-1", move.learnedFromPathId());
    }

    @Test
    void macroMoveHasExpandedMovesOrPartialTag() {
        RewriteMove expandable = enumerator.enumerate(
                List.of(learnedMacro("sin(x)^2 + 2*sin(x) + 1", "(sin(x) + 1)^2")),
                "sin(x)^2 + 2*sin(x) + 1").getFirst();
        assertTrue(!expandable.expandedMoves().isEmpty(), "macro should expand into atomic moves");

        RewriteMove partial = enumerator.enumerate(
                List.of(learnedMacro("", "")), "sin(x)^2 + 2*sin(x) + 1").getFirst();
        assertTrue(partial.tags().contains("macro-expansion-partial"));
    }

    @Test
    void expandedAtomicMoveUsesCanonicalizedBeforeAndAfterExpressions() {
        RewriteMove macroMove = enumerator.enumerate(
                List.of(learnedMacro("sin(x)^2 + 2*sin(x) + 1", "(sin(x) + 1)^2")),
                "sin(x)^2 + 2*sin(x) + 1").getFirst();
        RewriteMove atomic = macroMove.expandedMoves().getFirst();
        assertEquals(canonicalizer.canonicalize(atomic.sourceExpression()), atomic.canonicalBefore());
        assertEquals(canonicalizer.canonicalize(atomic.targetExpression()), atomic.canonicalAfter());
    }

    @Test
    void macroMovesHaveDeterministicOrdinals() {
        List<ReusableRule> macros = List.of(
                learnedMacro("sin(x)^2 + 2*sin(x) + 1", "(sin(x) + 1)^2"));
        RewriteMove first = enumerator.enumerate(macros, "sin(x)^2 + 2*sin(x) + 1").getFirst();
        RewriteMove second = enumerator.enumerate(macros, "sin(x)^2 + 2*sin(x) + 1").getFirst();
        assertEquals(first.ordinal(), second.ordinal());
    }

    @Test
    void curatedMacroIsClassifiedAsCuratedMacro() {
        ReusableRule curated = new ReusableRule(
                "curated_binomial_rule",
                "a^2 + 2*a + 1",
                "(a + 1)^2",
                List.of(),
                CandidateProofStatus.FORMALLY_PROVED,
                RuleStatus.MATCHES_KNOWN_RULE,
                5,
                1.0,
                Instant.parse("2020-01-01T00:00:00Z"));
        RewriteMove move = enumerator.enumerate(List.of(curated), "a^2 + 2*a + 1").getFirst();
        assertEquals(RewriteMoveKind.CURATED_MACRO, move.kind());
    }
}
