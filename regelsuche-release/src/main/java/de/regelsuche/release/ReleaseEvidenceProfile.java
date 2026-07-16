package de.regelsuche.release;

import de.regelsuche.json.JsonWriter;
import java.util.Arrays;
import java.util.List;

/** Named evidence profiles for Regelsuche 0.2 release claims. */
public enum ReleaseEvidenceProfile {
    SEARCH_REPRODUCIBILITY(
        "reproducible target-free search under pinned inputs",
        false,
        List.of(
            "PINNED_INPUTS",
            "TARGET_FREE",
            "CANONICAL_MANIFEST",
            "THREE_CLEAN_RUNS")),
    HIDDEN_RULE_REDISCOVERY(
        "rediscovery of withheld known rules without target leakage",
        false,
        List.of(
            "HIDDEN_REFERENCE_ISOLATED",
            "HIDDEN_RULE_BENCHMARK_COMPLETE",
            "EXECUTABLE_REDISCOVERY")),
    OPEN_TARGET_DISCOVERY(
        "formation and validation of an open-target conjecture",
        false,
        List.of(
            "TARGET_FREE",
            "MULTI_FAMILY_OBSERVATIONS",
            "AGGREGATE_MINING",
            "EXACT_LINEAGE",
            "REJECTION_EVIDENCE",
            "VALIDATION_COMPLETE",
            "COUNTEREXAMPLE_SEARCH_COMPLETE",
            "PROJECT_NOVELTY_COMPLETE",
            "SYMBOLIC_PROOF_COMPLETE",
            "LIFECYCLE_HANDOFF_COMPLETE")),
    AUTONOMOUS_CAMPAIGN(
        "autonomous generation, validation and retention of mathematical conjectures",
        true,
        List.of(
            "TARGET_FREE",
            "MULTI_FAMILY_OBSERVATIONS",
            "AGGREGATE_MINING",
            "EXACT_LINEAGE",
            "NO_MANDATORY_SKIPPED_WORK",
            "ALPHA_DISTINCT_SUPPORT_AT_LEAST_THREE",
            "HELD_OUT_FAMILY_OR_CLUSTER",
            "BALANCED_RELEASE_HOLDOUT_SUITE",
            "MULTIPLE_COUNTEREXAMPLE_STRATEGIES",
            "NO_REFUTATION",
            "PROJECT_NOVELTY_COMPLETE",
            "SYMBOLIC_PROOF_COMPLETE",
            "NO_UNRESOLVED_ASSUMPTIONS",
            "LIFECYCLE_HANDOFF_COMPLETE",
            "PAIRED_HELD_OUT_UTILITY",
            "THREE_CLEAN_RUNS")),
    EXTERNAL_NOVELTY_REVIEW(
        "externally reviewed mathematical novelty",
        false,
        List.of(
            "PROJECT_NOVELTY_COMPLETE",
            "EXTERNAL_NOVELTY_REVIEWED",
            "PUBLIC_EVIDENCE_REVIEWED"));

    public static final String CATALOG_SCHEMA =
        "regelsuche.release-evidence-profile-catalog/v1";

    private final String claim;
    private final boolean authorizesAutonomyClaim;
    private final List<String> requirements;

    ReleaseEvidenceProfile(
        String claim,
        boolean authorizesAutonomyClaim,
        List<String> requirements
    ) {
        this.claim = claim;
        this.authorizesAutonomyClaim = authorizesAutonomyClaim;
        this.requirements = List.copyOf(requirements);
    }

    public String claim() {
        return claim;
    }

    public boolean authorizesAutonomyClaim() {
        return authorizesAutonomyClaim;
    }

    public List<String> requirements() {
        return requirements;
    }

    public static String catalogJson() {
        return new JsonWriter().beginObject()
            .property("schema", CATALOG_SCHEMA)
            .array("profiles", array -> Arrays.stream(values()).forEach(profile ->
                array.objectValue(object -> object
                    .property("profile", profile.name())
                    .property("claim", profile.claim())
                    .property("authorizesAutonomyClaim",
                        profile.authorizesAutonomyClaim())
                    .stringArray("requirements", profile.requirements()))))
            .property("autonomyClaimProfile", AUTONOMOUS_CAMPAIGN.name())
            .property("externalNoveltyProfile", EXTERNAL_NOVELTY_REVIEW.name())
            .endObject()
            .toString();
    }
}
