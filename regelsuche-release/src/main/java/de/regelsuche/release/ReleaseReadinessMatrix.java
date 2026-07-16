package de.regelsuche.release;

import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Fail-closed evaluation of the named Regelsuche 0.2 evidence profiles. */
public final class ReleaseReadinessMatrix {
    public static final String SCHEMA =
        "regelsuche.release-readiness-matrix/v1";
    private static final int MIN_RELEASE_POSITIVE_HOLDOUTS = 12;
    private static final int MIN_RELEASE_NEGATIVE_HOLDOUTS = 12;

    private ReleaseReadinessMatrix() {
    }

    public static MatrixReport evaluate(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        Objects.requireNonNull(evidence, "evidence");
        List<ProfileResult> results = Arrays.stream(
                ReleaseEvidenceProfile.values())
            .map(profile -> evaluate(profile, evidence))
            .sorted(Comparator.comparing(result -> result.profile().name()))
            .toList();
        ProfileResult autonomy = findResult(
            results, ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN);
        return new MatrixReport(
            SCHEMA,
            evidence.evidenceHash(),
            results,
            autonomy.status(),
            autonomy.autonomyClaimAuthorized(),
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            matrixHash(evidence.evidenceHash(), results, autonomy.status()));
    }

    private static ProfileResult evaluate(
        ReleaseEvidenceProfile profile,
        AutonomousCampaignReleaseEvidence evidence
    ) {
        List<RequirementCheck> checks = switch (profile) {
            case SEARCH_REPRODUCIBILITY -> searchChecks(evidence);
            case HIDDEN_RULE_REDISCOVERY -> hiddenRuleChecks(evidence);
            case OPEN_TARGET_DISCOVERY -> openTargetChecks(evidence);
            case AUTONOMOUS_CAMPAIGN -> autonomousCampaignChecks(evidence);
            case EXTERNAL_NOVELTY_REVIEW -> externalNoveltyChecks(evidence);
        };
        ProfileStatus status = checks.stream().allMatch(RequirementCheck::passed)
            ? ProfileStatus.READY
            : ProfileStatus.BLOCKED;
        return new ProfileResult(
            profile,
            status,
            profile.authorizesAutonomyClaim() && status == ProfileStatus.READY,
            checks,
            checks.stream()
                .filter(check -> !check.passed())
                .map(RequirementCheck::code)
                .sorted()
                .toList());
    }

    private static List<RequirementCheck> searchChecks(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return List.of(
            check("PINNED_INPUTS", true,
                "versioned production brief", "versioned production brief"),
            check("TARGET_FREE", evidence.targetFree(),
                evidence.targetFree(), true),
            check("CANONICAL_MANIFEST",
                isSha256(evidence.campaignManifestHash()),
                evidence.campaignManifestHash(), "sha256"),
            check("THREE_CLEAN_RUNS",
                evidence.cleanRunCount() >= 3 && evidence.cleanRunsIdentical(),
                cleanRunSummary(evidence),
                ">=3 identical canonical manifests"));
    }

    private static List<RequirementCheck> hiddenRuleChecks(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return List.of(
            check("HIDDEN_REFERENCE_ISOLATED",
                evidence.hiddenReferenceIsolated(),
                evidence.hiddenReferenceIsolated(), true),
            check("HIDDEN_RULE_BENCHMARK_COMPLETE",
                evidence.hiddenRuleBenchmarkComplete(),
                evidence.hiddenRuleBenchmarkComplete(), true),
            check("EXECUTABLE_REDISCOVERY",
                evidence.executableRediscoveryRetained(),
                evidence.executableRediscoveryRetained(), true));
    }

    private static List<RequirementCheck> openTargetChecks(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return List.of(
            check("TARGET_FREE", evidence.targetFree(),
                evidence.targetFree(), true),
            check("MULTI_FAMILY_OBSERVATIONS",
                evidence.seedFamilyCount() >= 2
                    && evidence.observationCount() >= 12,
                evidence.seedFamilyCount() + " families / "
                    + evidence.observationCount() + " observations",
                ">=2 families / >=12 observations"),
            check("AGGREGATE_MINING", evidence.aggregateMiningComplete(),
                evidence.aggregateMiningComplete(), true),
            check("EXACT_LINEAGE", evidence.exactSupportingLineage(),
                evidence.exactSupportingLineage(), true),
            check("REJECTION_EVIDENCE",
                evidence.rejectedClusterCount() >= 1,
                evidence.rejectedClusterCount(), ">=1"),
            check("VALIDATION_COMPLETE",
                developmentHoldoutsComplete(evidence)
                    && evidence.refutingHoldouts() == 0,
                holdoutSummary(evidence),
                "all configured positive and negative holdouts; zero refuting"),
            check("COUNTEREXAMPLE_SEARCH_COMPLETE",
                evidence.counterexampleStrategyCount() >= 2
                    && evidence.counterexamplesFound() == 0,
                evidence.counterexampleStrategyCount()
                    + " strategies; found=" + evidence.counterexamplesFound(),
                ">=2 strategies; zero counterexamples"),
            check("PROJECT_NOVELTY_COMPLETE",
                "NOVEL_WITHIN_PROJECT".equals(
                    evidence.projectNoveltyStatus()),
                evidence.projectNoveltyStatus(), "NOVEL_WITHIN_PROJECT"),
            check("SYMBOLIC_PROOF_COMPLETE",
                "SYMBOLICALLY_VERIFIED".equals(
                    evidence.symbolicProofStatus()),
                evidence.symbolicProofStatus(), "SYMBOLICALLY_VERIFIED"),
            check("LIFECYCLE_HANDOFF_COMPLETE",
                evidence.lifecycleHandoffComplete(),
                evidence.lifecycleHandoffComplete(), true));
    }

    private static List<RequirementCheck> autonomousCampaignChecks(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        List<RequirementCheck> checks = new ArrayList<>(openTargetChecks(evidence));
        checks.removeIf(check -> check.code().equals("VALIDATION_COMPLETE")
            || check.code().equals("COUNTEREXAMPLE_SEARCH_COMPLETE"));
        checks.add(check("NO_MANDATORY_SKIPPED_WORK",
            evidence.mandatorySkippedWorkCount() == 0,
            evidence.mandatorySkippedWorkCount(), 0));
        checks.add(check("ALPHA_DISTINCT_SUPPORT_AT_LEAST_THREE",
            evidence.alphaDistinctSupport() >= 3,
            evidence.alphaDistinctSupport(), ">=3"));
        checks.add(check("HELD_OUT_FAMILY_OR_CLUSTER",
            evidence.heldOutFamilyOrClusterCount() >= 1,
            evidence.heldOutFamilyOrClusterCount(), ">=1"));
        checks.add(check("BALANCED_RELEASE_HOLDOUT_SUITE",
            releaseHoldoutsComplete(evidence)
                && evidence.refutingHoldouts() == 0,
            holdoutSummary(evidence),
            ">=" + MIN_RELEASE_POSITIVE_HOLDOUTS + " positive and >="
                + MIN_RELEASE_NEGATIVE_HOLDOUTS
                + " negative; fully executed; zero refuting"));
        checks.add(check("MULTIPLE_COUNTEREXAMPLE_STRATEGIES",
            evidence.counterexampleStrategyCount() >= 2,
            evidence.counterexampleStrategyCount(), ">=2"));
        checks.add(check("NO_REFUTATION",
            evidence.refutingHoldouts() == 0
                && evidence.counterexamplesFound() == 0,
            "holdouts=" + evidence.refutingHoldouts()
                + "; counterexamples=" + evidence.counterexamplesFound(),
            "zero"));
        checks.add(check("NO_UNRESOLVED_ASSUMPTIONS",
            evidence.unresolvedAssumptionCount() == 0,
            evidence.unresolvedAssumptionCount(), 0));
        checks.add(check("PAIRED_HELD_OUT_UTILITY",
            evidence.pairedHeldOutUtilityEvaluated()
                && evidence.pairedUtilityPermille() > 0,
            "evaluated=" + evidence.pairedHeldOutUtilityEvaluated()
                + "; gainPermille=" + evidence.pairedUtilityPermille(),
            "evaluated with positive held-out gain"));
        checks.add(check("THREE_CLEAN_RUNS",
            evidence.cleanRunCount() >= 3 && evidence.cleanRunsIdentical(),
            cleanRunSummary(evidence),
            ">=3 identical canonical manifests"));
        return checks.stream()
            .sorted(Comparator.comparing(RequirementCheck::code))
            .toList();
    }

    private static List<RequirementCheck> externalNoveltyChecks(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return List.of(
            check("PROJECT_NOVELTY_COMPLETE",
                "NOVEL_WITHIN_PROJECT".equals(
                    evidence.projectNoveltyStatus()),
                evidence.projectNoveltyStatus(), "NOVEL_WITHIN_PROJECT"),
            check("EXTERNAL_NOVELTY_REVIEWED",
                !"NOT_EVALUATED".equals(evidence.externalNoveltyStatus()),
                evidence.externalNoveltyStatus(), "externally reviewed verdict"),
            check("PUBLIC_EVIDENCE_REVIEWED",
                evidence.publicEvidenceReviewed(),
                evidence.publicEvidenceReviewed(), true));
    }

    private static boolean developmentHoldoutsComplete(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return evidence.configuredPositiveHoldouts() > 0
            && evidence.configuredNegativeHoldouts() > 0
            && evidence.executedPositiveHoldouts()
                == evidence.configuredPositiveHoldouts()
            && evidence.executedNegativeHoldouts()
                == evidence.configuredNegativeHoldouts();
    }

    private static boolean releaseHoldoutsComplete(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return evidence.configuredPositiveHoldouts()
                >= MIN_RELEASE_POSITIVE_HOLDOUTS
            && evidence.configuredNegativeHoldouts()
                >= MIN_RELEASE_NEGATIVE_HOLDOUTS
            && evidence.executedPositiveHoldouts()
                == evidence.configuredPositiveHoldouts()
            && evidence.executedNegativeHoldouts()
                == evidence.configuredNegativeHoldouts();
    }

    private static String holdoutSummary(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return "positive=" + evidence.executedPositiveHoldouts() + '/'
            + evidence.configuredPositiveHoldouts()
            + "; negative=" + evidence.executedNegativeHoldouts() + '/'
            + evidence.configuredNegativeHoldouts()
            + "; refuting=" + evidence.refutingHoldouts();
    }

    private static String cleanRunSummary(
        AutonomousCampaignReleaseEvidence evidence
    ) {
        return evidence.cleanRunCount() + " runs; identical="
            + evidence.cleanRunsIdentical();
    }

    private static RequirementCheck check(
        String code,
        boolean passed,
        Object actual,
        Object required
    ) {
        return new RequirementCheck(
            code,
            passed,
            String.valueOf(actual),
            String.valueOf(required));
    }

    private static String matrixHash(
        String evidenceHash,
        List<ProfileResult> profiles,
        ProfileStatus autonomyStatus
    ) {
        List<String> canonicalProfiles = profiles.stream()
            .sorted(Comparator.comparing(result -> result.profile().name()))
            .map(ProfileResult::canonicalMaterial)
            .toList();
        return de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nevidence=" + evidenceHash
                + "\nprofiles=" + canonicalProfiles
                + "\nautonomy=" + autonomyStatus.name());
    }

    public enum ProfileStatus {
        READY,
        BLOCKED
    }

    public record RequirementCheck(
        String code,
        boolean passed,
        String actual,
        String required
    ) {
        public RequirementCheck {
            requireText(code, "code");
            requireText(actual, "actual");
            requireText(required, "required");
        }

        String canonicalMaterial() {
            return code + "|passed=" + passed
                + "|actual=" + actual
                + "|required=" + required;
        }
    }

    public record ProfileResult(
        ReleaseEvidenceProfile profile,
        ProfileStatus status,
        boolean autonomyClaimAuthorized,
        List<RequirementCheck> checks,
        List<String> blockers
    ) {
        public ProfileResult {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(status, "status");
            checks = checks == null
                ? List.of()
                : checks.stream()
                    .sorted(Comparator.comparing(RequirementCheck::code))
                    .toList();
            blockers = blockers == null
                ? List.of()
                : blockers.stream().distinct().sorted().toList();
            if (checks.isEmpty()
                    || (status == ProfileStatus.READY) != blockers.isEmpty()
                    || autonomyClaimAuthorized
                        != (profile.authorizesAutonomyClaim()
                            && status == ProfileStatus.READY)) {
                throw new IllegalArgumentException(
                    "profile result status, blockers or claim authority is inconsistent");
            }
        }

        String canonicalMaterial() {
            return profile.name() + '|' + status.name() + '|'
                + autonomyClaimAuthorized + '|'
                + checks.stream().map(RequirementCheck::canonicalMaterial).toList()
                + '|' + blockers;
        }
    }

    public record MatrixReport(
        String schema,
        String evidenceHash,
        List<ProfileResult> profiles,
        ProfileStatus autonomousCampaignStatus,
        boolean autonomyClaimAuthorized,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public MatrixReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported release-readiness matrix schema");
            }
            requireSha256(evidenceHash, "evidenceHash");
            profiles = profiles == null
                ? List.of()
                : profiles.stream()
                    .sorted(Comparator.comparing(result -> result.profile().name()))
                    .toList();
            if (profiles.size() != ReleaseEvidenceProfile.values().length
                    || profiles.stream().map(ProfileResult::profile).distinct().count()
                        != profiles.size()) {
                throw new IllegalArgumentException(
                    "matrix must contain every release evidence profile exactly once");
            }
            ProfileResult autonomy = findResult(
                profiles, ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN);
            if (autonomousCampaignStatus != autonomy.status()
                    || autonomyClaimAuthorized
                        != autonomy.autonomyClaimAuthorized()) {
                throw new IllegalArgumentException(
                    "matrix autonomy summary is inconsistent");
            }
            if (!"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "release matrix cannot promote or publish evidence");
            }
            requireSha256(contentHash, "contentHash");
            String expectedHash = matrixHash(
                evidenceHash, profiles, autonomousCampaignStatus);
            if (!expectedHash.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "release matrix content hash does not match canonical semantics");
            }
        }

        public ProfileResult result(ReleaseEvidenceProfile profile) {
            return findResult(profiles, profile);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("evidenceHash", evidenceHash)
                .array("profiles", array -> profiles.forEach(result ->
                    array.objectValue(object -> object
                        .property("profile", result.profile().name())
                        .property("claim", result.profile().claim())
                        .property("status", result.status().name())
                        .property("authorizesAutonomyClaim",
                            result.autonomyClaimAuthorized())
                        .array("checks", checks -> result.checks().forEach(check ->
                            checks.objectValue(item -> item
                                .property("code", check.code())
                                .property("passed", check.passed())
                                .property("actual", check.actual())
                                .property("required", check.required()))))
                        .stringArray("blockers", result.blockers()))))
                .property("autonomousCampaignStatus",
                    autonomousCampaignStatus.name())
                .property("autonomyClaimAuthorized", autonomyClaimAuthorized)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static ProfileResult findResult(
        List<ProfileResult> profiles,
        ReleaseEvidenceProfile profile
    ) {
        return profiles.stream()
            .filter(result -> result.profile() == profile)
            .findFirst()
            .orElseThrow();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static void requireSha256(String value, String name) {
        if (!isSha256(value)) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
