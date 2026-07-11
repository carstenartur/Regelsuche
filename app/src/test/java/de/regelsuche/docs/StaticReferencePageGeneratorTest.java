package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticReferencePageGeneratorTest {

    @Test
    void generatorWritesIndexHtml(@TempDir Path outputDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        StaticReferencePageGenerator generator = new StaticReferencePageGenerator();
        generator.generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        assertTrue(Files.exists(outputDir.resolve("index.html")),
            "index.html must be written");
    }

    @Test
    void generatorWritesTimelineHtml(@TempDir Path outputDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        assertTrue(Files.exists(outputDir.resolve("timeline.html")),
            "timeline.html must be written");
    }

    @Test
    void generatorWritesObservatoryHtml(@TempDir Path outputDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        assertTrue(Files.exists(outputDir.resolve("observatory.html")),
            "observatory.html must be written");
    }

    @Test
    void generatorWritesMarkdownDigest(@TempDir Path outputDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        assertTrue(Files.exists(outputDir.resolve("timeline.md")),
            "timeline.md must be written");
    }

    @Test
    void generatorWritesVersionFile(@TempDir Path outputDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        assertTrue(Files.exists(outputDir.resolve("version.txt")),
            "version.txt must be written");
    }

    @Test
    void indexHtmlContainsReproduceSection(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("Reproduce") || html.contains("reproduce"),
            "index.html must contain Reproduce section");
    }

    @Test
    void indexHtmlContainsDockerCommand(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("docker"), "index.html must contain docker command");
        assertTrue(html.contains("Dockerfile.proof"), "index.html must reference Dockerfile.proof");
    }

    @Test
    void indexHtmlContainsGradleCommand(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("./gradlew runReferenceCampaign"),
            "index.html must contain exact Gradle command");
        assertTrue(html.contains("./gradlew generateStaticReferencePage"),
            "index.html must contain generateStaticReferencePage Gradle command");
    }

    @Test
    void indexHtmlLabelsPreGeneratedModeUnambiguously(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("PRE-GENERATED REFERENCE EVIDENCE"),
            "index.html must label PRE-GENERATED REFERENCE EVIDENCE mode unambiguously");
    }

    @Test
    void indexHtmlLabelsLiveModeUnambiguously(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.LIVE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("LIVE EXECUTION"),
            "index.html must label LIVE EXECUTION mode unambiguously");
    }

    @Test
    void indexHtmlContainsHonestyDeclaration(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("Honesty"), "index.html must contain Honesty Declaration");
        assertTrue(html.contains("Observation"), "index.html must note Observation ≠ proof");
    }

    @Test
    void indexHtmlContainsPageVersionFooter(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains(StaticReferencePageGenerator.PAGE_VERSION),
            "index.html must contain page version");
    }

    @Test
    void markdownAndHtmlContainEquivalentCoreFacts(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String html = Files.readString(outputDir.resolve("timeline.html"), StandardCharsets.UTF_8);
        String md = Files.readString(outputDir.resolve("timeline.md"), StandardCharsets.UTF_8);

        assertTrue(html.contains("Observe") || html.contains("Observe"), "HTML must mention Observe stage");
        assertTrue(md.contains("Observe") || md.contains("Observe"), "Markdown must mention Observe stage");

        assertTrue(html.contains("Validate"), "HTML must mention Validate stage");
        assertTrue(md.contains("Validate"), "Markdown must mention Validate stage");
    }

    @Test
    void versionFileContainsExpectedVersion(@TempDir Path outputDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();

        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE, null);

        String version = Files.readString(outputDir.resolve("version.txt"), StandardCharsets.UTF_8).strip();
        assertFalse(version.isBlank(), "version.txt must not be blank");
        assertTrue(version.equals(StaticReferencePageGenerator.PAGE_VERSION),
            "version.txt must contain PAGE_VERSION, found: " + version);
    }
}
