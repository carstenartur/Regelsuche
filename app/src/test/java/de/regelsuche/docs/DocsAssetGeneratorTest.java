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
        assertTrue(svg.contains("Bridge node"));
        assertTrue(svg.contains("macro shortcut"));
        assertTrue(svg.contains("Target"));
        String macroImpact = Files.readString(output.resolve("macro-impact.json"));
        assertTrue(macroImpact.contains("bridgeDiscovered"));
        assertTrue(macroImpact.contains("macroReused"));
    }
}
