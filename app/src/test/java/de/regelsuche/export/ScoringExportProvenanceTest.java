package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.ScoreRevision;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScoringExportProvenanceTest {
    private final DefaultTransformationExportService exporter = new DefaultTransformationExportService(
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final DefaultTransformationImportService importer = new DefaultTransformationImportService();

    @Test
    void builtInScoreRevisionAndAllRetainedPathDataSurviveRoundTrip() {
        var scorer = new ExpressionScorer();
        var original = path(scorer.score("x/x"), scorer.score("1"));
        var imported = importer.importJson(export(original)).transformations().getFirst();
        assertEquals(original, imported);
        assertEquals(ScoreRevision.CURRENT, imported.originalScore().scoringRevision());
        assertEquals(ScoreRevision.CURRENT, imported.improvedScore().scoringRevision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom-metric/v1", "obsolete-metric/v1", ScoreRevision.UNSPECIFIED})
    void exporterPreservesTheActualProducerRatherThanStampingItsOwnRevision(String revision) {
        var original = path(new ExpressionScore(701, 31, 2, 0, 9, revision),
            new ExpressionScore(603, 27, 1, 0, 5, revision));
        var imported = importer.importJson(export(original)).transformations().getFirst();
        assertEquals(original, imported);
        assertEquals(revision, imported.originalScore().scoringRevision());
        assertEquals(revision, imported.improvedScore().scoringRevision());
        assertFalse(export(imported).contains(ScoreRevision.CURRENT));
    }

    @Test
    void historicalNumbersRemainUnspecifiedAndAreNotRescoredOrRelabelled() {
        var original = path(new ExpressionScore(701, 31, 2, 0, 9, ScoreRevision.CURRENT),
            new ExpressionScore(603, 27, 1, 0, 5, ScoreRevision.CURRENT));
        String legacy = export(original).replace("\"scoringRevision\":\"" + ScoreRevision.CURRENT + "\",", "");
        assertFalse(legacy.contains("scoringRevision"));
        var imported = importer.importJson(legacy).transformations().getFirst();
        assertEquals(new ExpressionScore(701, 31, 2, 0, 9), imported.originalScore());
        assertEquals(new ExpressionScore(603, 27, 1, 0, 5), imported.improvedScore());
        assertEquals(original.steps(), imported.steps());
        assertEquals(original.totalImprovement(), imported.totalImprovement());
        assertFalse(export(imported).contains(ScoreRevision.CURRENT));
        assertThrows(IllegalArgumentException.class, () ->
            imported.originalScore().improvementTo(new ExpressionScorer().score("1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "true", "[]", "{}", "null"})
    void malformedRevisionTypesAreRejectedInsteadOfCoercedIntoAnIdentity(String invalidJson) {
        var scorer = new ExpressionScorer();
        String invalid = export(path(scorer.score("x/x"), scorer.score("1")))
            .replace("\"scoringRevision\":\"" + ScoreRevision.CURRENT + "\"",
                "\"scoringRevision\":" + invalidJson);
        var exception = assertThrows(IllegalArgumentException.class, () -> importer.importJson(invalid));
        assertTrue(exception.getMessage().contains("scoringRevision"));
    }

    private String export(DiscoveredTransformation transformation) {
        return exporter.exportJson(List.of(transformation), List.of());
    }

    private static DiscoveredTransformation path(ExpressionScore before, ExpressionScore after) {
        var step = new TransformationStep(0, "x/x", "1", "cancel", RewriteKind.SIMPLIFY,
            before.weightedTotal(), after.weightedTotal(), true, "requires nonzero x", List.of("x != 0"));
        return new DiscoveredTransformation("score-provenance", "x/x", "1", List.of(step), before, after,
            before.improvementTo(after), CandidateProofStatus.OBSERVED, Instant.EPOCH, "canonical");
    }
}
