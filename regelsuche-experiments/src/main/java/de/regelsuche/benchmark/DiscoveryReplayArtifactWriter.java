package de.regelsuche.benchmark;

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
            Path screenshot = outputDirectory.resolve("discovery-summary.png");
            Path gif = outputDirectory.resolve("discovery-replay.gif");
            Files.writeString(json, report.renderDeterministicJson());
            Files.writeString(html, renderHtml(report));
            writeScreenshot(screenshot, report);
            writeReplayGif(gif, report);
            return new ArtifactBundle(json, html, screenshot, gif);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write discovery replay artefacts to " + outputDirectory, exception);
        }
    }

    public String renderHtml(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">");
        out.append("<title>Regelsuche Discovery Report</title>");
        out.append("<style>body{font-family:sans-serif}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:.35rem}")
            .append(".replay-step{margin:.15rem 0}.ok{color:#137333}.fail{color:#a50e0e}</style>");
        out.append("</head><body><h1>Regelsuche Discovery Report</h1>");
        out.append("<p>Seeds: ").append(report.metrics().processedSeeds())
            .append(" · erfolgreich: ").append(report.metrics().successfulSeeds())
            .append(" · Hypothesen: ").append(report.metrics().hypotheses())
            .append(" · Gegenbeispiele: ").append(report.metrics().counterexamples())
            .append("</p><table><thead><tr><th>Seed</th><th>Status</th><th>Replay</th><th>Summary</th></tr></thead><tbody>");
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            out.append("<tr><td>").append(escape(row.seed().stableKey())).append("</td><td>")
                .append(row.success() ? "<span class=\"ok\">OK</span>" : "<span class=\"fail\">FAIL</span>")
                .append("</td><td><ol>");
            for (String step : row.replayPath()) {
                out.append("<li class=\"replay-step\"><code>").append(escape(step)).append("</code></li>");
            }
            out.append("</ol></td><td>").append(escape(row.summary())).append("</td></tr>");
        }
        out.append("</tbody></table></body></html>");
        return out.toString();
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

    public record ArtifactBundle(Path jsonReport, Path htmlReport, Path screenshotPng, Path replayGif) {
    }
}
