package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleIndexBenchmarkTest {
    @Test
    void comparesNaiveRootAndMultiStageCandidateRetrieval() {
        List<RuleIndexBenchmark.Result> results = new RuleIndexBenchmark().growingRuleInventory(80);

        assertEquals(List.of("naive-scan", "root-symbol", "multi-stage"),
            results.stream().map(RuleIndexBenchmark.Result::strategy).toList());
        RuleIndexBenchmark.Result naive = results.get(0);
        RuleIndexBenchmark.Result root = results.get(1);
        RuleIndexBenchmark.Result multiStage = results.get(2);
        assertTrue(naive.candidateSetSize() > root.candidateSetSize());
        assertTrue(root.candidateSetSize() > multiStage.candidateSetSize());
        assertTrue(multiStage.rulesSkippedByIndex() > root.rulesSkippedByIndex());
        assertEquals(multiStage.candidateSetSize(), (int) multiStage.averageCandidateSetSize());
    }
}
