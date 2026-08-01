package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionRewriteProgramBaselineAblationPlan.Track;
import de.regelsuche.evolution.EvolutionRewriteProgramBaselineAblationPlan.TrackKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramBaselineAblationPlanTest {
    @Test
    void repeatedConstructionIsCanonicalAndContainsEveryTrack() {
        EvolutionSplitManifest manifest = manifest();
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramBaselineAblationPlan first = create(
            manifest,
            protocol,
            tracks());
        List<Track> reversed = new ArrayList<>(tracks());
        java.util.Collections.reverse(reversed);
        EvolutionRewriteProgramBaselineAblationPlan second = create(
            manifest,
            protocol,
            reversed);

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(TrackKind.values().length, first.tracks().size());
        first.requireInputs(
            manifest,
            protocol,
            hash("primitive-inventory"),
            hash("program-grammar"),
            hash("mutation-catalog"),
            hash("matched-work-budget-policy"));
    }

    @Test
    void missingOrDuplicateTrackKindIsRejected() {
        List<Track> missing = new ArrayList<>(tracks());
        missing.removeLast();
        assertThrows(IllegalArgumentException.class, () -> create(
            manifest(),
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1(),
            missing));

        List<Track> duplicate = new ArrayList<>(tracks());
        duplicate.set(
            duplicate.size() - 1,
            track(TrackKind.FIXED_PRIMITIVE_BEST_FIRST));
        assertThrows(IllegalArgumentException.class, () -> create(
            manifest(),
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1(),
            duplicate));
    }

    @Test
    void randomSeedAndHandWrittenCandidateAreTrackSpecific() {
        assertThrows(IllegalArgumentException.class, () -> new Track(
            TrackKind.FIXED_PRIMITIVE_BEST_FIRST,
            "bad_seed_track",
            hash("impl"),
            hash("config"),
            7L,
            null));
        assertThrows(IllegalArgumentException.class, () -> new Track(
            TrackKind.RANDOMIZED_VALID_PROGRAM,
            "missing_seed_track",
            hash("impl"),
            hash("config"),
            null,
            null));
        assertThrows(IllegalArgumentException.class, () -> new Track(
            TrackKind.HAND_WRITTEN_PROGRAM,
            "missing_program_track",
            hash("impl"),
            hash("config"),
            null,
            null));
        assertThrows(IllegalArgumentException.class, () -> new Track(
            TrackKind.NO_COMPOSITION_ABLATION,
            "unexpected_program_track",
            hash("impl"),
            hash("config"),
            null,
            hash("program")));
    }

    @Test
    void configurationOrSharedSurfaceSubstitutionChangesIdentity() {
        EvolutionSplitManifest manifest = manifest();
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramBaselineAblationPlan base = create(
            manifest,
            protocol,
            tracks());
        List<Track> changedTracks = new ArrayList<>(tracks());
        Track original = changedTracks.getFirst();
        changedTracks.set(0, new Track(
            original.kind(),
            original.trackId(),
            original.implementationHash(),
            hash("changed-configuration"),
            original.randomSeed(),
            original.candidateProgramHash()));
        EvolutionRewriteProgramBaselineAblationPlan changed = create(
            manifest,
            protocol,
            changedTracks);

        assertNotEquals(base.contentHash(), changed.contentHash());
        assertThrows(IllegalArgumentException.class, () -> base.requireInputs(
            manifest,
            protocol,
            hash("different-primitive-inventory"),
            hash("program-grammar"),
            hash("mutation-catalog"),
            hash("matched-work-budget-policy")));
    }

    private static EvolutionRewriteProgramBaselineAblationPlan create(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        List<Track> tracks
    ) {
        return EvolutionRewriteProgramBaselineAblationPlan.create(
            "flagship_baseline_ablation_v1",
            manifest.studyId(),
            manifest.contentHash(),
            protocol.contentHash(),
            hash("primitive-inventory"),
            hash("program-grammar"),
            hash("mutation-catalog"),
            hash("matched-work-budget-policy"),
            tracks);
    }

    private static List<Track> tracks() {
        return Arrays.stream(TrackKind.values())
            .map(EvolutionRewriteProgramBaselineAblationPlanTest::track)
            .toList();
    }

    private static Track track(TrackKind kind) {
        return new Track(
            kind,
            kind.name().toLowerCase(java.util.Locale.ROOT),
            hash("implementation-" + kind.name()),
            hash("configuration-" + kind.name()),
            kind == TrackKind.RANDOMIZED_VALID_PROGRAM ? 20260801L : null,
            kind == TrackKind.HAND_WRITTEN_PROGRAM
                ? hash("frozen-hand-written-program")
                : null);
    }

    private static EvolutionSplitManifest manifest() {
        return EvolutionSplitManifest.create(
            "flagship_baseline_study_v1",
            hash("corpus"),
            hash("features"),
            List.of(reference("train_case", "train_family", "train")),
            List.of(reference(
                "validation_case", "validation_family", "validation")),
            List.of(reference("final_case", "final_family", "final")));
    }

    private static EvolutionSplitManifest.CaseReference reference(
        String caseId,
        String familyId,
        String material
    ) {
        return new EvolutionSplitManifest.CaseReference(
            caseId,
            familyId,
            hash(material + "-exact"),
            hash(material + "-alpha"),
            hash(material + "-input"),
            hash(material + "-target"));
    }

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }
}
