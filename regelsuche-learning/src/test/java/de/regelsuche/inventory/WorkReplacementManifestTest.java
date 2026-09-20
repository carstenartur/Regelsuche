package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import de.regelsuche.search.moves.SearchContinuationContract;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkReplacementManifestTest {
    @Test void roleBoundRevisionsAndObservationModeCannotBeSwapped() {
        var manifest = manifest(Profile.LOADED_STREAM, 1000, partitions());
        for (var arm : Arm.values()) manifest.validateBinding(arm, manifest.binding(arm));
        assertThrows(IllegalArgumentException.class, () -> manifest.validateBinding(Arm.L1,
            new Binding("improved/v3", "rules/v1", "checker/v1", "model/v1", ObservationMode.PROFILE)));
        assertThrows(IllegalArgumentException.class, () -> manifest.validateBinding(Arm.B1,
            new Binding("improved/v3", "rules/v1", "NONE", "checker/v1", ObservationMode.QUIET_TIMING)));
        assertEquals(manifest.binding(Arm.B1).execution(), manifest.binding(Arm.L1).execution());
        assertNotEquals(manifest.binding(Arm.B0).execution(), manifest.binding(Arm.B1).execution());
    }
    @Test void finalTestSourcesAndTheirDerivedFormsCannotFlowBackToTraining() {
        assertThrows(IllegalArgumentException.class, () -> manifest(Profile.LOADED_STREAM, 100,
            List.of(new Partition("final", "f", "final-family", Split.FINAL_TEST, List.of()),
                new Partition("train", "renamed", "train-family", Split.TRAIN, List.of("final")))));
        assertThrows(IllegalArgumentException.class, () -> manifest(Profile.LOADED_STREAM, 100,
            List.of(new Partition("final", "same", "final-family", Split.FINAL_TEST, List.of()),
                new Partition("train", "same", "train-family", Split.TRAIN, List.of()))));
        var manifest = manifest(Profile.LOADED_STREAM, 100, partitions());
        manifest.requireTrainingSources(List.of("train-source"));
        assertThrows(IllegalArgumentException.class, () -> manifest.requireTrainingSources(List.of(WorkReplacementLearning.identity("x+0"))));
    }
    @Test void aRenamedTemplateIsNotAnIndependentFamilyHoldout() {
        var base = manifest(Profile.LOADED_STREAM, 100, partitions());
        assertThrows(IllegalArgumentException.class, () -> new WorkReplacementManifest(base.baselineCommit(), base.revisions(),
            "SEALED_FAMILY_HOLDOUT", base.quality(), base.seeds(), base.resources(), base.profile(), base.observationMode(),
            base.unsolvedPolicy(), List.of(new Partition("a", "a", "same-family", Split.TRAIN, List.of()),
                new Partition("b", "b", "same-family", Split.FINAL_TEST, List.of()))));
    }
    static List<Partition> partitions() {
        return List.of(new Partition("train", "train-source", "train-family", Split.TRAIN, List.of()),
            new Partition("query", WorkReplacementLearning.identity("x+0"), "query-family", Split.FINAL_TEST, List.of()));
    }
    static WorkReplacementManifest manifest(Profile profile, long budget, List<Partition> partitions) {
        return new WorkReplacementManifest("7aec9ae0a1619dda98f859d1277ac8b287471423",
            new Revisions("historical/v1", "improved/v3", "rules/v1", "model/v1", "checker/v1"),
            "PUBLIC_DEVELOPMENT", new Quality("node-count/v1", QualityMode.SUFFICIENT_QUALITY_MIN_WORK, 1,
                SearchContinuationContract.PATH_SENSITIVE), List.of(17L),
            new Resources(budget, 60_000_000_000L, 64, 8, "REMAINING_EQUAL_SHARE_WITH_CARRY"), profile,
            ObservationMode.PROFILE, UnsolvedPolicy.NO_RUNTIME_RATIO, partitions);
    }
}
