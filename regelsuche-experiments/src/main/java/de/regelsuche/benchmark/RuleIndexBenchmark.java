package de.regelsuche.benchmark;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.egraph.EGraph;
import de.regelsuche.egraph.EGraphPatternMatcher;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.index.CandidateBudget;
import de.regelsuche.search.index.CandidateSet;
import de.regelsuche.search.index.RootSymbolTermRuleIndex;
import de.regelsuche.search.index.SearchContext;
import de.regelsuche.search.index.TermRuleIndex;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.json.JsonWriter;
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
        return compareRuleIndexes(inventory, CONTEXT, CandidateBudget.unbounded());
    }

    public List<Result> macroHeavyDiscovery(int inventorySize, int macroBudget) {
        List<TermRuleIndex.IndexedMacroMove> inventory = macroHeavyInventory(inventorySize);
        return compareRuleIndexes(inventory, new SearchContext(
            "x ^ 2 + 14 * x + 49",
            TermRuleIndex.ProofStatusRank.VALIDATED_BY_EXAMPLES,
            "",
            false,
            true,
            java.util.Set.of()
        ), new CandidateBudget(0, macroBudget));
    }

    public Result growingEGraph(int expressionCount) {
        ExpressionParser parser = new ExpressionParser();
        EGraph graph = new EGraph();
        for (int i = 0; i < Math.max(0, expressionCount); i++) {
            graph.addExpression(parser.parseTerm("(x" + i + " + " + i + ")"));
            graph.addExpression(parser.parseTerm("(x" + i + " * " + (i + 1) + ")"));
            graph.addExpression(parser.parseTerm("sin(x" + i + ")"));
        }
        EGraphPatternMatcher matcher = new EGraphPatternMatcher(graph);
        PatternExpr addPattern = PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("L"), PatternExpr.var("R"));
        long started = System.nanoTime();
        int firstMatches = matcher.matchAll("add-pattern", addPattern, null).size();
        int secondMatches = matcher.matchAll("add-pattern", addPattern, null).size();
        long elapsedNanos = System.nanoTime() - started;
        EGraphPatternMatcher.MatcherStats stats = matcher.stats();
        return new Result(
            "growing-egraph-indexed",
            graph.classCount(),
            secondMatches,
            stats.candidateClassesSkipped(),
            secondMatches,
            stats.nodesScanned(),
            stats.matcherCacheHits(),
            elapsedNanos
        );
    }

    private List<Result> compareRuleIndexes(
        List<TermRuleIndex.IndexedMacroMove> inventory,
        SearchContext context,
        CandidateBudget budget
    ) {
        RootSymbolTermRuleIndex rootOnly = new RootSymbolTermRuleIndex(false);
        RootSymbolTermRuleIndex multiStage = new RootSymbolTermRuleIndex(true);
        for (TermRuleIndex.IndexedMacroMove rule : inventory) {
            rootOnly.addMacroMove(rule);
            multiStage.addMacroMove(rule);
        }
        return List.of(
            naiveScan(inventory, context, budget),
            indexed("root-symbol", rootOnly, context, budget),
            indexed("multi-stage", multiStage, context, budget)
        );
    }

    private Result naiveScan(List<TermRuleIndex.IndexedMacroMove> inventory, SearchContext context, CandidateBudget budget) {
        long started = System.nanoTime();
        List<TermRuleIndex.IndexedMacroMove> candidates = inventory.stream()
            .filter(rule -> rule.proofStatus().ordinal() >= context.minimumProofStatus().ordinal())
            .filter(rule -> context.domain().isBlank() || rule.domain().contains(context.domain()))
            .limit(budget.maxMacroMoves())
            .toList();
        long elapsedNanos = System.nanoTime() - started;
        return new Result("naive-scan", inventory.size(), candidates.size(), 0, candidates.size(), 0, 0, elapsedNanos);
    }

    private Result indexed(String name, RootSymbolTermRuleIndex index, SearchContext context, CandidateBudget budget) {
        long started = System.nanoTime();
        CandidateSet candidates = index.candidateSetForExpression(QUERY, context, budget);
        long elapsedNanos = System.nanoTime() - started;
        return new Result(
            name,
            candidates.metrics().rulesConsidered(),
            candidates.macroMoves().size(),
            candidates.metrics().rulesSkippedByIndex(),
            candidates.metrics().averageCandidateSetSize(),
            0,
            0,
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

    private static List<TermRuleIndex.IndexedMacroMove> macroHeavyInventory(int inventorySize) {
        List<TermRuleIndex.IndexedMacroMove> rules = new ArrayList<>();
        for (int i = 0; i < Math.max(0, inventorySize); i++) {
            rules.add(switch (i % 5) {
                case 0 -> rule(i, "(x + A" + i + ") ^ 2", "x ^ 2 + 2 * A" + i + " * x + A" + i + " ^ 2", "algebra");
                case 1 -> rule(i, "(x + A" + i + ") ^ 3", "x ^ 3 + A" + i, "algebra");
                case 2 -> rule(i, "sin(x) ^ 2", "1 - cos(x) ^ 2", "trig");
                case 3 -> rule(i, "(M" + i + " + N" + i + ") ^ 2", "M" + i + " ^ 2 + 2 * M" + i + " * N" + i, "matrix");
                default -> rule(i, "x + A" + i, "A" + i + " + x", "algebra");
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
        long rulesSkippedByIndex,
        double averageCandidateSetSize,
        long nodesScanned,
        long matcherCacheHits,
        long elapsedNanos
    ) {
        public String renderJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("schema", "regelsuche.rule-index-benchmark-result/v1");
            writer.property("strategy", strategy);
            writer.property("rulesConsidered", rulesConsidered);
            writer.property("candidateSetSize", candidateSetSize);
            writer.property("rulesSkippedByIndex", rulesSkippedByIndex);
            writer.property("averageCandidateSetSize", averageCandidateSetSize);
            writer.property("nodesScanned", nodesScanned);
            writer.property("matcherCacheHits", matcherCacheHits);
            writer.property("elapsedNanos", elapsedNanos);
            writer.endObject();
            return writer.toString();
        }

        public static String renderJsonArray(List<Result> results) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("schema", "regelsuche.rule-index-benchmark/v1");
            writer.array("results", array -> (results == null ? List.<Result>of() : results)
                .forEach(result -> array.objectValue(object -> {
                    object.property("strategy", result.strategy());
                    object.property("rulesConsidered", result.rulesConsidered());
                    object.property("candidateSetSize", result.candidateSetSize());
                    object.property("rulesSkippedByIndex", result.rulesSkippedByIndex());
                    object.property("averageCandidateSetSize", result.averageCandidateSetSize());
                    object.property("nodesScanned", result.nodesScanned());
                    object.property("matcherCacheHits", result.matcherCacheHits());
                    object.property("elapsedNanos", result.elapsedNanos());
                })));
            writer.endObject();
            return writer.toString();
        }
    }
}
