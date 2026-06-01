package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsAssetGeneratorTest {
    @Test
    void writesBenchmarkEvidenceGalleryAssets() throws Exception {
        java.nio.file.Path output = Files.createTempDirectory("regelsuche-docs");

        new DocsAssetGenerator().generate(output);

        java.nio.file.Path gallery = output.resolve("gallery");
        assertTrue(Files.exists(gallery.resolve("index.html")));
        assertTrue(Files.exists(gallery.resolve("complete-square.svg")));
        assertTrue(Files.exists(gallery.resolve("sophie-germain.svg")));
        assertTrue(Files.exists(output.resolve("complete-square-evidence.json")));
        assertTrue(Files.exists(output.resolve("sophie-germain-evidence.json")));
        String index = Files.readString(gallery.resolve("index.html"));
        assertTrue(index.contains("Discovery benchmark evidence"));
        assertTrue(index.contains("complete-square-evidence.json"));
        assertTrue(index.contains("sophie-germain-evidence.json"));
        String svg = Files.readString(gallery.resolve("complete-square.svg"));
        assertTrue(svg.contains("data-generated-by=\"SearchSpaceGallerySvgWriter\""));
        assertTrue(svg.contains("data-scenario-id=\"complete-square-factorization\""));
        assertTrue(svg.contains("data-evidence=\"../complete-square-evidence.json\""));
        assertTrue(svg.contains("data-node-count="));
        assertTrue(svg.contains("data-edge-count="));
        String macroImpact = Files.readString(output.resolve("macro-impact.json"));
        assertTrue(macroImpact.contains("\"scenarioId\" : \"complete-square-factorization\""));
        assertTrue(macroImpact.contains("\"scenarioId\" : \"sophie-germain\""));
        assertTrue(macroImpact.contains("complete-square-evidence.json"));
        assertTrue(macroImpact.contains("sophie-germain-evidence.json"));
        String evidence = Files.readString(output.resolve("complete-square-evidence.json"));
        assertTrue(evidence.contains("complete_square_bridge"));
        assertTrue(evidence.contains("ast_square_difference_factor"));
        String sophieEvidence = Files.readString(output.resolve("sophie-germain-evidence.json"));
        assertTrue(sophieEvidence.contains("macro_"));
        assertTrue(sophieEvidence.contains("\"success\" : true"));
    }
}
