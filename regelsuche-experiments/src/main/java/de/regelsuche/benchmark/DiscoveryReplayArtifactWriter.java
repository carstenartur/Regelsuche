package de.regelsuche.benchmark;

import de.regelsuche.json.JsonWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Writes deterministic replay/report artefacts for CI discovery runs. */
public final class DiscoveryReplayArtifactWriter {

    public ArtifactBundle write(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        Path outputDirectory
    ) {
        try {
            Files.createDirectories(outputDirectory);
            Path json = outputDirectory.resolve("discovery-report.json");
            Path html = outputDirectory.resolve("discovery-report.html");
            Path markdown = outputDirectory.resolve("discovery-report.md");
            Path replayExport = outputDirectory.resolve("discovery-replay.json");
            Path screenshot = outputDirectory.resolve("discovery-summary.png");
            Path gif = outputDirectory.resolve("discovery-replay.gif");
            Files.writeString(json, report.renderDeterministicJson());
            Files.writeString(html, renderHtml(report));
            Files.writeString(markdown, renderMarkdown(report));
            Files.writeString(replayExport, renderReplayExport(report));
            writeScreenshot(screenshot, report);
            writeReplayGif(gif, report);
            return new ArtifactBundle(json, html, markdown, replayExport, screenshot, gif);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write discovery replay artefacts to " + outputDirectory, exception);
        }
    }

    public String renderHtml(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">");
        out.append("<title>Regelsuche Discovery Report</title>");
        out.append("<style>")
            .append("body{font-family:system-ui,sans-serif;background:#f6f8fb;color:#1f2937;margin:0;padding:2rem}")
            .append("h1,h2{margin:.2rem 0 1rem}table{width:100%;border-collapse:collapse;background:#fff}")
            .append("td,th{border:1px solid #d8dee8;padding:.5rem;vertical-align:top;text-align:left}")
            .append("th{background:#eef2ff}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:.75rem;margin:1rem 0}")
            .append(".card{background:#fff;border:1px solid #d8dee8;border-radius:.75rem;padding:.9rem}")
            .append(".metric{font-size:1.5rem;font-weight:700}.muted{color:#667085;font-size:.92rem}")
            .append(".replay-step{margin:.2rem 0}.ok{color:#137333}.fail{color:#a50e0e}")
            .append(".pill{display:inline-block;background:#eef2ff;border:1px solid #c7d2fe;border-radius:999px;padding:.08rem .45rem;margin:.08rem;font-size:.82rem}")
            .append("code{background:#f3f4f6;border-radius:.25rem;padding:.08rem .25rem}ul,ol{margin:.2rem 0 .2rem 1.2rem;padding:0}")
            .append("</style>");
        out.append("</head><body><h1>Regelsuche Discovery Report</h1>");
        out.append("<p class=\"muted\">Deterministischer Replay-/Discovery-Bericht für wissenschaftliche Reproduktionsläufe.</p>");
        out.append("<div class=\"cards\">")
            .append(metricCard("Seeds", String.valueOf(report.metrics().processedSeeds())))
            .append(metricCard("Erfolgreich", String.valueOf(report.metrics().successfulSeeds())))
            .append(metricCard("Hypothesen", String.valueOf(report.metrics().hypotheses())))
            .append(metricCard("Gegenbeispiele", String.valueOf(report.metrics().counterexamples())))
            .append(metricCard("Runtime Σ", report.metrics().accumulatedRuntimeMillis() + " ms"))
            .append(metricCard("Speicher Σ", report.metrics().accumulatedMemoryBytes() + " B"))
            .append("</div>");
        out.append("<p class=\"muted\">Seeds: ").append(report.metrics().processedSeeds())
            .append(" · erfolgreich: ").append(report.metrics().successfulSeeds())
            .append(" · Hypothesen: ").append(report.metrics().hypotheses())
            .append(" · Gegenbeispiele: ").append(report.metrics().counterexamples())
            .append("</p><table><thead><tr><th>Seed</th><th>Status</th><th>Discovery</th><th>Replay</th><th>Summary</th></tr></thead><tbody>");
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            out.append("<tr><td>").append(escape(row.seed().stableKey())).append("</td><td>")
                .append(row.success() ? "<span class=\"ok\">OK</span>" : "<span class=\"fail\">FAIL</span>")
                .append("<div class=\"muted\">Kategorie: ").append(escape(row.seed().category())).append("</div>")
                .append("</td><td>")
                .append(renderHtmlDiscoveryDetails(row))
                .append("</td><td><ol>");
            for (String step : row.replayPath()) {
                out.append("<li class=\"replay-step\"><code>").append(escape(step)).append("</code></li>");
            }
            if (row.replayPath().isEmpty()) {
                out.append("<li class=\"replay-step muted\">Kein Replay vorhanden</li>");
            }
            out.append("</ol></td><td>").append(escape(row.summary()))
                .append("<div class=\"muted\">Laufzeit: ").append(row.elapsedMillis())
                .append(" ms · Speicher: ").append(row.memoryBytes()).append(" B</div></td></tr>");
        }
        out.append("</tbody></table></body></html>");
        return out.toString();
    }

    public String renderMarkdown(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Regelsuche Discovery Report\n\n");
        out.append("- Seeds: ").append(report.metrics().processedSeeds()).append('\n');
        out.append("- Erfolgreich: ").append(report.metrics().successfulSeeds()).append('\n');
        out.append("- Hypothesen: ").append(report.metrics().hypotheses()).append('\n');
        out.append("- Gegenbeispiele: ").append(report.metrics().counterexamples()).append('\n');
        out.append("- Laufzeit Σ: ").append(report.metrics().accumulatedRuntimeMillis()).append(" ms\n");
        out.append("- Speicher Σ: ").append(report.metrics().accumulatedMemoryBytes()).append(" B\n\n");
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            out.append("## ").append(row.seed().stableKey()).append("\n\n");
            out.append("- Status: ").append(row.success() ? "OK" : "FAIL").append('\n');
            out.append("- Kategorie: ").append(row.seed().category()).append('\n');
            out.append("- Annahmen: ").append(joinOrDash(row.seed().assumptions())).append('\n');
            out.append("- Hypothesen: ").append(joinOrDash(row.hypotheses())).append('\n');
            out.append("- Gegenbeispiele: ").append(joinOrDash(row.counterexamples())).append('\n');
            out.append("- Laufzeit: ").append(row.elapsedMillis()).append(" ms\n");
            out.append("- Speicher: ").append(row.memoryBytes()).append(" B\n");
            out.append("- Summary: ").append(row.summary()).append("\n\n");
            out.append("### Replay\n\n");
            if (row.replayPath().isEmpty()) {
                out.append("- Kein Replay vorhanden\n\n");
            } else {
                for (int i = 0; i < row.replayPath().size(); i++) {
                    out.append(i + 1).append(". `").append(row.replayPath().get(i)).append("`\n");
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    public String renderReplayExport(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.discovery-replay/v1");
        writer.array("replays", arr -> report.rows().forEach(row -> arr.objectValue(object -> {
            object.property("seedId", row.seed().id());
            object.property("seedStableKey", row.seed().stableKey());
            object.property("category", row.seed().category());
            object.array("assumptions", assumptions -> row.seed().assumptions().forEach(assumptions::value));
            object.array("hypotheses", hypotheses -> row.hypotheses().forEach(hypotheses::value));
            object.array("counterexamples", counterexamples -> row.counterexamples().forEach(counterexamples::value));
            object.array("replayPath", replay -> row.replayPath().forEach(replay::value));
        })));
        writer.endObject();
        return writer.toString();
    }

    private void writeScreenshot(Path path, DeterministicDiscoveryExperimentRunner.DiscoveryReport report) throws IOException {
        if (!ImageIO.write(renderFrame(report, 0), "png", path.toFile())) {
            throw new IOException("No ImageIO writer for png");
        }
    }

    private void writeReplayGif(Path path, DeterministicDiscoveryExperimentRunner.DiscoveryReport report) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").hasNext()
            ? ImageIO.getImageWritersByFormatName("gif").next()
            : null;
        if (writer == null) {
            throw new IOException("No ImageIO writer for gif");
        }
        try (ImageOutputStream output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            int frames = Math.max(2, report.rows().stream().mapToInt(row -> Math.max(1, row.replayPath().size())).max().orElse(2));
            for (int i = 0; i < frames; i++) {
                writer.writeToSequence(new javax.imageio.IIOImage(renderFrame(report, i), null, null), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private BufferedImage renderFrame(DeterministicDiscoveryExperimentRunner.DiscoveryReport report, int frame) {
        int width = 480;
        int height = 180;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(26, 115, 232));
            graphics.fillRect(0, 0, width, 42);
            graphics.setColor(Color.WHITE);
            graphics.drawString("Regelsuche Discovery Replay", 20, 27);
            graphics.setColor(Color.BLACK);
            graphics.drawString("Seeds: " + report.metrics().processedSeeds(), 20, 72);
            graphics.drawString("Successful: " + report.metrics().successfulSeeds(), 20, 96);
            graphics.drawString("Hypotheses: " + report.metrics().hypotheses(), 20, 120);
            graphics.drawString("Counterexamples: " + report.metrics().counterexamples(), 20, 144);
            graphics.drawString("Replay frame: " + frame, 260, 72);
            report.rows().stream().findFirst().ifPresent(row -> {
                if (!row.replayPath().isEmpty()) {
                    String step = row.replayPath().get(Math.min(frame, row.replayPath().size() - 1));
                    graphics.drawString(step.substring(0, Math.min(52, step.length())), 260, 96);
                }
            });
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String metricCard(String label, String value) {
        return "<div class=\"card\"><div class=\"muted\">" + escape(label)
            + "</div><div class=\"metric\">" + escape(value) + "</div></div>";
    }

    private String renderHtmlDiscoveryDetails(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        StringBuilder out = new StringBuilder();
        out.append("<div><strong>Annahmen</strong>: ").append(renderPills(row.seed().assumptions())).append("</div>");
        out.append("<div><strong>Hypothesen</strong>: ").append(renderPills(row.hypotheses())).append("</div>");
        out.append("<div><strong>Gegenbeispiele</strong>: ").append(renderPills(row.counterexamples())).append("</div>");
        return out.toString();
    }

    private String renderPills(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return "<span class=\"muted\">–</span>";
        }
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append("<span class=\"pill\">").append(escape(value)).append("</span>");
        }
        return out.toString();
    }

    private String joinOrDash(java.util.List<String> values) {
        return values == null || values.isEmpty() ? "–" : String.join(", ", values);
    }

    public record ArtifactBundle(
        Path jsonReport,
        Path htmlReport,
        Path markdownReport,
        Path replayJson,
        Path screenshotPng,
        Path replayGif
    ) {
    }
}
