package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the benchmark report and JSON summary contain the new
 * quality metrics defined by PR #16 follow-up §2.
 */
class BenchmarkReportRendererTest {

    @Test
    void benchmarkReportContainsQualityMetrics() {
        BenchmarkSuite.BenchmarkSuiteResult scenario = new BenchmarkSuite.BenchmarkSuiteResult(
            "test-scenario",
            List.of(new SearchBenchmarkResult(
                "best-first", "x + 0",
                /* exploredStates */ 3,
                /* bestImprovement */ 1,
                /* shortestImprovingDepth */ 1,
                /* expandedSteps */ 1,
                /* distinctRules */ 1,
                /* elapsedMillis */ 5L,
                de.regelsuche.mining.CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                /* expectedResultMatched */ Boolean.TRUE,
                /* prunedStates */ 2,
                /* eGraphClasses */ 10,
                /* eGraphNodes */ 20,
                /* saturationSavings */ 0.5,
                /* learnedRuleUsed */ true,
                /* exportBundleValid */ true
            ))
        );
        BenchmarkReportRenderer renderer = new BenchmarkReportRenderer();
        String md = renderer.renderMarkdown(List.of(scenario));
        assertNotNull(md);
        // The dashboard column header set must be present.
        assertTrue(md.contains("Erw. getroffen"),
            "report must include the expectedResultMatched column header");
        assertTrue(md.contains("e-Klassen") && md.contains("e-Knoten"),
            "report must include the e-graph size columns");
        assertTrue(md.contains("Sat-Sparung"),
            "report must include the saturation-savings column");
        assertTrue(md.contains("Lernregel"),
            "report must include the learned-rule indicator");
        // Per-row values must be rendered.
        assertTrue(md.contains("best-first"), "row must include strategy name");
        assertTrue(md.contains("✓") && md.contains("✅"),
            "OK rows must surface the success markers");
    }

    @Test
    void benchmarkSummaryJsonIsGenerated() {
        BenchmarkSuite.BenchmarkSuiteResult scenario = new BenchmarkSuite.BenchmarkSuiteResult(
            "test-scenario",
            List.of(new SearchBenchmarkResult(
                "beam", "x + 0",
                3, 1, 1, 1, 1, 7L,
                de.regelsuche.mining.CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                Boolean.FALSE, 0, 0, 0, 0.0, false, false
            ))
        );
        String json = new BenchmarkReportRenderer().renderJsonSummary(List.of(scenario));
        assertTrue(json.contains("\"schema\""),
            "summary must declare its schema for downstream tooling");
        assertTrue(json.contains("\"expectedResultMatched\""),
            "summary must include expectedResultMatched");
        assertTrue(json.contains("\"visitedStates\""),
            "summary must include visitedStates");
        assertTrue(json.contains("\"prunedStates\""),
            "summary must include prunedStates");
        assertTrue(json.contains("\"eGraphClasses\"") && json.contains("\"eGraphNodes\""),
            "summary must include e-graph metrics");
        assertTrue(json.contains("\"saturationSavings\""),
            "summary must include saturationSavings");
        assertTrue(json.contains("\"learnedRuleUsed\""),
            "summary must include learnedRuleUsed");
        assertTrue(json.contains("\"exportBundleValid\""),
            "summary must include exportBundleValid");
        // The FAIL row above (not found OR expectedResultMatched=false) should
        // surface as a FAIL quality label.
        assertTrue(json.contains("\"quality\":\"FAIL\""),
            "summary must surface a FAIL quality label for the false-match row");
    }
}
