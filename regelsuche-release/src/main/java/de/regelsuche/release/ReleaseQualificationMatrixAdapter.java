package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.release.ReleaseReadinessMatrix.MatrixReport;
import de.regelsuche.release.ReleaseReadinessMatrix.ProfileResult;
import de.regelsuche.release.ReleaseReadinessMatrix.ProfileStatus;
import de.regelsuche.release.ReleaseReadinessMatrix.RequirementCheck;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Re-evaluates only the three autonomy checks owned by issue #359. */
public final class ReleaseQualificationMatrixAdapter {
    public MatrixReport apply(
        MatrixReport base,
        AutonomousCampaignReleaseEvidence campaignEvidence,
        AutonomousCandidateQualificationEvidence qualification
    ) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(campaignEvidence, "campaignEvidence");
        if (qualification == null) {
            return base;
        }
        if (!qualification.campaignManifestHash()
                .equals(campaignEvidence.campaignManifestHash())) {
            throw new IllegalArgumentException(
                "qualification belongs to another production campaign");
        }
        List<ProfileResult> profiles = base.profiles().stream()
            .map(result -> result.profile()
                == ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN
                    ? qualifiedAutonomy(result, qualification)
                    : result)
            .sorted(Comparator.comparing(result -> result.profile().name()))
            .toList();
        ProfileResult autonomy = profiles.stream()
            .filter(result -> result.profile()
                == ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN)
            .findFirst().orElseThrow();
        String hash = matrixHash(
            base.evidenceHash(),
            base.hiddenRuleEvidenceHash(),
            profiles,
            autonomy.status());
        return new MatrixReport(
            base.schema(),
            base.evidenceHash(),
            base.hiddenRuleEvidenceHash(),
            profiles,
            autonomy.status(),
            autonomy.autonomyClaimAuthorized(),
            base.promotionStatus(),
            base.publicEvidenceStatus(),
            hash);
    }

    private static ProfileResult qualifiedAutonomy(
        ProfileResult existing,
        AutonomousCandidateQualificationEvidence evidence
    ) {
        List<RequirementCheck> checks = existing.checks().stream()
            .map(check -> switch (check.code()) {
                case "HELD_OUT_FAMILY_OR_CLUSTER" -> new RequirementCheck(
                    check.code(),
                    evidence.heldOutFamilyOrClusterCount() >= 1,
                    "count=" + evidence.heldOutFamilyOrClusterCount()
                        + "; evidence=" + evidence.contentHash(),
                    ">=1 hash-bound held-out family or structural cluster");
                case "BALANCED_RELEASE_HOLDOUT_SUITE" -> new RequirementCheck(
                    check.code(),
                    evidence.configuredPositiveHoldouts() >= 12
                        && evidence.executedPositiveHoldouts()
                            == evidence.configuredPositiveHoldouts()
                        && evidence.configuredNegativeHoldouts() >= 12
                        && evidence.executedNegativeHoldouts()
                            == evidence.configuredNegativeHoldouts()
                        && evidence.mandatorySkippedWorkCount() == 0
                        && evidence.refutingHoldouts() == 0
                        && evidence.counterexamplesFound() == 0,
                    "positive=" + evidence.executedPositiveHoldouts() + '/'
                        + evidence.configuredPositiveHoldouts()
                        + "; negative=" + evidence.executedNegativeHoldouts() + '/'
                        + evidence.configuredNegativeHoldouts()
                        + "; skipped=" + evidence.mandatorySkippedWorkCount()
                        + "; refuting=" + evidence.refutingHoldouts()
                        + "; counterexamples=" + evidence.counterexamplesFound()
                        + "; evidence=" + evidence.contentHash(),
                    ">=12 positive and >=12 negative; fully executed; zero refuting");
                case "PAIRED_HELD_OUT_UTILITY" -> new RequirementCheck(
                    check.code(),
                    evidence.pairedHeldOutUtilityEvaluated()
                        && evidence.pairedUtilityPermille() > 0
                        && evidence.correctnessRegressionCount() == 0,
                    "evaluated=" + evidence.pairedHeldOutUtilityEvaluated()
                        + "; gainPermille=" + evidence.pairedUtilityPermille()
                        + "; regressions="
                            + evidence.correctnessRegressionCount()
                        + "; evidence=" + evidence.contentHash(),
                    "positive held-out gain with zero correctness regressions");
                default -> check;
            })
            .sorted(Comparator.comparing(RequirementCheck::code))
            .toList();
        ProfileStatus status = checks.stream().allMatch(RequirementCheck::passed)
            ? ProfileStatus.READY : ProfileStatus.BLOCKED;
        List<String> blockers = checks.stream()
            .filter(check -> !check.passed())
            .map(RequirementCheck::code)
            .sorted().toList();
        return new ProfileResult(
            existing.profile(),
            status,
            existing.profile().authorizesAutonomyClaim()
                && status == ProfileStatus.READY,
            checks,
            blockers);
    }

    private static String matrixHash(
        String evidenceHash,
        String hiddenRuleEvidenceHash,
        List<ProfileResult> profiles,
        ProfileStatus autonomyStatus
    ) {
        List<String> canonicalProfiles = profiles.stream()
            .sorted(Comparator.comparing(result -> result.profile().name()))
            .map(ProfileResult::canonicalMaterial)
            .toList();
        return AutonomousResearchBriefV2.hash(
            ReleaseReadinessMatrix.SCHEMA
                + "\nevidence=" + evidenceHash
                + "\nhiddenRuleEvidence=" + hiddenRuleEvidenceHash
                + "\nprofiles=" + canonicalProfiles
                + "\nautonomy=" + autonomyStatus.name());
    }
}
