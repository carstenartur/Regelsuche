package de.regelsuche.search.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTelemetryReplayTest {
    private static final Pattern SEQUENCE_PATTERN = Pattern.compile("\"sequence\":(\\d+)");

    @Test
    void ndjsonAndSummaryAreInternallyConsistent(@TempDir Path tempDir) throws Exception {
        Path ndjson = tempDir.resolve("search-events.ndjson");
        SearchTelemetrySummaryObserver summaryObserver = new SearchTelemetrySummaryObserver();
        try (NdjsonSearchObserver ndjsonObserver = new NdjsonSearchObserver(ndjson)) {
            SearchProblem problem = new SearchProblem(
                "x",
                new DeterministicTransformationEngine(),
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                new SearchHeuristic(2, 20, 2, 4, 80, 12)
            ).withObserver(CompositeSearchObserver.of(summaryObserver, ndjsonObserver));
            new BestFirstSearchStrategy().search(problem);
        }

        List<String> lines = Files.readAllLines(ndjson, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty());
        assertEquals(summaryObserver.summary().totalEvents(), lines.size());
        assertTrue(lines.getFirst().contains("\"type\":\"SEARCH_STARTED\""));
        assertTrue(lines.getLast().contains("\"type\":\"SEARCH_FINISHED\""));
        assertEquals(lines.stream().filter(line -> line.contains("\"type\":\"TRANSFORMATION_GENERATED\"")).count(),
            summaryObserver.summary().generatedTransformations());
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(i, parseSequence(lines.get(i)));
        }
    }

    @Test
    void ndjsonOutputIsDeterministicAcrossTwoRuns(@TempDir Path tempDir) throws Exception {
        List<String> run1 = runAndCollectNdjson(tempDir.resolve("run1.ndjson"));
        List<String> run2 = runAndCollectNdjson(tempDir.resolve("run2.ndjson"));

        assertEquals(run1.size(), run2.size(), "event count must be identical across runs");
        for (int i = 0; i < run1.size(); i++) {
            assertEquals(run1.get(i), run2.get(i),
                "event at index " + i + " must be byte-identical across runs");
        }
    }

    private List<String> runAndCollectNdjson(Path ndjson) throws Exception {
        try (NdjsonSearchObserver ndjsonObserver = new NdjsonSearchObserver(ndjson)) {
            SearchProblem problem = new SearchProblem(
                "x",
                new DeterministicTransformationEngine(),
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                new SearchHeuristic(2, 20, 2, 4, 80, 12)
            ).withObserver(ndjsonObserver);
            new BestFirstSearchStrategy().search(problem);
        }
        return Files.readAllLines(ndjson, StandardCharsets.UTF_8);
    }

    private int parseSequence(String line) {
        Matcher matcher = SEQUENCE_PATTERN.matcher(line);
        assertTrue(matcher.find(), "missing sequence in line: " + line);
        return Integer.parseInt(matcher.group(1));
    }

    private static final class DeterministicTransformationEngine implements TransformationEngine {
        @Override
        public List<Transformation> transform(String expression) {
            if (!"x".equals(expression)) {
                return List.of();
            }
            return List.of(
                new Transformation("rule_b", "b", RewriteKind.NORMALIZE, false, 0, true, "rule_b:b"),
                new Transformation("rule_a", "a", RewriteKind.NORMALIZE, false, 0, true, "rule_a:a")
            );
        }
    }
}
