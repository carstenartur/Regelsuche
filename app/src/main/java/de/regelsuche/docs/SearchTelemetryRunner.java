package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.telemetry.CompositeSearchObserver;
import de.regelsuche.search.telemetry.NdjsonSearchObserver;
import de.regelsuche.search.telemetry.SearchTimelineDataCollector;
import de.regelsuche.search.telemetry.SearchTelemetrySummary;
import de.regelsuche.search.telemetry.SearchTelemetrySummaryObserver;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Records runtime search telemetry artifacts for selected discovery scenarios. */
public final class SearchTelemetryRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final List<String> SCENARIO_IDS = List.of("complete-square-factorization");

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final TelemetryTimelineSvgWriter svgWriter = new TelemetryTimelineSvgWriter();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new SearchTelemetryRunner().writeReport(repoRoot.resolve("app/build/reports/search-telemetry"));
    }

    public TelemetryReport writeReport(Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            List<ScenarioTelemetryResult> results = new ArrayList<>();
            for (DiscoveryBenchmarkScenario scenario : sortedScenarios()) {
                Path scenarioDirectory = outputDirectory.resolve(slugFor(scenario.id()));
                Files.createDirectories(scenarioDirectory);
                ScenarioTelemetryResult result = recordScenario(scenario, scenarioDirectory);
                results.add(result);
            }
            TelemetryReport report = new TelemetryReport("search-telemetry", results);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("search-telemetry-index.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("search-telemetry-summary.md"),
                renderAggregateSummary(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<DiscoveryBenchmarkScenario> sortedScenarios() {
        return loader.loadAll("discovery-scenarios").stream()
            .filter(scenario -> SCENARIO_IDS.contains(scenario.id()))
            .sorted(Comparator.comparingInt(scenario -> SCENARIO_IDS.indexOf(scenario.id())))
            .toList();
    }

    private ScenarioTelemetryResult recordScenario(DiscoveryBenchmarkScenario scenario, Path scenarioDirectory) throws IOException {
        String targetCanonicalHash = canonicalizer.stableHash(normalizeExpression(scenario.targetExpression()));
        SearchTelemetrySummaryObserver summaryObserver = new SearchTelemetrySummaryObserver(targetCanonicalHash);
        SearchTimelineDataCollector timelineCollector = new SearchTimelineDataCollector();
        DiscoveryBenchmarkEvidence evidence;
        try (NdjsonSearchObserver ndjson = new NdjsonSearchObserver(scenarioDirectory.resolve("search-events.ndjson"))) {
            evidence = executor.execute(scenario,
                CompositeSearchObserver.of(summaryObserver, ndjson, timelineCollector));
        }
        SearchTelemetrySummary summary = summaryObserver.summary();
        ScenarioTelemetryResult result = new ScenarioTelemetryResult(
            scenario.id(),
            scenario.inputExpression(),
            scenario.targetExpression(),
            evidence.success(),
            evidence.failureReason(),
            summary,
            evidence.withoutMacroRun().path(),
            evidence.withoutMacroRun().appliedRuleIds()
        );
        AtomicJsonFile.writeUtf8(
            scenarioDirectory.resolve("search-telemetry-summary.json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)
        );
        Files.writeString(
            scenarioDirectory.resolve("search-telemetry-summary.md"),
            renderScenarioSummary(result),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            scenarioDirectory.resolve("search-telemetry-replay.html"),
            renderReplayHtml(result),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            scenarioDirectory.resolve("search-telemetry-timeline.svg"),
            svgWriter.render(scenario.id(), timelineCollector.points()),
            StandardCharsets.UTF_8
        );
        return result;
    }

    private String renderAggregateSummary(TelemetryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Search telemetry summary\n\n");
        out.append("| Scenario | Success | Events | Visited | Generated | Enqueued | Max depth | Max frontier |\n");
        out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ScenarioTelemetryResult result : report.results()) {
            SearchTelemetrySummary summary = result.summary();
            out.append("| `").append(escapeMd(result.scenarioId())).append("` | ")
                .append(result.success() ? "yes" : "no").append(" | ")
                .append(summary.totalEvents()).append(" | ")
                .append(summary.visitedStates()).append(" | ")
                .append(summary.generatedTransformations()).append(" | ")
                .append(summary.enqueuedStates()).append(" | ")
                .append(summary.maxDepthReached()).append(" | ")
                .append(summary.maxFrontierSize()).append(" |\n");
        }
        out.append('\n');
        for (ScenarioTelemetryResult result : report.results()) {
            String slug = slugFor(result.scenarioId());
            out.append("## ").append(escapeMd(result.scenarioId())).append("\n\n");
            out.append("- [search-events.ndjson](").append(slug).append("/search-events.ndjson)\n");
            out.append("- [search-telemetry-summary.json](").append(slug).append("/search-telemetry-summary.json)\n");
            out.append("- [search-telemetry-summary.md](").append(slug).append("/search-telemetry-summary.md)\n");
            out.append("- [search-telemetry-replay.html](").append(slug).append("/search-telemetry-replay.html)\n");
            out.append("- [search-telemetry-timeline.svg](").append(slug).append("/search-telemetry-timeline.svg)\n\n");
        }
        return out.toString();
    }

    private String renderScenarioSummary(ScenarioTelemetryResult result) {
        SearchTelemetrySummary summary = result.summary();
        StringBuilder out = new StringBuilder();
        out.append("# Runtime search telemetry\n\n");
        out.append("- Scenario: `").append(escapeMd(result.scenarioId())).append("`\n");
        out.append("- Input: `").append(escapeMd(result.inputExpression())).append("`\n");
        out.append("- Target: `").append(escapeMd(result.targetExpression())).append("`\n");
        out.append("- Success: ").append(result.success() ? "yes" : "no").append('\n');
        if (!result.failureReason().isBlank()) {
            out.append("- Failure reason: ").append(escapeMd(result.failureReason())).append('\n');
        }
        out.append('\n');
        out.append("## Counters\n\n");
        out.append("| Metric | Value |\n");
        out.append("| --- | ---: |\n");
        out.append("| Total events | ").append(summary.totalEvents()).append(" |\n");
        out.append("| Visited states | ").append(summary.visitedStates()).append(" |\n");
        out.append("| Generated transformations | ").append(summary.generatedTransformations()).append(" |\n");
        out.append("| Enqueued states | ").append(summary.enqueuedStates()).append(" |\n");
        out.append("| Pruned duplicates | ").append(summary.prunedDuplicates()).append(" |\n");
        out.append("| Pruned transpositions | ").append(summary.prunedTranspositions()).append(" |\n");
        out.append("| Pruned by depth | ").append(summary.prunedByDepth()).append(" |\n");
        out.append("| Pruned by budget | ").append(summary.prunedByBudget()).append(" |\n");
        out.append("| Max depth reached | ").append(summary.maxDepthReached()).append(" |\n");
        out.append("| Max frontier size | ").append(summary.maxFrontierSize()).append(" |\n");
        out.append("| Target-near states | ").append(summary.targetNearStates()).append(" |\n");
        out.append("| Target reached | ").append(summary.targetReached() ? "yes" : "no").append(" |\n");
        out.append('\n');

        out.append("## Depth histogram\n\n");
        out.append("| Depth | Count |\n");
        out.append("| ---: | ---: |\n");
        summary.depthHistogram().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> out.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n"));
        out.append('\n');

        out.append("## Rule usage during search\n\n");
        if (summary.ruleUsage().isEmpty()) {
            out.append("_No rule usage recorded._\n\n");
        } else {
            out.append("| Rule | Count |\n");
            out.append("| --- | ---: |\n");
            summary.ruleUsage().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> out.append("| `").append(escapeMd(entry.getKey())).append("` | ")
                    .append(entry.getValue()).append(" |\n"));
            out.append('\n');
        }

        out.append("## Selected path (if found)\n\n");
        if (!result.selectedPath().isEmpty()) {
            out.append("- Expressions: ").append(result.selectedPath().size()).append('\n');
            out.append("- Applied rules: ").append(result.appliedRuleIds().size()).append('\n');
        } else {
            out.append("_No selected target-reaching path found._\n");
        }
        out.append('\n');
        out.append("Replay in [search-telemetry-replay.html](search-telemetry-replay.html)." +
            " Timeline SVG: [search-telemetry-timeline.svg](search-telemetry-timeline.svg).\n");
        out.append('\n');
        String powerSlug = slugFor(result.scenarioId());
        out.append("## See also\n\n");
        out.append("- [Search-space power report](../../search-space-power/").append(powerSlug)
            .append("/search-space-power.md) — structural metrics derived from the evidence graph.\n");
        return out.toString();
    }

    private String renderReplayHtml(ScenarioTelemetryResult result) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Search telemetry replay - %s</title>
              <style>
                body { font-family: system-ui, sans-serif; max-width: 960px; margin: 2rem auto; padding: 0 1rem; }
                button { margin-right: .5rem; }
                pre { background: #111; color: #f5f5f5; padding: .75rem; overflow-x: auto; border-radius: 6px; }
              </style>
            </head>
            <body>
              <h1>Search telemetry replay</h1>
              <p><strong>Scenario:</strong> %s</p>
              <p><strong>Input:</strong> <code>%s</code></p>
              <p><strong>Target:</strong> <code>%s</code></p>
              <div>
                <button id="play">Play</button>
                <button id="pause">Pause</button>
                <button id="step">Step</button>
                <label>Speed
                  <select id="speed">
                    <option value="1000">1x</option>
                    <option value="500">2x</option>
                    <option value="250">4x</option>
                    <option value="100">10x</option>
                  </select>
                </label>
              </div>
              <p id="status">Loading events…</p>
              <pre id="event"></pre>
              <script>
                const status = document.getElementById('status');
                const eventView = document.getElementById('event');
                const speed = document.getElementById('speed');
                let events = [];
                let index = -1;
                let timer = null;

                function render() {
                  if (index < 0 || index >= events.length) {
                    eventView.textContent = '';
                    return;
                  }
                  const e = events[index];
                  status.textContent = `Event ${index + 1}/${events.length}: ${e.type} | frontier=${e.frontierSize} | visited=${e.visitedCount}`;
                  eventView.textContent = JSON.stringify(e, null, 2);
                }

                function step() {
                  if (index < events.length - 1) {
                    index += 1;
                    render();
                  }
                }

                function play() {
                  if (timer !== null) return;
                  timer = setInterval(() => {
                    if (index >= events.length - 1) {
                      pause();
                      return;
                    }
                    step();
                  }, Number(speed.value));
                }

                function pause() {
                  if (timer !== null) {
                    clearInterval(timer);
                    timer = null;
                  }
                }

                document.getElementById('play').addEventListener('click', play);
                document.getElementById('pause').addEventListener('click', pause);
                document.getElementById('step').addEventListener('click', step);
                speed.addEventListener('change', () => {
                  if (timer !== null) {
                    pause();
                    play();
                  }
                });

                fetch('search-events.ndjson')
                  .then(response => response.text())
                  .then(text => {
                    events = text.trim().split('\\n').filter(Boolean).map(line => JSON.parse(line));
                    status.textContent = `Loaded ${events.length} events`;
                    index = -1;
                    render();
                  })
                  .catch(error => {
                    status.textContent = `Failed to load NDJSON: ${error}. If opening from disk (file://), serve via a local web server instead, e.g. python3 -m http.server 8080`;
                  });
              </script>
            </body>
            </html>
            """.formatted(
            escapeHtml(result.scenarioId()),
            escapeHtml(result.scenarioId()),
            escapeHtml(result.inputExpression()),
            escapeHtml(result.targetExpression())
        );
    }

    private String normalizeExpression(String expression) {
        return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
    }

    private String escapeMd(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private String slugFor(String scenarioId) {
        return scenarioId == null ? "unknown"
            : scenarioId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    }

    public record TelemetryReport(String id, List<ScenarioTelemetryResult> results) {
        public TelemetryReport {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record ScenarioTelemetryResult(
        String scenarioId,
        String inputExpression,
        String targetExpression,
        boolean success,
        String failureReason,
        SearchTelemetrySummary summary,
        List<String> selectedPath,
        List<String> appliedRuleIds
    ) {
        public ScenarioTelemetryResult {
            failureReason = failureReason == null ? "" : failureReason;
            selectedPath = selectedPath == null ? List.of() : List.copyOf(selectedPath);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
        }
    }
}
