package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryStoryTimelineWriterTest {

    private static ReferenceCampaignRunner.CampaignReport report;
    private static final DiscoveryStoryTimelineWriter writer = new DiscoveryStoryTimelineWriter();

    @BeforeAll
    static void buildReport(@TempDir Path tempDir) {
        report = new ReferenceCampaignRunner().writeReport(tempDir);
    }

    @Test
    void htmlTimelineContainsAllSevenStages() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Observe"), "HTML must contain Observe stage");
        assertTrue(html.contains("Generalize"), "HTML must contain Generalize stage");
        assertTrue(html.contains("Challenge"), "HTML must contain Challenge stage");
        assertTrue(html.contains("Refine"), "HTML must contain Refine stage");
        assertTrue(html.contains("Validate"), "HTML must contain Validate stage");
        assertTrue(html.contains("Prove"), "HTML must contain Prove stage");
        assertTrue(html.contains("Learn"), "HTML must contain Learn stage");
    }

    @Test
    void htmlTimelineLabelsLiveModeUnambiguously() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("LIVE EXECUTION"), "HTML must label LIVE EXECUTION mode");
    }

    @Test
    void htmlTimelineLabelsReplayedModeUnambiguously() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.REPLAYED_FROM_CANONICAL);
        assertTrue(html.contains("REPLAYED FROM CANONICAL EVIDENCE"),
            "HTML must label REPLAYED FROM CANONICAL EVIDENCE mode");
    }

    @Test
    void htmlTimelineLabelsPreGeneratedModeUnambiguously() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.PRE_GENERATED_REFERENCE);
        assertTrue(html.contains("PRE-GENERATED REFERENCE EVIDENCE"),
            "HTML must label PRE-GENERATED REFERENCE EVIDENCE mode");
    }

    @Test
    void htmlTimelineDistinguishesObservationFromProof() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("SEARCH_FOUND_PATH"),
            "HTML must distinguish search found path from proof");
        assertTrue(html.contains("mathematical proof"),
            "HTML must contain honesty note about proof");
    }

    @Test
    void htmlTimelineDistinguishesNoCexFromProof() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("No-counterexample-found"),
            "HTML must note that no-counterexample-found is not proof");
    }

    @Test
    void htmlTimelineDistinguishesScriptFromProverConfirmation() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("Script-generation"), "HTML must distinguish script-generation from prover confirmation");
    }

    @Test
    void htmlTimelineDistinguishesTrainingFromHoldout() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.contains("positive holdouts") || html.contains("Holdout"),
            "HTML must separately show holdout results");
    }

    @Test
    void htmlTimelineKeepsRejectedHypothesesVisible() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        // Revision table should show rejected revisions
        assertTrue(html.contains("revision") || html.contains("REJECTED") || html.contains("revision(s)"),
            "HTML must keep rejected candidates visible");
    }

    @Test
    void htmlTimelineIsValidHtml() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "Must start with DOCTYPE");
        assertTrue(html.contains("</html>"), "Must contain closing html tag");
    }

    @Test
    void markdownTimelineContainsAllSevenStages() {
        String md = writer.renderMarkdown(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(md.contains("## Stage 1"), "Markdown must contain Stage 1");
        assertTrue(md.contains("## Stage 2"), "Markdown must contain Stage 2");
        assertTrue(md.contains("## Stage 3"), "Markdown must contain Stage 3");
        assertTrue(md.contains("## Stage 4"), "Markdown must contain Stage 4");
        assertTrue(md.contains("## Stage 5"), "Markdown must contain Stage 5");
        assertTrue(md.contains("## Stage 6"), "Markdown must contain Stage 6");
        assertTrue(md.contains("## Stage 7"), "Markdown must contain Stage 7");
    }

    @Test
    void markdownTimelineLabelsMode() {
        String md = writer.renderMarkdown(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(md.contains("LIVE EXECUTION"), "Markdown must label LIVE EXECUTION mode");
    }

    @Test
    void markdownAndHtmlContainEquivalentCoreFacts() {
        String html = writer.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        String md = writer.renderMarkdown(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);

        // Both must mention key training data
        assertTrue(html.contains("training") || html.contains("Training"),
            "HTML must mention training");
        assertTrue(md.contains("training") || md.contains("Training"),
            "Markdown must mention training");

        // Both must mention holdouts
        assertTrue(html.contains("holdout") || html.contains("Holdout"),
            "HTML must mention holdouts");
        assertTrue(md.contains("holdout") || md.contains("Holdout"),
            "Markdown must mention holdouts");

        // Both must have honesty notes
        assertTrue(html.contains("proof"), "HTML must mention proof honesty note");
        assertTrue(md.contains("proof"), "Markdown must mention proof honesty note");
    }

    @Test
    void markdownTimelineContainsHonestyNotes() {
        String md = writer.renderMarkdown(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertTrue(md.contains("SEARCH_FOUND_PATH"), "Markdown must note search-found-path");
        assertTrue(md.contains("No-counterexample-found"), "Markdown must note no-cex");
        assertTrue(md.contains("Script-generation"), "Markdown must note script-generation");
        assertTrue(md.contains("Holdout success"), "Markdown must note holdout vs proof distinction");
    }

    @Test
    void markdownTimelineIsNotEmpty() {
        String md = writer.renderMarkdown(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE);
        assertFalse(md.isBlank(), "Markdown timeline must not be empty");
        assertTrue(md.length() > 200, "Markdown timeline must have substantial content");
    }
}
