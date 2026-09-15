package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.StateSnapshot;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchState;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousSnapshotScoringTest {
    @Test void miningRecomputesUnversionedSnapshotScoresWithoutChangingRetainedEvidence() throws Exception {
        var snapshot = new StateSnapshot("x", 1, "retained-value-hash", 987654,
            List.of("x + 0", "x"), List.of("remove-zero"), List.of(true), List.of("x != 0"));
        String before = snapshot.canonicalMaterial();
        var method = AutonomousProductionMiningRunner.class.getDeclaredMethod("replayState", StateSnapshot.class);
        method.setAccessible(true);
        var replayed = (SearchState) method.invoke(null, snapshot);
        assertEquals(new ExpressionScorer().score("x"), replayed.score());
        assertEquals(before, snapshot.canonicalMaterial());
        assertEquals(987654, snapshot.scoreWeightedTotal());
        assertEquals(snapshot.path(), replayed.path());
        assertEquals(snapshot.appliedRuleIds(), replayed.appliedRuleIds());
        assertEquals(snapshot.assumptions(), replayed.assumptions());
        assertEquals(snapshot.canonicalHash(), replayed.canonicalHash());
    }
}
