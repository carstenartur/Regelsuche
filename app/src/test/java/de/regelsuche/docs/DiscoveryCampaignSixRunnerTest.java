package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.CountableMoveSearchEngine;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignSixRunnerTest {
    @Test
    void reportsCountableMoveSearchProbe(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSixRunner runner = new DiscoveryCampaignSixRunner();

        DiscoveryCampaignSixRunner.CampaignReport report = runner.writeReport(tempDir);

        assertEquals("discovery-campaign-6", report.campaignId());
        assertEquals(4, report.cases().size());
        assertTrue(report.cases().stream().allMatch(result -> result.depth1CandidateProbe().expectedMovePresent()));
        assertTrue(report.relatedFollowUpIssues().stream().anyMatch(issue -> issue.contains("#102")));

        DiscoveryCampaignSixRunner.CaseResult cancellation = report.cases().stream()
            .filter(result -> result.id().equals("cancellation-plus-one"))
            .findFirst()
            .orElseThrow();
        assertEquals("Move-only", cancellation.depth1CandidateProbe().expectedMoveCoverage());
        assertEquals("Missing normalizer", cancellation.architectureNote());
        assertTrue(cancellation.multiStepSearch().failureReason().contains("TARGET_NOT_REACHED")
            || cancellation.multiStepSearch().failureReason().contains("MAX_STATES_REACHED"));

        DiscoveryCampaignSixRunner.CaseResult completeSquare = report.cases().stream()
            .filter(result -> result.id().equals("complete-square"))
            .findFirst()
            .orElseThrow();
        assertTrue(completeSquare.multiStepSearch().success());
        assertTrue(completeSquare.multiStepSearch().pathLength() >= 1);
        assertTrue(completeSquare.multiStepSearch().ordinalPath().size() == completeSquare.multiStepSearch().pathLength());

        // Search Space Intelligence (Issue #103)
        assertEquals(4, report.searchSpaceSummary().caseCount());
        assertTrue(report.cases().stream().allMatch(result -> !result.searchSpaceAssessment().isBlank()));
        assertTrue(report.searchSpaceSummary().totalDuplicateStates() >= 1, "Duplikate müssen gezählt werden");
        assertTrue(report.cases().stream().anyMatch(result -> result.multiStepSearch().success()
            && !result.searchSpace().successfulPathMoveKinds().isEmpty()),
            "Mindestens ein Fall muss einen erfolgreichen Pfad mit Search-Space-Metriken zeigen");
        assertEquals(
            completeSquare.multiStepSearch().appliedMoves().stream().map(move -> move.kind().name()).toList(),
            completeSquare.searchSpace().successfulPathMoveKinds()
        );
        assertEquals("ausreichend klein", completeSquare.searchSpaceAssessment());
        assertEquals("braucht Normalizer", cancellation.searchSpaceAssessment());

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-6.json")));
        Path markdown = tempDir.resolve("countable-move-enumeration-report.md");
        assertTrue(Files.exists(markdown));
        String rendered = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("# Discovery Campaign 6: Countable Move Search Probe"));
        assertTrue(rendered.contains("Depth-1 Candidate Summary"));
        assertTrue(rendered.contains("Multi-step Search Result"));
        assertTrue(rendered.contains("Classic-vs-Move Vergleich"));
        assertTrue(rendered.contains("Related follow-up issues"));
        assertTrue(rendered.contains("### Search Space Intelligence"));
        assertTrue(rendered.contains("## Search Space Intelligence Summary"));
        assertTrue(rendered.contains("branchingFactor pro Tiefe"));
        assertTrue(rendered.contains("MoveKind-Histogramm"));
        assertTrue(rendered.contains("Enumerator-Histogramm"));
    }

    @Test
    void renderedReportIsReproducible(@TempDir Path first, @TempDir Path second) throws Exception {
        DiscoveryCampaignSixRunner runner = new DiscoveryCampaignSixRunner();

        runner.writeReport(first);
        runner.writeReport(second);

        String firstReport = Files.readString(
            first.resolve("countable-move-enumeration-report.md"), StandardCharsets.UTF_8);
        String secondReport = Files.readString(
            second.resolve("countable-move-enumeration-report.md"), StandardCharsets.UTF_8);
        assertEquals(firstReport, secondReport);
    }

    @Test
    void successfulLargeSearchSpaceIsNotClassifiedAsSufficientlySmall() throws Exception {
        DiscoveryCampaignSixRunner runner = new DiscoveryCampaignSixRunner();
        Method assess = DiscoveryCampaignSixRunner.class.getDeclaredMethod(
            "assessSearchSpace",
            CountableMoveSearchEngine.CountableMoveSearchResult.class,
            String.class
        );
        assess.setAccessible(true);

        CountableMoveSearchEngine.SearchSpaceMetrics metrics = new CountableMoveSearchEngine.SearchSpaceMetrics(
            65,
            65,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            0,
            0,
            0
        );
        CountableMoveSearchEngine.CountableMoveSearchResult result = new CountableMoveSearchEngine.CountableMoveSearchResult(
            true,
            "x",
            "x",
            List.of("x"),
            List.of(),
            List.of(),
            65,
            65,
            CountableMoveSearchEngine.FailureReason.NONE,
            metrics
        );

        String assessment = (String) assess.invoke(runner, result, "");
        assertEquals("braucht stärkere Heuristik", assessment);
    }
}
