package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpressionScoreRevisionTest {
    @Test void equalNumbersWithoutAProducerAreNotAnIdentifiedBuiltInScore() {
        var identified = new ExpressionScorer().score("x + 0");
        var unknown = new ExpressionScore(identified.stringLength(), identified.astNodeCount(), identified.operatorCount(),
            identified.nestingDepth(), identified.recognizedPatternBonus());
        assertNotEquals(unknown, identified);
        assertEquals(unknown.weightedTotal(), identified.weightedTotal());
    }

    @Test void improvementCannotMixAnUnspecifiedScoreWithABuiltInScore() {
        var unknown = new ExpressionScore(3, 3, 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> unknown.improvementTo(new ExpressionScorer().score("x")));
        assertEquals(5, unknown.improvementTo(new ExpressionScore(1, 1, 0, 0, 0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u2003"})
    void suppliedBlankRevisionsCannotBecomeHistoricalMissingMetadata(String revision) {
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.normalize(revision));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionScore(1, 1, 0, 0, 0, revision));
    }

    @Test void missingMetadataStillHasAnExplicitHistoricalIdentity() {
        assertEquals(ScoreRevision.UNSPECIFIED, ScoreRevision.normalize(null));
        assertEquals(ScoreRevision.UNSPECIFIED, new ExpressionScore(1, 1, 0, 0, 0, null).scoringRevision());
        assertEquals("custom/v1", ScoreRevision.normalize("custom/v1"));
    }
}
