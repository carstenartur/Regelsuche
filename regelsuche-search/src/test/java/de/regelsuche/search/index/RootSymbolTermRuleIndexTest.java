package de.regelsuche.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
