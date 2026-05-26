package de.regelsuche.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class RootSymbolTermRuleIndexTest {
    @Test
    void rootSymbolIndexFiltersAtomicAndMacroRules() {
        RootSymbolTermRuleIndex index = new RootSymbolTermRuleIndex();
        RewriteRule plusRule = stubRule("plus-zero");
        index.addAtomicRule("^", plusRule);
        index.addMacroMove(rule("macro_algebra_square", "(x + A) ^ 2", "x ^ 2 + 2 * A * x + A ^ 2",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra"));
        index.addMacroMove(rule("macro_trig_identity", "sin(x) ^ 2 + cos(x) ^ 2", "1",
            TermRuleIndex.ProofStatusRank.OBSERVED, "trig"));

        TermRuleIndex.QueryResult result = index.query(
            "(x + 3) ^ 2",
            new TermRuleIndex.Query("x ^ 2 + 2 * 3 * x + 3 ^ 2",
                TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra", true, true)
        );

        assertEquals(List.of(plusRule), result.atomicRules());
        assertEquals(1, result.macroMoves().size());
        assertEquals("macro_algebra_square", result.macroMoves().getFirst().id());
        assertTrue(result.metrics().rulesSkippedByIndex() >= 1);
        assertEquals(2, result.metrics().rulesMatched());
    }

    @Test
    void multiStageIndexSkipsStructuralMismatchesWithinSameRoot() {
        RootSymbolTermRuleIndex index = new RootSymbolTermRuleIndex();
        index.addMacroMove(rule("square", "(x + A) ^ 2", "x ^ 2 + 2 * A * x + A ^ 2",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra"));
        index.addMacroMove(rule("power-of-sine", "sin(x) ^ 2", "1 - cos(x) ^ 2",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "trig"));
        index.addMacroMove(rule("cube", "(x + A) ^ 3", "x ^ 3 + 3 * A * x ^ 2 + 3 * A ^ 2 * x + A ^ 3",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra"));

        CandidateSet candidates = index.candidatesFor(
            new ExpressionParser().parseTerm("(x + 3) ^ 2"),
            new SearchContext("", TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "", false, true, java.util.Set.of()),
            CandidateBudget.unbounded()
        );

        assertEquals(List.of("square"), candidates.macroMoves().stream().map(TermRuleIndex.IndexedMacroMove::id).toList());
        assertEquals(1, candidates.metrics().rulesSkippedByFeatureVector());
        assertEquals(1, candidates.metrics().rulesSkippedByDiscriminationTree());
        assertEquals(1, candidates.metrics().rulesMatched());
    }

    @Test
    void candidateBudgetLimitsResultSetAndTracksBudgetSkips() {
        RootSymbolTermRuleIndex index = new RootSymbolTermRuleIndex();
        index.addMacroMove(rule("macro-a", "x + A", "A + x",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra"));
        index.addMacroMove(rule("macro-b", "x + B", "B + x",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra"));

        CandidateSet candidates = index.candidateSetForExpression(
            "x + 1",
            new SearchContext("", TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "", false, true, java.util.Set.of()),
            new CandidateBudget(0, 1)
        );

        assertEquals(1, candidates.macroMoves().size());
        assertEquals(1, candidates.metrics().rulesSkippedByBudget());
    }

    @Test
    void updateRemoveAndRebuildMaintainIncrementalIndexes() {
        RootSymbolTermRuleIndex index = new RootSymbolTermRuleIndex();
        RewriteRule plusRule = stubRule("plus-zero");
        index.addAtomicRule("+", plusRule);
        index.addMacroMove(rule("commute", "x + A", "A + x",
            TermRuleIndex.ProofStatusRank.OBSERVED, "algebra"));

        assertTrue(index.updateMacroMove(rule("commute", "x * A", "A * x",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra")));
        assertTrue(index.removeAtomicRule("+", plusRule));

        CandidateSet plusCandidates = index.candidateSetForExpression(
            "x + 1",
            new SearchContext("", TermRuleIndex.ProofStatusRank.OBSERVED, "", true, true, java.util.Set.of()),
            CandidateBudget.unbounded()
        );
        assertTrue(plusCandidates.atomicRules().isEmpty());
        assertTrue(plusCandidates.macroMoves().isEmpty());

        CandidateSet multiplyCandidates = index.candidateSetForExpression(
            "x * 2",
            new SearchContext("", TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "", false, true, java.util.Set.of()),
            CandidateBudget.unbounded()
        );
        assertEquals(List.of("commute"), multiplyCandidates.macroMoves().stream()
            .map(TermRuleIndex.IndexedMacroMove::id).toList());

        index.rebuild(
            List.of(new TermRuleIndex.AtomicRuleEntry("^", plusRule)),
            List.of(rule("square", "(x + A) ^ 2", "x ^ 2 + 2 * A * x + A ^ 2",
                TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "algebra"))
        );

        CandidateSet rebuilt = index.candidateSetForExpression(
            "(x + 3) ^ 2",
            new SearchContext("", TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES, "", true, true, java.util.Set.of()),
            CandidateBudget.unbounded()
        );
        assertEquals(List.of(plusRule), rebuilt.atomicRules());
        assertEquals(List.of("square"), rebuilt.macroMoves().stream()
            .map(TermRuleIndex.IndexedMacroMove::id).toList());
    }

    private static TermRuleIndex.IndexedMacroMove rule(
        String id,
        String left,
        String right,
        TermRuleIndex.ProofStatusRank status,
        String domain
    ) {
        return new TermRuleIndex.IndexedMacroMove(id, left, right, status, domain);
    }

    private static RewriteRule stubRule(String id) {
        return new RewriteRule() {
            @Override public String id() { return id; }
            @Override public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
            @Override public boolean mayIncreaseComplexity() { return false; }
            @Override public int estimatedCostDelta() { return -1; }
            @Override public boolean isEquivalencePreservingByConstruction() { return true; }
            @Override public boolean matches(de.regelsuche.ast.Expr subtree) { return false; }
            @Override public de.regelsuche.ast.Expr apply(de.regelsuche.ast.Expr subtree) { return subtree; }
        };
    }
}
