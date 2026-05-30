package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests that pin down the killer-demo documentation contract:
 * <ul>
 *   <li>the single Docker image is the primary/standard mode,</li>
 *   <li>Docker Compose is documented only as an <em>optional</em> Full Mode
 *       for persistent / Neo4j-backed analyses.</li>
 * </ul>
 *
 * <p>These tests guard against silent drift where someone could "promote"
 * Compose to the recommended way to run the demo and thereby break the
 * "5 minutes to wow" promise.</p>
 */
class DemoDocumentationTest {

    private static final Path REPO_ROOT = locateRepoRoot();
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("(?<!!)\\[[^]]*]\\(([^)]+)\\)");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);

    @Test
    void singleDockerImageDocumentedAsPrimaryMode() throws IOException {
        String readme = readReadme();
        // Quickstart section must use the single-image flow and announce
        // it as the primary/standard demo mode.
        assertTrue(readme.contains("docker build -t regelsuche ."),
            "README must document `docker build -t regelsuche .`");
        assertTrue(readme.contains("docker run --rm -p 8080:8080 regelsuche"),
            "README must document `docker run --rm -p 8080:8080 regelsuche`");
        assertTrue(
            readme.contains("Killer-Demo Standard")
                || readme.contains("Standardmodus der Killer-Demo")
                || readme.contains("Standard"),
            "README must mark the single Docker image as the standard mode");
        // It must explicitly state "no external infrastructure required".
        assertTrue(readme.contains("ohne externe"),
            "README must state explicitly that the standard mode needs no external infrastructure");
    }

    @Test
    void dockerComposeDocumentedOnlyAsOptionalFullMode() throws IOException {
        String readme = readReadme();
        // Compose section exists ...
        assertTrue(readme.contains("docker compose up --build"),
            "README must mention `docker compose up --build`");
        // ... but only as Optional Full Mode.
        int composeIndex = readme.indexOf("docker compose up --build");
        // Heading immediately preceding the compose snippet must contain
        // both "Optional" and "Full Mode".
        String prelude = readme.substring(Math.max(0, composeIndex - 800), composeIndex);
        assertTrue(prelude.contains("Optional"),
            "Compose must be introduced under an 'Optional' heading");
        assertTrue(prelude.contains("Full Mode"),
            "Compose must be introduced as the Full Mode");
        // And the README must explicitly say that Compose is NOT a
        // prerequisite for the demo.
        assertTrue(readme.contains("nur optional") || readme.contains("nicht Voraussetzung")
                || readme.contains("nur optionaler"),
            "README must explicitly state Compose is only optional / not a prerequisite");

        // The actual compose file must exist (so the documentation has a
        // real artifact to point at).
        Path compose = REPO_ROOT.resolve("docker-compose.yml");
        assertTrue(Files.exists(compose), "docker-compose.yml must exist at the repo root");
        String composeContent = Files.readString(compose, StandardCharsets.UTF_8);
        assertTrue(composeContent.contains("neo4j"),
            "docker-compose.yml must wire a Neo4j service");
        assertTrue(composeContent.contains("NEO4J_URI"),
            "docker-compose.yml must export NEO4J_URI to the app");
        assertTrue(composeContent.contains("NEO4J_USER"),
            "docker-compose.yml must export NEO4J_USER to the app");
        assertTrue(composeContent.contains("NEO4J_PASSWORD"),
            "docker-compose.yml must export NEO4J_PASSWORD to the app");
        assertTrue(composeContent.contains("volumes:"),
            "docker-compose.yml must declare a persistent Neo4j volume");
    }

    @Test
    void demoGraphSemanticAssetsEndInCollectedPolynomialNormalForm() throws IOException {
        assertSemanticGraphAssetFinalNormalForm(
            "binomial-graph.semantic.json",
            "x^2+6*x+9",
            "x*x+3*x+x*3+3*3"
        );
        assertSemanticGraphAssetFinalNormalForm(
            "polynomial-expansion-graph.semantic.json",
            "x^2+3*x+2",
            "x*x+x*2+x+2"
        );
    }

    @Test
    void demoGalleryDoesNotReuseScreenshotReferences() throws IOException {
        Path gallery = REPO_ROOT.resolve("docs/demo-gallery.md");
        Map<String, Integer> counts = new HashMap<>();
        for (String image : localReferences(Files.readString(gallery, StandardCharsets.UTF_8), MARKDOWN_IMAGE)) {
            if (image.startsWith("assets/screenshots/")) {
                counts.merge(image, 1, Integer::sum);
            }
        }

        List<String> duplicates = counts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(entry -> entry.getKey() + " x" + entry.getValue())
            .sorted()
            .toList();
        assertTrue(duplicates.isEmpty(), "demo-gallery.md must not repeat screenshot references: " + duplicates);
    }

    @Test
    void localDocsLinksAndImagesResolve() throws IOException {
        assertMarkdownReferencesResolve(REPO_ROOT.resolve("README.md"));
        assertMarkdownReferencesResolve(REPO_ROOT.resolve("docs/demo-gallery.md"));
    }

    private static String readReadme() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        assertNotNull(readme);
        assertTrue(Files.exists(readme), "README.md must exist at repository root");
        return Files.readString(readme, StandardCharsets.UTF_8);
    }

    private static void assertSemanticGraphAssetFinalNormalForm(
        String fileName,
        String expectedFinalNormalizedLabel,
        String forbiddenUncollectedFinalLabel
    ) throws IOException {
        Path asset = REPO_ROOT.resolve("docs/assets/screenshots").resolve(fileName);
        assertTrue(Files.exists(asset), fileName + " must exist next to the graph PNG");
        String json = Files.readString(asset, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"normalizedLabel\": \"" + expectedFinalNormalizedLabel + "\""),
            fileName + " must contain the collected polynomial node");
        assertTrue(json.contains("\"id\": \"\\\\text{collect like terms}\""),
            fileName + " must contain the visible collect-like-terms edge label");
        assertTrue(json.contains("\"finalNormalizedLabel\": \"" + expectedFinalNormalizedLabel + "\""),
            fileName + " must assert finalNormalizedLabel as collected normal form");
        assertTrue(!json.contains("\"finalNormalizedLabel\": \"" + forbiddenUncollectedFinalLabel + "\""),
            fileName + " must not assert the uncollected expansion as finalNormalizedLabel");
    }

    private static Path locateRepoRoot() {
        // Tests run from the `app` subproject, so the repo root is the
        // parent of the working directory. Walk upwards until we find a
        // directory containing both README.md and settings.gradle.
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
        throw new IllegalStateException(
            "Could not locate repository root from " + Paths.get(".").toAbsolutePath());
    }

    private static void assertMarkdownReferencesResolve(Path markdownFile) throws IOException {
        String markdown = Files.readString(markdownFile, StandardCharsets.UTF_8);
        for (String image : localReferences(markdown, MARKDOWN_IMAGE)) {
            Path imagePath = resolveReference(markdownFile, image);
            assertTrue(Files.exists(imagePath), "Referenced image must exist: " + image + " in " + markdownFile);
            assertTrue(Files.isRegularFile(imagePath), "Referenced image must be a file: " + image + " in " + markdownFile);
        }

        for (String link : localReferences(markdown, MARKDOWN_LINK)) {
            String[] targetAndFragment = splitFragment(link);
            Path targetPath = targetAndFragment[0].isBlank()
                ? markdownFile
                : resolveReference(markdownFile, targetAndFragment[0]);
            assertTrue(Files.exists(targetPath), "Referenced link target must exist: " + link + " in " + markdownFile);
            if (!targetAndFragment[1].isBlank()) {
                assertTrue(Files.isRegularFile(targetPath), "Anchor target must be a file: " + link + " in " + markdownFile);
                Set<String> anchors = markdownAnchors(Files.readString(targetPath, StandardCharsets.UTF_8));
                assertTrue(anchors.contains(targetAndFragment[1]),
                    "Referenced anchor must exist: " + link + " in " + markdownFile + " anchors=" + anchors);
            }
        }
    }

    private static List<String> localReferences(String markdown, Pattern pattern) {
        Matcher matcher = pattern.matcher(markdown);
        List<String> references = new ArrayList<>();
        while (matcher.find()) {
            String reference = matcher.group(1).trim();
            int titleSeparator = reference.indexOf(' ');
            if (titleSeparator > 0) {
                reference = reference.substring(0, titleSeparator);
            }
            if (!reference.isBlank() && isLocalReference(reference)) {
                references.add(reference);
            }
        }
        return references;
    }

    private static boolean isLocalReference(String reference) {
        return !reference.startsWith("http://")
            && !reference.startsWith("https://")
            && !reference.startsWith("mailto:")
            && !reference.startsWith("data:");
    }

    private static String[] splitFragment(String reference) {
        String withoutQuery = reference.split("\\?", 2)[0];
        String[] parts = withoutQuery.split("#", 2);
        return new String[] { parts[0], parts.length == 2 ? parts[1] : "" };
    }

    private static Path resolveReference(Path markdownFile, String reference) {
        String target = splitFragment(reference)[0];
        if (target.isBlank()) {
            return markdownFile;
        }
        return markdownFile.getParent().resolve(target).normalize();
    }

    private static Set<String> markdownAnchors(String markdown) {
        Matcher matcher = MARKDOWN_HEADING.matcher(markdown);
        Set<String> anchors = new HashSet<>();
        while (matcher.find()) {
            anchors.add(githubAnchor(matcher.group(1)));
        }
        return anchors;
    }

    private static String githubAnchor(String heading) {
        String withoutFormatting = heading
            .replace("`", "")
            .replaceAll("<[^>]+>", "");
        String slug = withoutFormatting.toLowerCase(java.util.Locale.ROOT)
            .trim()
            .replaceAll("\\s+", "-")
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}-]", "");
        return slug;
    }
}
