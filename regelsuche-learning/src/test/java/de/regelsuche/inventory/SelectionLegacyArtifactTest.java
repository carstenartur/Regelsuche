package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.moves.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionLegacyArtifactTest {
    @Test void sourceQualityV2BytesRemainFrozen() {
        var profile = new TypedSourcePolicySelection.Profile("plain", List.of(), MovePriorityPolicy.INVENTORY_ORDER);
        var observation = new TypedSourcePolicySelection.Observation("t", 3, 2, 9, true,
            MoveSearch.Outcome.INCONCLUSIVE, 11, 12, 13);
        var frozen = new TypedSourcePolicySelection.Frozen(profile,
            List.of(new TypedSourcePolicySelection.Trial(profile, List.of(observation))), List.of("source"),
            new TypedSourcePolicySelection.QualityGoal(2, SearchContinuationContract.PATH_SENSITIVE));
        assertEquals("sha256:be95a6e1002ba1ee4f783ea5dd1bbaa81c54c93fe417b25ef3fca251b23908ae", hash(frozen.toCanonicalJson()));
        assertTrue(frozen.toCanonicalJson().contains(TypedSourcePolicySelection.QUALITY_REVISION));
    }
    @Test void targetSelectionV2BytesRemainFrozen() {
        var selector = new TypedPolicySelection();
        var tasks = List.of(task("a"), task("b"));
        var frozen = selector.train(new RuleHistoryMemory().freeze(), tasks,
            List.of(TypedPolicySelection.Profile.inventoryOrder("plain")));
        assertEquals("sha256:57e694c1eae3c951f4163ec1fa24067d5561491958a39688f8f039c14aef2e27", hash(frozen.toCanonicalJson()));
        assertTrue(frozen.toCanonicalJson().contains(TypedPolicySelection.REVISION));
    }
    private static String hash(String text) {
        try { return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
    private static TypedPolicySelection.TrainingTask task(String name) {
        var target = new de.regelsuche.ast.VariableExpr(name);
        return new TypedPolicySelection.TrainingTask(name, new TypedMoveSearch.Problem(
            new de.regelsuche.ast.BinaryExpr(target, de.regelsuche.ast.BinaryOperator.ADD, new de.regelsuche.ast.NumberExpr(0)),
            new TypedMoveSearch.Context(target, List.of(), MoveContext.Phase.TRAIN), WorkReplacementExperimentTest.PROFILE.providers(),
            MovePriorityPolicy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(WorkReplacementExperimentTest.TRANSPORT), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 100, 16, 10000)));
    }
}
