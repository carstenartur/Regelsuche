package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExpressionScoreRevisionTest {
    @Test void rawNumbersAreNotSilentlyRelabelledAsNewlyProducedScores() {
        assertNotEquals(new ExpressionScore(1, 1, 0, 0, 0), new ExpressionScorer().score("x"));
    }
    @Test void incompatibleProducerRevisionsCannotBeUsedToComputeAnImprovement() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExpressionScorer().score("x").improvementTo(new ExpressionScore(1, 1, 0, 0, 0)));
    }
}
