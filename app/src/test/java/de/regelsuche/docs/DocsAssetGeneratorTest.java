package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsAssetGeneratorTest {
    @Test
    void writesBridgeAnalysisGalleryAssets() throws Exception {
        java.nio.file.Path output = Files.createTempDirectory("regelsuche-docs");

        new DocsAssetGenerator().generate(output);

        java.nio.file.Path gallery = output.resolve("gallery");
        assertTrue(Files.exists(gallery.resolve("index.html")));
        assertTrue(Files.exists(gallery.resolve("bridge-analysis.svg")));
        assertTrue(Files.readString(gallery.resolve("index.html")).contains("bridge-analysis.svg"));
        String svg = Files.readString(gallery.resolve("bridge-analysis.svg"));
        MacroImpactReport report = new MacroImpactReportGenerator().generate();
        assertTrue(report.withoutMacroBenchmark().bridgeRules().stream().anyMatch(svg::contains));
        assertTrue(report.withMacroAnalytics().ruleUsage().keySet().stream()
                .filter(rule -> rule.contains("macro"))
                .anyMatch(svg::contains));
        assertTrue(svg.contains("without states: " + report.withoutMacroAnalytics().statesExplored()));
        assertTrue(svg.contains("target: " + report.targetExpression()));
        String macroImpact = Files.readString(output.resolve("macro-impact.json"));
        assertTrue(macroImpact.contains("bridgeDiscovered"));
        assertTrue(macroImpact.contains("macroReused"));
    }
}
