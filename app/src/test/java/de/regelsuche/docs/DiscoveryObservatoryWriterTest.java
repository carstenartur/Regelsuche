package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryObservatoryWriterTest {

    private static ReferenceCampaignRunner.CampaignReport report;
    private static final DiscoveryObservatoryWriter writer = new DiscoveryObservatoryWriter();

    @BeforeAll
    static void buildReport(@TempDir Path tempDir) {
        report = new ReferenceCampaignRunner().writeReport(tempDir);
    }

    @Test
    void observatoryHtmlContainsActiveFamiliesSection() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Active Families"), "Observatory must have Active Families section");
    }

    @Test
    void observatoryHtmlContainsCandidatesSection() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Candidates"), "Observatory must have Candidates section");
    }

    @Test
    void observatoryHtmlContainsCounterexamplesSection() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Counterexamples"), "Observatory must have Counterexamples section");
    }

    @Test
    void observatoryHtmlContainsProofStatusSection() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Proof Status"), "Observatory must have Proof Status section");
    }

    @Test
    void observatoryHtmlContainsNoveltySection() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Novelty"), "Observatory must have Novelty section");
    }

    @Test
    void observatoryHtmlContainsAblationSection() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Ablation"), "Observatory must have Ablation section");
    }

    @Test
    void observatoryHtmlLabelsLiveModeUnambiguously() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("LIVE EXECUTION"), "Observatory must label LIVE EXECUTION mode");
    }

    @Test
    void observatoryHtmlLabelsReplayedModeUnambiguously() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.REPLAYED_FROM_CANONICAL);
        assertTrue(html.contains("REPLAYED FROM CANONICAL EVIDENCE"),
            "Observatory must label REPLAYED FROM CANONICAL EVIDENCE mode");
    }

    @Test
    void observatoryHtmlLabelsPreGeneratedModeUnambiguously() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE);
        assertTrue(html.contains("PRE-GENERATED REFERENCE EVIDENCE"),
            "Observatory must label PRE-GENERATED REFERENCE EVIDENCE mode");
    }

    @Test
    void observatoryHtmlContainsHonestyNoteAboutStatusUpgrade() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("never upgrades"), "Observatory must note it never upgrades status");
    }

    @Test
    void observatoryHtmlShowsRejectedCandidatesAsInspectable() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        // Should indicate rejected candidates are visible
        assertTrue(html.contains("Rejected candidates remain inspectable")
            || html.contains("REJECTED"),
            "Observatory must show rejected candidates are inspectable");
    }

    @Test
    void observatoryHtmlContainsProofHonestyNote() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Prover confirmation"), "Observatory must note prover confirmation ≠ search success");
    }

    @Test
    void observatoryHtmlIsValidHtml() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "Must start with DOCTYPE");
        assertTrue(html.contains("</html>"), "Must contain closing html tag");
    }

    @Test
    void observatoryHtmlIsNotEmpty() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertFalse(html.isBlank(), "Observatory HTML must not be empty");
        assertTrue(html.length() > 500, "Observatory HTML must have substantial content");
    }
}
