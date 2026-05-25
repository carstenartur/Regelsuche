package de.regelsuche.benchmark;

import de.regelsuche.json.JsonWriter;
import java.util.List;
import java.util.Locale;

/**
 * Renders {@link BenchmarkScenarioResult}s into the two artefacts
 * that the docs site ships:
 *
 * <ul>
 *   <li>a human-readable Markdown report ({@code docs/benchmark-report.md}),
 *       grouped by scenario with an "Ampel" column (OK / WARN / FAIL) per row;</li>
 *   <li>a structured JSON summary ({@code docs/assets/benchmark-summary.json})
 *       that downstream tooling (CI dashboard, regression tracker, …) can
 *       consume.</li>
 * </ul>
 *
 * <p>Both artefacts include the full quality-metrics set so the report is a
 * complete answer to "what does the system do well today?" without needing
 * to re-run the benchmarks.</p>
 */
public final class BenchmarkReportRenderer {

    public String renderMarkdown(List<BenchmarkScenarioResult> scenarios) {
        StringBuilder out = new StringBuilder();
        out.append("# Regelsuche – Benchmark-Qualitätsdashboard\n\n");
        out.append("Automatisch generiert von `./gradlew benchmarkReport`. Jede Zeile zeigt ")
            .append("neben den klassischen Suchmetriken auch Qualitätsmetriken: ob das erwartete ")
            .append("Ergebnis getroffen wurde, wie viele Zustände geprunet wurden, e-Graph-Größe, ")
            .append("Saturation-Einsparungen, ob eine gelernte Makroregel beteiligt war und ob das ")
            .append("Export-Bundle für diese Zeile gültig ist.\n\n");
        out.append("**Ampel:** ✅ OK · ⚠️ WARN · ❌ FAIL\n\n");
        for (BenchmarkScenarioResult scenario : scenarios) {
            out.append("## ").append(scenario.name()).append("\n\n");
            out.append("| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Sat-Sparung | Lernregel | Proof | Export |\n");
            out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (SearchBenchmarkResult row : scenario.results()) {
                out.append("| ").append(escape(row.strategyName()))
                    .append(" | ").append(escape(row.expression()))
                    .append(" | ").append(ampel(row.qualityLabel()))
                    .append(" | ").append(row.found() ? "✓" : "✗")
                    .append(" | ").append(formatExpected(row.expectedResultMatched()))
                    .append(" | ").append(row.elapsedMillis())
                    .append(" | ").append(row.visitedStates())
                    .append(" | ").append(row.prunedStates())
                    .append(" | ").append(row.eGraphClasses())
                    .append(" | ").append(row.eGraphNodes())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", row.saturationSavings()))
                    .append(" | ").append(row.learnedRuleUsed() ? "✓" : "–")
                    .append(" | ").append(row.proofStatus().name())
                    .append(" | ").append(row.exportBundleValid() ? "✓" : "✗")
                    .append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    public String renderJsonSummary(List<BenchmarkScenarioResult> scenarios) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.property("schema", "regelsuche.benchmark-summary/v1");
        w.array("scenarios", scenariosArr -> scenarios.forEach(scenario ->
            scenariosArr.objectValue(s -> {
                s.property("name", scenario.name());
                s.array("rows", rows -> scenario.results().forEach(row ->
                    rows.objectValue(r -> {
                        r.property("strategy", row.strategyName());
                        r.property("expression", row.expression());
                        r.property("found", row.found());
                        if (row.expectedResultMatched() != null) {
                            r.property("expectedResultMatched", row.expectedResultMatched());
                        } else {
                            r.nullProperty("expectedResultMatched");
                        }
                        r.property("elapsedMillis", row.elapsedMillis());
                        r.property("visitedStates", row.visitedStates());
                        r.property("prunedStates", row.prunedStates());
                        r.property("eGraphClasses", row.eGraphClasses());
                        r.property("eGraphNodes", row.eGraphNodes());
                        r.property("saturationSavings", row.saturationSavings());
                        r.property("learnedRuleUsed", row.learnedRuleUsed());
                        r.property("proofStatus", row.proofStatus().name());
                        r.property("exportBundleValid", row.exportBundleValid());
                        r.property("quality", row.qualityLabel());
                    })));
            })));
        w.endObject();
        return w.toString();
    }

    private static String ampel(String label) {
        return switch (label) {
            case "OK" -> "✅";
            case "WARN" -> "⚠️";
            case "FAIL" -> "❌";
            default -> label;
        };
    }

    private static String formatExpected(Boolean matched) {
        if (matched == null) return "—";
        return matched ? "✓" : "✗";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
