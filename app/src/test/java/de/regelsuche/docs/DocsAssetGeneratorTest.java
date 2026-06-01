package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class DocsAssetGeneratorTest {
    @Test
    void writesBridgeAnalysisGalleryAssets() throws Exception {
        java.nio.file.Path output = Files.createTempDirectory("regelsuche-docs");

        new DocsAssetGenerator().generate(output);

        java.nio.file.Path gallery = output.resolve("gallery");
        assertThat(gallery.resolve("index.html")).exists();
        assertThat(gallery.resolve("bridge-analysis.svg")).exists();
        assertThat(Files.readString(gallery.resolve("index.html"))).contains("bridge-analysis.svg");
        assertThat(Files.readString(gallery.resolve("bridge-analysis.svg"))).contains("Bridge node", "macro shortcut", "Target");
        assertThat(Files.readString(output.resolve("macro-impact.json"))).contains("bridgeDiscovered", "macroReused");
    }
}
