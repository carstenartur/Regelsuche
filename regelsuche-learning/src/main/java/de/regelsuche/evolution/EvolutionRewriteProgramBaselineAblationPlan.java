package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed, pre-execution plan for every flagship baseline and
 * ablation track.
 *
 * <p>All tracks share the same frozen information surface, evaluation protocol,
 * primitive inventory and matched primitive/total-work policy. A track may
 * differ only through its explicitly hashed implementation/configuration and
 * the semantics declared by {@link TrackKind}.</p>
 */
public record EvolutionRewriteProgramBaselineAblationPlan(
    String schema,
    String planId,
    String studyId,
    String splitManifestHash,
    String evaluationProtocolHash,
    String primitiveInventoryHash,
    String programGrammarHash,
    String mutationCatalogHash,
    String workBudgetPolicyHash,
    WorkAccountingPolicy workAccountingPolicy,
    List<Track> tracks,
    PlanStatus status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-baseline-ablation-plan/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramBaselineAblationPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program baseline/ablation-plan schema");
        }
        requireId(planId, "planId");
        requireId(studyId, "studyId");
        requireHashes(
            splitManifestHash,
            evaluationProtocolHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash);
        if (workAccountingPolicy
                != WorkAccountingPolicy.MATCHED_PRIMITIVE_AND_TOTAL_WORK
                || status != PlanStatus.NOT_STARTED) {
            throw new IllegalArgumentException(
                "baseline/ablation plan must remain matched-work and pre-execution");
        }
        tracks = canonicalTracks(tracks);
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            planId,
            studyId,
            splitManifestHash,
            evaluationProtocolHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash,
            workAccountingPolicy,
            tracks,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "baseline/ablation-plan contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramBaselineAblationPlan create(
        String planId,
        String studyId,
        String splitManifestHash,
        String evaluationProtocolHash,
        String primitiveInventoryHash,
        String programGrammarHash,
        String mutationCatalogHash,
        String workBudgetPolicyHash,
        List<Track> tracks
    ) {
        requireId(planId, "planId");
        requireId(studyId, "studyId");
        requireHashes(
            splitManifestHash,
            evaluationProtocolHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash);
        List<Track> canonical = canonicalTracks(tracks);
        String hash = EvolutionGenome.hash(render(
            planId,
            studyId,
            splitManifestHash,
            evaluationProtocolHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash,
            WorkAccountingPolicy.MATCHED_PRIMITIVE_AND_TOTAL_WORK,
            canonical,
            null));
        return new EvolutionRewriteProgramBaselineAblationPlan(
            SCHEMA,
            planId,
            studyId,
            splitManifestHash,
            evaluationProtocolHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash,
            WorkAccountingPolicy.MATCHED_PRIMITIVE_AND_TOTAL_WORK,
            canonical,
            PlanStatus.NOT_STARTED,
            hash);
    }

    public void requireInputs(
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol,
        String primitiveInventoryHash,
        String programGrammarHash,
        String mutationCatalogHash,
        String workBudgetPolicyHash
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(evaluationProtocol, "evaluationProtocol");
        requireHashes(
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash);
        if (!studyId.equals(splitManifest.studyId())
                || !this.splitManifestHash.equals(splitManifest.contentHash())
                || !this.evaluationProtocolHash.equals(
                    evaluationProtocol.contentHash())
                || !this.primitiveInventoryHash.equals(primitiveInventoryHash)
                || !this.programGrammarHash.equals(programGrammarHash)
                || !this.mutationCatalogHash.equals(mutationCatalogHash)
                || !this.workBudgetPolicyHash.equals(workBudgetPolicyHash)) {
            throw new IllegalArgumentException(
                "baseline/ablation plan input identity mismatch");
        }
    }

    public String toCanonicalJson() {
        return render(
            planId,
            studyId,
            splitManifestHash,
            evaluationProtocolHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            workBudgetPolicyHash,
            workAccountingPolicy,
            tracks,
            contentHash);
    }

    private static List<Track> canonicalTracks(List<Track> values) {
        Objects.requireNonNull(values, "tracks");
        List<Track> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "track"))
            .sorted(Comparator.comparing(track -> track.kind().name()))
            .toList();
        if (result.size() != TrackKind.values().length) {
            throw new IllegalArgumentException(
                "baseline/ablation plan must contain every required track exactly once");
        }
        Set<TrackKind> kinds = new HashSet<>();
        Set<String> trackIds = new HashSet<>();
        for (Track track : result) {
            if (!kinds.add(track.kind())) {
                throw new IllegalArgumentException(
                    "baseline/ablation plan contains duplicate track kind");
            }
            if (!trackIds.add(track.trackId())) {
                throw new IllegalArgumentException(
                    "baseline/ablation plan contains duplicate track ID");
            }
        }
        if (!kinds.equals(Set.copyOf(Arrays.asList(TrackKind.values())))) {
            throw new IllegalArgumentException(
                "baseline/ablation plan omits a required track kind");
        }
        return List.copyOf(result);
    }

    private static String render(
        String planId,
        String studyId,
        String splitManifestHash,
        String evaluationProtocolHash,
        String primitiveInventoryHash,
        String programGrammarHash,
        String mutationCatalogHash,
        String workBudgetPolicyHash,
        WorkAccountingPolicy workAccountingPolicy,
        List<Track> tracks,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("planId", planId)
            .property("studyId", studyId)
            .property("splitManifestHash", splitManifestHash)
            .property("evaluationProtocolHash", evaluationProtocolHash)
            .property("primitiveInventoryHash", primitiveInventoryHash)
            .property("programGrammarHash", programGrammarHash)
            .property("mutationCatalogHash", mutationCatalogHash)
            .property("workBudgetPolicyHash", workBudgetPolicyHash)
            .property("workAccountingPolicy", workAccountingPolicy.name())
            .array("tracks", array -> tracks.forEach(track ->
                array.objectValue(object -> {
                    object.property("kind", track.kind().name())
                        .property("trackId", track.trackId())
                        .property("implementationHash", track.implementationHash())
                        .property("configurationHash", track.configurationHash())
                        .property("informationSurface", "FROZEN_SHARED_SURFACE")
                        .property("workAccountingPolicy",
                            WorkAccountingPolicy.MATCHED_PRIMITIVE_AND_TOTAL_WORK.name());
                    if (track.randomSeed() != null) {
                        object.property("randomSeed", track.randomSeed());
                    }
                    if (track.candidateProgramHash() != null) {
                        object.property(
                            "candidateProgramHash", track.candidateProgramHash());
                    }
                })))
            .property("status", PlanStatus.NOT_STARTED.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void requireHashes(String... values) {
        for (int index = 0; index < values.length; index++) {
            EvolutionGenome.requireSha256(values[index], "hash[" + index + "]");
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }

    public enum WorkAccountingPolicy {
        MATCHED_PRIMITIVE_AND_TOTAL_WORK
    }

    public enum PlanStatus {
        NOT_STARTED
    }

    public enum TrackKind {
        FIXED_PRIMITIVE_BEST_FIRST,
        EQUALITY_SATURATION_SHARED_FRAGMENT,
        RANDOMIZED_VALID_PROGRAM,
        MUTATION_ONLY_NO_TOPOLOGY,
        HAND_WRITTEN_PROGRAM,
        NO_COMPOSITION_ABLATION,
        FIXED_GUARD_ABLATION,
        FLATTENED_PROGRAM_OUTER_SEARCH
    }

    public record Track(
        TrackKind kind,
        String trackId,
        String implementationHash,
        String configurationHash,
        Long randomSeed,
        String candidateProgramHash
    ) {
        public Track {
            Objects.requireNonNull(kind, "kind");
            requireId(trackId, "trackId");
            EvolutionGenome.requireSha256(
                implementationHash, "implementationHash");
            EvolutionGenome.requireSha256(
                configurationHash, "configurationHash");
            boolean randomized = kind == TrackKind.RANDOMIZED_VALID_PROGRAM;
            if (randomized != (randomSeed != null)) {
                throw new IllegalArgumentException(
                    "only randomized-valid-program track carries a random seed");
            }
            boolean handWritten = kind == TrackKind.HAND_WRITTEN_PROGRAM;
            if (handWritten != (candidateProgramHash != null)) {
                throw new IllegalArgumentException(
                    "only hand-written-program track carries a candidate hash");
            }
            if (candidateProgramHash != null) {
                EvolutionGenome.requireSha256(
                    candidateProgramHash, "candidateProgramHash");
            }
        }
    }
}
