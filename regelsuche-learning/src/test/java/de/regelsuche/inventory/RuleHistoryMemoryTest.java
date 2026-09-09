package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.Transformation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleHistoryMemoryTest {
    @TempDir Path directory;
    private static MoveProvider provider(String id, de.regelsuche.transform.TransformationEngine engine) {
        return new EngineMoveProvider(new MoveProvider.Descriptor(id, id, SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture"), engine, true);
    }
    private static MoveSearch.Result run(List<MoveProvider> providers, String target) {
        return new MoveSearch().search(new MoveSearch.Problem("a", MoveContext.frozen(target), providers, MovePriorityPolicy.INVENTORY_ORDER,
            (s, m, c) -> new MoveVerifier.Verification(true, 3, List.of("graph-fixture"), "TEST_ONLY"), s -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 0, 20, 10000)));
    }
    @Test void continuationIsContextualAndFrozenSnapshotsDoNotLearnFromEvaluation() throws Exception {
        var normalize = provider("normalize", s -> s.equals("a") ? List.of(new Transformation("normalize", "b")) : List.of());
        var cancel = provider("cancel", s -> s.equals("b") ? List.of(new Transformation("cancel", "c")) : List.of());
        var memory = new RuleHistoryMemory(); var empty = memory.freeze();
        var result = run(List.of(normalize, cancel), "c"); assertTrue(result.reached());
        for (int i = 0; i < 3; i++) memory.observe(result, MoveContext.Phase.TRAIN, Map.of("cancel", 10L));
        var frozen = memory.freeze(); var source = result.witness().getLast().source();
        String key = StructuralMoveContext.of(source).key();
        assertEquals(3, frozen.continuation(key, "normalize", "cancel").success());
        assertEquals(0, frozen.continuation(key, "unrelated", "cancel").success());
        assertEquals(10, frozen.family(key, "cancel").averageWorkSaved());
        assertTrue(empty.families().isEmpty());
        var policy = new HistoryMovePolicy(frozen, HistoryMovePolicy.Weights.DEFAULT);
        assertEquals(MovePriorityPolicy.Stage.PRINCIPAL_HISTORY, policy.stage(cancel.descriptor(), source, MoveContext.frozen("c")));
        var unrelated = new MoveState("a+b", 1, 1, "normalize", List.of(), java.util.Set.of(), 0);
        assertEquals(MovePriorityPolicy.Stage.NORMAL_PRIMITIVE, policy.stage(cancel.descriptor(), unrelated, MoveContext.frozen("c")));
        var step = result.witness().getLast().move();
        assertTrue(policy.score(step, source, MoveContext.frozen("c")) > new HistoryMovePolicy(empty,
            HistoryMovePolicy.Weights.DEFAULT).score(step, source, MoveContext.frozen("c")));
        assertThrows(IllegalArgumentException.class, () -> memory.observe(result, MoveContext.Phase.FROZEN_EVALUATION, Map.of()));
        assertEquals(frozen, memory.freeze());
        var path = directory.resolve("history.json"); frozen.persistTo(path); assertEquals(frozen, RuleHistoryMemory.Snapshot.load(path));
        assertEquals(StructuralMoveContext.of(MoveState.root("a*a+a*b")).key(), StructuralMoveContext.of(MoveState.root("x*x+x*y")).key());
        assertEquals(-1, StructuralMoveContext.of(MoveState.root("sin(x)")).degree());
    }
    @Test void duplicatesAndInspectedDeadEndsArePenaltiesAndFeatureWorkIsCharged() {
        var rule = provider("rule", s -> s.equals("a") ? List.of(new Transformation("rule", "b"), new Transformation("rule", "b")) : List.of());
        var result = run(List.of(rule), "absent"); assertTrue(result.completeBoundedRelation());
        var memory = new RuleHistoryMemory(); memory.observe(result, MoveContext.Phase.TRAIN, Map.of());
        var stats = memory.freeze().family(StructuralMoveContext.of(MoveState.root("a")).key(), "rule");
        assertEquals(2, stats.applications()); assertEquals(1, stats.failure()); assertEquals(1, stats.duplicates()); assertEquals(0, stats.success());
        var activity = new RuleActivityMemory(List.of("rule")); activity.observe(result, MoveContext.Phase.TRAIN, Map.of());
        assertEquals(1, activity.freeze().rules().get("rule").deadEnds()); assertEquals(1, activity.freeze().rules().get("rule").duplicates());
        assertTrue(activity.freeze().rules().get("rule").activity() < 0);
        var state = MoveState.root("a*a+a*b"); var context = MoveContext.frozen("b");
        var policy = new ActivityMovePolicy(activity.freeze(), new HistoryMovePolicy(memory.freeze(), HistoryMovePolicy.Weights.DEFAULT));
        var picker = new StagedMovePicker(List.of(rule), policy, state, context);
        assertEquals(2L * StructuralMoveContext.of(state).visitedNodes(), picker.workMetrics().delegatedMechanicalWorkUnits());
        assertThrows(IllegalArgumentException.class, () -> new HistoryMovePolicy.Weights(Double.NaN, 1, 1, 1, 1, 1, 1, 1));
    }
}
