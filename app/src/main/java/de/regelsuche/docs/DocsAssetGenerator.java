package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DocsAssetGenerator {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final MacroImpactReportGenerator macroImpactReportGenerator = new MacroImpactReportGenerator();
    private final DiscoveryBenchmarkScenarioLoader scenarioLoader = new DiscoveryBenchmarkScenarioLoader();
    private final SearchSpaceGallerySvgWriter svgWriter = new SearchSpaceGallerySvgWriter();

    public void generate(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            List<DiscoveryBenchmarkScenario> scenarios = scenarioLoader.loadAll("discovery-scenarios").stream()
                    .filter(scenario -> scenario.gallery().generateSvg())
                    .toList();
            List<MacroImpactReport> reports = scenarios.stream()
                    .map(macroImpactReportGenerator::generate)
                    .toList();
            writeGallery(outputDir.resolve("gallery"), reports);
            writeMacroImpact(outputDir.resolve("macro-impact.json"), reports);
            for (MacroImpactReport report : reports) {
                String evidenceFile = evidenceFileName(report.scenarioId());
                writeEvidence(outputDir.resolve(evidenceFile), report.evidence());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void writeGallery(Path gallery, List<MacroImpactReport> reports) throws IOException {
        Files.createDirectories(gallery);
        StringBuilder sections = new StringBuilder();
        for (MacroImpactReport report : reports) {
            String evidenceFile = evidenceFileName(report.scenarioId());
            String svgFile = scenarioAssetName(report.scenarioId()) + ".svg";
            sections.append("<section><h2>").append(escapeHtml(report.caseName())).append("</h2>")
                    .append("<p>Evidence: <a href=\"../").append(evidenceFile).append("\">").append(evidenceFile).append("</a></p>");
            if (eligibleForGallery(report.evidence())) {
                Files.writeString(gallery.resolve(svgFile), svgWriter.write(report.evidence(), "../" + evidenceFile), StandardCharsets.UTF_8);
                sections.append("<img src=\"").append(svgFile).append("\" alt=\"").append(escapeHtml(report.caseName())).append(" evidence graph\">");
            } else {
                sections.append("<p class=\"validation-failed\">Evidence validation failed; SVG was not generated.</p>");
            }
            sections.append("</section>\n");
        }
        String html = """
                <!doctype html>
                <html><head><title>Regelsuche Gallery</title></head>
                <body><h1>Regelsuche Discovery Gallery</h1>
                <h2>Discovery benchmark evidence</h2>
                ${sections}
                ${metrics}
                </body></html>
                """.replace("${sections}", sections.toString()).replace("${metrics}", metricsTable(reports));
        Files.writeString(gallery.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private boolean eligibleForGallery(DiscoveryBenchmarkEvidence evidence) {
        return evidence.success()
                && !evidence.bridgeRulesUsed().isEmpty()
                && (evidence.learnedMacros().isEmpty() || !evidence.reusedMacros().isEmpty())
                && evidence.edges().stream().noneMatch(edge -> edge.source().equals("hardcoded") || edge.source().equals("scenario"));
    }

    private String metricsTable(List<MacroImpactReport> reports) {
        StringBuilder rows = new StringBuilder();
        for (MacroImpactReport report : reports) {
            rows.append("<tr><td>").append(escapeHtml(report.caseName())).append("</td><td>")
                    .append(report.withoutMacroStates()).append("</td><td>")
                    .append(report.withMacroStates()).append("</td><td>")
                    .append(report.bridgeUsage()).append("</td><td>")
                    .append(report.macroReused()).append("</td><td>")
                    .append(report.pathsExplored()).append("</td></tr>\n");
        }
        return """
                <table><thead><tr><th>Scenario</th><th>States without macro</th><th>States with macro</th><th>Bridge rules used</th><th>Macro reused</th><th>Path count</th></tr></thead><tbody>
                ${rows}</tbody></table>
                """.replace("${rows}", rows.toString());
    }

    private void writeMacroImpact(Path path, List<MacroImpactReport> reports) throws IOException {
        List<Map<String, Object>> summaries = reports.stream()
                .map(this::summary)
                .toList();
        AtomicJsonFile.writeUtf8(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summaries));
    }

    private Map<String, Object> summary(MacroImpactReport report) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("scenarioId", report.scenarioId());
        summary.put("caseName", report.caseName());
        summary.put("inputExpression", report.inputExpression());
        summary.put("targetExpression", report.targetExpression());
        summary.put("withoutMacroStates", report.withoutMacroStates());
        summary.put("withMacroStates", report.withMacroStates());
        summary.put("pathsExplored", report.pathsExplored());
        summary.put("convergenceCount", report.convergenceCount());
        summary.put("bridgeUsage", report.bridgeUsage());
        summary.put("bridgeDiscovered", report.bridgeDiscovered());
        summary.put("macroReused", report.macroReused());
        summary.put("improvementFactor", report.improvementFactor());
        summary.put("evidence", evidenceFileName(report.scenarioId()));
        return summary;
    }

    private void writeEvidence(Path path, DiscoveryBenchmarkEvidence evidence) throws IOException {
        AtomicJsonFile.writeUtf8(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    private String evidenceFileName(String scenarioId) {
        return scenarioAssetName(scenarioId) + "-evidence.json";
    }

    private String scenarioAssetName(String scenarioId) {
        return scenarioId.toLowerCase(Locale.ROOT).replace("-factorization", "");
    }

    private String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
