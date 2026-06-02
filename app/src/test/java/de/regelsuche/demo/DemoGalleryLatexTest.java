package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DemoGalleryLatexTest {
    private static final Path REPO_ROOT = locateRepoRoot();
    private static String gallery;

    @BeforeAll
    static void loadGallery() throws IOException {
        Path md = REPO_ROOT.resolve("docs/demo-gallery.md");
        assertTrue(Files.exists(md), "docs/demo-gallery.md must exist");
        gallery = Files.readString(md, StandardCharsets.UTF_8);
    }

    @Test
    void demoGalleryIsGeneratedDiscoveryEvidenceOnly() {
        assertTrue(gallery.startsWith("# Regelsuche Discovery Gallery"));
        assertTrue(gallery.contains("This gallery contains generated evidence only."));
        assertTrue(gallery.contains("## Complete-square factorization"));
        assertTrue(gallery.contains("## Sophie-Germain discovery"));
        assertTrue(gallery.contains("## Scenario comparison"));
    }

    @Test
    void publicDiscoverySectionsLinkGeneratedEvidence() {
        assertTrue(gallery.contains("generated/discovery/complete-square/evidence.json"));
        assertTrue(gallery.contains("generated/discovery/complete-square/search-space.svg"));
        assertTrue(gallery.contains("generated/discovery/sophie-germain/evidence.json"));
        assertTrue(gallery.contains("generated/discovery/sophie-germain/search-space.svg"));
    }

    @Test
    void generatedGalleryDoesNotExposeLegacyCuratedDemoSections() {
        assertFalse(gallery.contains("Binomische Formel"));
        assertFalse(gallery.contains("Bruchkürzung"));
        assertFalse(gallery.contains("Ungleichung"));
        assertFalse(gallery.contains("parametric-sophie-germain-discovery.png"));
        assertFalse(gallery.contains("convergent-sophie-germain.svg"));
    }

    private static Path locateRepoRoot() {
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md"))
                && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
