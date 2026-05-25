package de.regelsuche.benchmark;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

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
            Path screenshot = outputDirectory.resolve("discovery-summary.png");
            Path gif = outputDirectory.resolve("discovery-replay.gif");
            Files.writeString(json, report.renderDeterministicJson());
            Files.writeString(html, renderHtml(report));
            writeRaster(screenshot, "png", report);
            writeRaster(gif, "gif", report);
            return new ArtifactBundle(json, html, screenshot, gif);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write discovery replay artefacts to " + outputDirectory, exception);
        }
    }

    public String renderHtml(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">");
        out.append("<title>Regelsuche Discovery Report</title>");
        out.append("<style>body{font-family:sans-serif}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:.35rem}</style>");
        out.append("</head><body><h1>Regelsuche Discovery Report</h1>");
        out.append("<p>Seeds: ").append(report.metrics().processedSeeds())
            .append(" · erfolgreich: ").append(report.metrics().successfulSeeds())
            .append(" · Hypothesen: ").append(report.metrics().hypotheses())
            .append(" · Gegenbeispiele: ").append(report.metrics().counterexamples())
            .append("</p><table><thead><tr><th>Seed</th><th>Status</th><th>Replay</th><th>Summary</th></tr></thead><tbody>");
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            out.append("<tr><td>").append(escape(row.seed().stableKey())).append("</td><td>")
                .append(row.success() ? "OK" : "FAIL")
                .append("</td><td><ol>");
            for (String step : row.replayPath()) {
                out.append("<li>").append(escape(step)).append("</li>");
            }
            out.append("</ol></td><td>").append(escape(row.summary())).append("</td></tr>");
        }
        out.append("</tbody></table></body></html>");
        return out.toString();
    }

    private void writeRaster(
        Path path,
        String format,
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report
    ) throws IOException {
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
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, format, path.toFile())) {
            throw new IOException("No ImageIO writer for " + format);
        }
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

    public record ArtifactBundle(Path jsonReport, Path htmlReport, Path screenshotPng, Path replayGif) {
    }
}
