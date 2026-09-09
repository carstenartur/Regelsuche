package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.Transformation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleActivityMemoryTest {
    @Test void invalidTrainFeedbackDoesNotAgeOrMeterTheMemory() {
        var memory = new RuleActivityMemory(List.of("rule"));
        var initial = memory.freeze();
        assertThrows(IllegalArgumentException.class, () -> memory.observe(null, MoveContext.Phase.TRAIN, Map.of()));
        assertEquals(initial, memory.freeze()); assertEquals(0, memory.measuredWork());
        assertThrows(IllegalArgumentException.class, () -> memory.observe(successfulRun(), MoveContext.Phase.TRAIN, null));
        assertEquals(initial, memory.freeze()); assertEquals(0, memory.measuredWork());
        memory.observe(successfulRun(), MoveContext.Phase.TRAIN, Map.of());
        assertTrue(memory.measuredWork() > 0);
    }
    @TempDir Path directory;
    private MoveSearch.Result successfulRun() {
        var descriptor = new MoveProvider.Descriptor("rule", "family", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.EMPIRICAL, List.of(), SearchMove.ValueEvidence.UNKNOWN, "empirical-receipt");
        return new MoveSearch().search(new MoveSearch.Problem("a", MoveContext.frozen("b"),
            List.of(new EngineMoveProvider(descriptor, state -> state.equals("a") ? List.of(new Transformation("rule", "b")) : List.of(), true)),
            MovePriorityPolicy.INVENTORY_ORDER, (s, m, c) -> new MoveVerifier.Verification(true, 1, List.of("fixture"), "TEST_GRAPH"),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(2, 2, 0, 10, 1000)));
    }
    @Test void successfulFeedbackPromotesActivityWhileProofRemainsEmpiricalAndSnapshotCannotTrain() throws Exception {
        var memory = new RuleActivityMemory(List.of("rule", "unused"));
        var initial = memory.freeze(); var result = successfulRun(); assertTrue(result.reached());
        for (int i = 0; i < 5; i++) memory.observe(result, MoveContext.Phase.TRAIN, Map.of("rule", 20L));
        var hot = memory.freeze(); assertEquals(RuleActivityMemory.Tier.HOT, hot.tier("rule"));
        assertEquals(RuleActivityMemory.Tier.SHADOW, hot.tier("unused")); assertEquals(100, hot.rules().get("rule").measuredWorkSaved());
        assertEquals(SearchMove.ProofStrength.EMPIRICAL, result.witness().getFirst().move().proofStrength());
        assertEquals(0, initial.rules().get("rule").successes());
        assertThrows(IllegalArgumentException.class, () -> memory.observe(result, MoveContext.Phase.FROZEN_EVALUATION, Map.of()));
        assertEquals(hot, memory.freeze());
        var path = directory.resolve("activity.json"); hot.persistTo(path); assertEquals(hot, RuleActivityMemory.Snapshot.load(path));
        for (int i = 0; i < 150; i++) memory.age(MoveContext.Phase.TRAIN);
        assertEquals(RuleActivityMemory.Tier.COLD, memory.freeze().tier("rule"));
        assertEquals(hot.rules().keySet(), memory.freeze().rules().keySet());
        var policy = new ActivityMovePolicy(memory.freeze(), MovePriorityPolicy.INVENTORY_ORDER);
        var descriptor = new MoveProvider.Descriptor("rule", "family", SearchMove.SourceKind.LEARNED, SearchMove.ProofStrength.VERIFIED,
            List.of(), new SearchMove.ValueEvidence(1, 0, 5, 3, 1, true, "ref"), "proof");
        assertEquals(MovePriorityPolicy.Stage.EXPLORATION, policy.stage(descriptor, MoveState.root("a"), MoveContext.frozen("b")));
        var picker = new StagedMovePicker(List.of(new EngineMoveProvider(descriptor, s -> List.of(new Transformation("rule", "b")), true)),
            policy, MoveState.root("a"), MoveContext.frozen("b"));
        assertTrue(picker.next().isPresent(), "cold verified knowledge is delayed, never deleted");
    }
}
