package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

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
}
