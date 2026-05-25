package de.regelsuche.benchmark;

import de.regelsuche.search.index.CandidateBudget;
import de.regelsuche.search.index.CandidateSet;
import de.regelsuche.search.index.RootSymbolTermRuleIndex;
import de.regelsuche.search.index.SearchContext;
import de.regelsuche.search.index.TermRuleIndex;
import java.util.ArrayList;
import java.util.List;

/** Deterministic benchmark harness for comparing rule-candidate retrieval strategies. */
public final class RuleIndexBenchmark {
    private static final String QUERY = "(x + 7) ^ 2";
    private static final SearchContext CONTEXT = new SearchContext(
        "x ^ 2 + 14 * x + 49",
        TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES,
        "algebra",
        false,
        true,
        java.util.Set.of()
    );

    public List<Result> growingRuleInventory(int inventorySize) {
        List<TermRuleIndex.IndexedMacroMove> inventory = macroInventory(inventorySize);
        RootSymbolTermRuleIndex rootOnly = new RootSymbolTermRuleIndex(false);
        RootSymbolTermRuleIndex multiStage = new RootSymbolTermRuleIndex(true);
        for (TermRuleIndex.IndexedMacroMove rule : inventory) {
            rootOnly.addMacroMove(rule);
            multiStage.addMacroMove(rule);
        }
        return List.of(
            naiveScan(inventory),
            indexed("root-symbol", rootOnly),
            indexed("multi-stage", multiStage)
        );
    }

    private Result naiveScan(List<TermRuleIndex.IndexedMacroMove> inventory) {
        long started = System.nanoTime();
        List<TermRuleIndex.IndexedMacroMove> candidates = inventory.stream()
            .filter(rule -> rule.proofStatus().ordinal() >= CONTEXT.minimumProofStatus().ordinal())
            .filter(rule -> CONTEXT.domain().isBlank() || rule.domain().contains(CONTEXT.domain()))
            .toList();
        long elapsedNanos = System.nanoTime() - started;
        return new Result("naive-scan", inventory.size(), candidates.size(), 0, 0, elapsedNanos);
    }

    private Result indexed(String name, RootSymbolTermRuleIndex index) {
        long started = System.nanoTime();
        CandidateSet candidates = index.candidateSetForExpression(QUERY, CONTEXT, CandidateBudget.unbounded());
        long elapsedNanos = System.nanoTime() - started;
        return new Result(
            name,
            candidates.metrics().rulesConsidered(),
            candidates.macroMoves().size(),
            candidates.metrics().rulesSkippedByIndex(),
            candidates.metrics().averageCandidateSetSize(),
            elapsedNanos
        );
    }

    private static List<TermRuleIndex.IndexedMacroMove> macroInventory(int inventorySize) {
        List<TermRuleIndex.IndexedMacroMove> rules = new ArrayList<>();
        for (int i = 0; i < Math.max(0, inventorySize); i++) {
            rules.add(switch (i % 4) {
                case 0 -> rule(i, "(x + A" + i + ") ^ 2", "x ^ 2 + 2 * A" + i + " * x + A" + i + " ^ 2", "algebra");
                case 1 -> rule(i, "sin(x" + i + ") ^ 2", "1 - cos(x" + i + ") ^ 2", "trig");
                case 2 -> rule(i, "(x + A" + i + ") ^ 3", "x ^ 3 + A" + i, "algebra");
                default -> rule(i, "x" + i + " * 1", "x" + i, "algebra");
            });
        }
        return List.copyOf(rules);
    }

    private static TermRuleIndex.IndexedMacroMove rule(int index, String left, String right, String domain) {
        return new TermRuleIndex.IndexedMacroMove(
            "macro-" + index,
            left,
            right,
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES,
            domain
        );
    }

    public record Result(
        String strategy,
        int rulesConsidered,
        int candidateSetSize,
        int rulesSkippedByIndex,
        double averageCandidateSetSize,
        long elapsedNanos
    ) {
    }
}
