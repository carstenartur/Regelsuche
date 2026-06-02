package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GalleryConsistencyTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path REPO_ROOT = locateRepoRoot();
    private static final Pattern GENERATED_LINK = Pattern.compile("generated/discovery/([^)]*(?:evidence\\.json|search-space\\.svg))");
    private static final Pattern README_EVIDENCE_ROW = Pattern.compile("\\|\\s*(Complete square|Sophie-Germain)\\s*\\|\\s*yes\\s*\\|\\s*yes\\s*\\|\\s*yes\\s*\\|\\s*\\[link]\\(([^)]+)\\)");

    @Test
    void galleryReferencesOnlySuccessfulGeneratedEvidence() throws IOException {
        Path gallery = REPO_ROOT.resolve("docs/demo-gallery.md");
        Path generatedRoot = REPO_ROOT.resolve("docs/generated/discovery");
        String markdown = Files.readString(gallery, StandardCharsets.UTF_8);
        Set<Path> evidenceFiles = new HashSet<>();
        Set<Path> svgFiles = new HashSet<>();

        Matcher matcher = GENERATED_LINK.matcher(markdown);
        while (matcher.find()) {
            Path target = generatedRoot.resolve(matcher.group(1)).normalize();
            assertTrue(target.startsWith(generatedRoot), "Generated link must stay below docs/generated/discovery: " + target);
            assertTrue(Files.exists(target), "Generated gallery link must exist: " + target);
            if (target.getFileName().toString().equals("evidence.json")) {
                evidenceFiles.add(target);
            } else if (target.getFileName().toString().equals("search-space.svg")) {
                svgFiles.add(target);
            }
        }

        assertFalse(evidenceFiles.isEmpty(), "Gallery must link generated evidence.json files");
        assertEquals(evidenceFiles.size(), svgFiles.size(), "Each public evidence file must have a public SVG");
        for (Path evidenceFile : evidenceFiles) {
            JsonNode evidence = JSON.readTree(evidenceFile.toFile());
            assertTrue(evidence.path("success").asBoolean(), evidenceFile.toString());
            assertEquals(DocsDiscoveryGalleryGenerator.GENERATED_BY, evidence.path("generatedBy").asText());
            Path svg = evidenceFile.getParent().resolve("search-space.svg");
            assertTrue(svgFiles.contains(svg), "Gallery must link SVG next to " + evidenceFile);
            String svgContent = Files.readString(svg, StandardCharsets.UTF_8);
            assertTrue(svgContent.contains("data-scenario-id=\"" + evidence.path("scenarioId").asText() + "\""), svg.toString());
            assertTrue(svgContent.contains("data-generated-by=\"SearchSpaceGallerySvgWriter\""), svg.toString());
            assertTrue(svgContent.contains("data-evidence=\"evidence.json\""), svg.toString());
            assertTrue(svgContent.contains("class=\"edge bridge\""), svg.toString());
            if (!evidence.path("reusedMacros").isEmpty()) {
                assertTrue(svgContent.contains("class=\"edge macro\""), svg.toString());
            }
            assertTrue(evidence.path("nodeCount").asInt() > 3, "No old three-node graph may be the main evidence: " + evidenceFile);
        }
    }

    @Test
    void readmeLinksResolveAndClaimsMatchGeneratedEvidence() throws IOException {
        String readme = Files.readString(REPO_ROOT.resolve("README.md"), StandardCharsets.UTF_8);
        Matcher matcher = README_EVIDENCE_ROW.matcher(readme);
        int rows = 0;
        while (matcher.find()) {
            rows++;
            Path evidencePath = REPO_ROOT.resolve(matcher.group(2)).normalize();
            assertTrue(Files.exists(evidencePath), "README evidence link must exist: " + matcher.group(2));
            JsonNode evidence = JSON.readTree(evidencePath.toFile());
            assertTrue(evidence.path("success").asBoolean(), matcher.group(1));
            assertFalse(evidence.path("bridgeRulesUsed").isEmpty(), matcher.group(1));
            assertFalse(evidence.path("learnedMacros").isEmpty(), matcher.group(1));
            assertFalse(evidence.path("reusedMacros").isEmpty(), matcher.group(1));
        }
        assertEquals(2, rows, "README must list both generated public evidence scenarios");
        assertTrue(Files.exists(REPO_ROOT.resolve("docs/demo-gallery.md")), "README gallery target must exist");
        assertFalse(readme.contains("search-space-sophie-germain-compact.svg"), "README must not use old manual/curated main graphics");
    }

    @Test
    void generatedIndexMatchesPublicArtifacts() throws IOException {
        Path indexPath = REPO_ROOT.resolve("docs/generated/discovery/index.json");
        JsonNode index = JSON.readTree(indexPath.toFile());
        assertEquals(DocsDiscoveryGalleryGenerator.GENERATED_BY, index.path("generatedBy").asText());
        assertEquals(2, index.path("scenarios").size());
        for (JsonNode scenario : index.path("scenarios")) {
            assertTrue(scenario.path("success").asBoolean(), scenario.toString());
            assertTrue(Files.exists(indexPath.getParent().resolve(scenario.path("evidence").asText())), scenario.toString());
            assertTrue(Files.exists(indexPath.getParent().resolve(scenario.path("svg").asText())), scenario.toString());
        }
    }

    @Test
    void oldWeakDiscoveryGraphicsAreNotPublicMainEvidence() throws IOException {
        String readme = Files.readString(REPO_ROOT.resolve("README.md"), StandardCharsets.UTF_8);
        String gallery = Files.readString(REPO_ROOT.resolve("docs/demo-gallery.md"), StandardCharsets.UTF_8);
        String publicDocs = readme + "\n" + gallery;
        assertFalse(publicDocs.contains("convergent-sophie-germain.svg"), "Old explanatory graph must not be public main evidence");
        assertFalse(publicDocs.contains("parametric-sophie-germain-discovery.png"), "Old screenshot must not be public main evidence");
        assertFalse(publicDocs.contains("source replay ids: none"), "Public docs must not expose empty replay ids");
        assertFalse(publicDocs.contains("source replay:"), "Public docs must not expose legacy replay ids");
    }

    private static Path locateRepoRoot() {
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md")) && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
            if (candidate == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
