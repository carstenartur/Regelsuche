package de.regelsuche.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DocsAssetGenerator {
    private final MacroImpactReportGenerator macroImpactReportGenerator = new MacroImpactReportGenerator();

    public void generate(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            MacroImpactReport report = macroImpactReportGenerator.generate();
            writeGallery(outputDir.resolve("gallery"), report);
            writeMacroImpact(outputDir.resolve("macro-impact.json"), report);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void writeGallery(Path gallery, MacroImpactReport report) throws IOException {
        Files.createDirectories(gallery);
        Files.writeString(gallery.resolve("bridge-analysis.svg"), bridgeAnalysisSvg(report), StandardCharsets.UTF_8);
        String html = """
                <!doctype html>
                <html><head><title>Regelsuche Gallery</title></head>
                <body><h1>Regelsuche Discovery Gallery</h1>
                <img src=\"bridge-analysis.svg\" alt=\"Bridge analysis graph\">
                </body></html>
                """;
        Files.writeString(gallery.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private String bridgeAnalysisSvg(MacroImpactReport report) {
        List<SvgNode> graphNodes = bridgeAnalysisNodes(report);
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < graphNodes.size(); i++) {
            int x = 70 + (i % 6) * 135;
            int y = 80 + (i / 6) * 120;
            SvgNode node = graphNodes.get(i);
            String fill = switch (node.kind()) {
                case "bridge" -> "#fde68a";
                case "macro" -> "#ddd6fe";
                case "target" -> "#dcfce7";
                default -> "#e0f2fe";
            };
            nodes.append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                    .append("\" r=\"30\" fill=\"").append(fill)
                    .append("\" stroke=\"#334155\"/>\n");
            nodes.append("<text x=\"").append(x - 45).append("\" y=\"").append(y + 5)
                    .append("\" font-size=\"11\">").append(escapeXml(node.label())).append("</text>\n");
            if (i > 0) {
                int previousX = 70 + ((i - 1) % 6) * 135;
                int previousY = 80 + ((i - 1) / 6) * 120;
                String stroke = switch (node.kind()) {
                    case "bridge" -> "#d97706";
                    case "macro" -> "#7c3aed";
                    default -> "#475569";
                };
                edges.append("<line x1=\"").append(previousX + 30).append("\" y1=\"").append(previousY)
                        .append("\" x2=\"").append(x - 30).append("\" y2=\"").append(y)
                        .append("\" stroke=\"").append(stroke)
                        .append("\" marker-end=\"url(#arrow)\"/>\n");
            }
        }
        return """
                <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"860\" height=\"300\">
                  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>
                  <defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"10\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#475569\"/></marker></defs>
                  ${edges}
                  ${nodes}
                  <text x=\"40\" y=\"280\" font-size=\"12\">Bridge rules: amber · Macro rules: purple · Target: green</text>
                </svg>
                """.replace("${edges}", edges.toString()).replace("${nodes}", nodes.toString());
    }

    private List<SvgNode> bridgeAnalysisNodes(MacroImpactReport report) {
        List<SvgNode> nodes = new ArrayList<>();
        nodes.add(new SvgNode("input: " + report.inputExpression(), "state"));
        nodes.add(new SvgNode("without states: " + report.withoutMacroAnalytics().statesExplored(), "state"));
        report.withoutMacroBenchmark().bridgeRules()
                .forEach(rule -> nodes.add(new SvgNode("bridge: " + rule, "bridge")));
        report.withMacroAnalytics().ruleUsage().keySet().stream()
                .filter(rule -> rule.toLowerCase(Locale.ROOT).contains("macro"))
                .forEach(rule -> nodes.add(new SvgNode("macro: " + rule, "macro")));
        nodes.add(new SvgNode("with states: " + report.withMacroAnalytics().statesExplored(), "state"));
        nodes.add(new SvgNode("target: " + report.targetExpression(), "target"));
        return nodes;
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void writeMacroImpact(Path path, MacroImpactReport report) throws IOException {
        String json = """
                {
                  "withoutMacroStates": %d,
                  "withMacroStates": %d,
                  "pathsExplored": %d,
                  "convergenceCount": %d,
                  "bridgeUsage": %d,
                  "bridgeDiscovered": %s,
                  "macroReused": %s,
                  "improvementFactor": %.2f
                }
                """.formatted(report.withoutMacroStates(), report.withMacroStates(), report.pathsExplored(),
                report.convergenceCount(), report.bridgeUsage(), report.bridgeDiscovered(), report.macroReused(),
                report.improvementFactor());
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private record SvgNode(String label, String kind) {
    }
}
