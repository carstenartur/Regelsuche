package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Versioned interestingness decision with independent eligibility and evidence axes.
 */
public record InterestingnessAssessment(
    String schema,
    String candidateId,
    InterestingnessProfile profile,
    Eligibility eligibility,
    InterestingnessEvidence evidence,
    CandidateProofStatus proofStatus,
    CounterexampleSearchService.Status counterexampleStatus,
    String externalNoveltyStatus,
    String publicEvidenceStatus,
    int knownRuleSimilarityPermille,
    int evidenceCompletenessPermille,
    List<ComponentContribution> contributions,
    int unresolvedRiskPenaltyPermille,
    int controlPenaltyPermille,
    int totalPermille,
    List<String> hardBlockers,
    List<String> warnings,
    String contentHash
) implements Comparable<InterestingnessAssessment> {
    public static final String SCHEMA = "regelsuche.interestingness-assessment/v1";

    public InterestingnessAssessment {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported interestingness schema");
        }
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        profile = Objects.requireNonNull(profile, "profile");
        eligibility = Objects.requireNonNull(eligibility, "eligibility");
        evidence = Objects.requireNonNull(evidence, "evidence");
        proofStatus = Objects.requireNonNull(proofStatus, "proofStatus");
        counterexampleStatus = Objects.requireNonNull(
            counterexampleStatus, "counterexampleStatus");
        externalNoveltyStatus = normalizeStatus(externalNoveltyStatus);
        publicEvidenceStatus = normalizeStatus(publicEvidenceStatus);
        requirePermille(knownRuleSimilarityPermille, "knownRuleSimilarityPermille");
        requirePermille(evidenceCompletenessPermille, "evidenceCompletenessPermille");
        requirePermille(unresolvedRiskPenaltyPermille, "unresolvedRiskPenaltyPermille");
        requirePermille(controlPenaltyPermille, "controlPenaltyPermille");
        if (totalPermille < -1000 || totalPermille > 1000) {
            throw new IllegalArgumentException("totalPermille must be in [-1000,1000]");
        }
        contributions = contributions == null
            ? List.of()
            : contributions.stream()
                .sorted(Comparator.comparing(ComponentContribution::name))
                .toList();
        hardBlockers = ordered(hardBlockers);
        warnings = ordered(warnings);
        if (contentHash == null || !contentHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be SHA-256");
        }
    }

    @Override
    public int compareTo(InterestingnessAssessment other) {
        int eligibilityOrder = Integer.compare(
            eligibility.rankOrder(), other.eligibility.rankOrder());
        if (eligibilityOrder != 0) {
            return eligibilityOrder;
        }
        int scoreOrder = Integer.compare(other.totalPermille, totalPermille);
        return scoreOrder != 0 ? scoreOrder : candidateId.compareTo(other.candidateId);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("candidateId", candidateId)
            .property("profile", profile.name())
            .property("profileSchema", InterestingnessProfile.WEIGHT_SCHEMA)
            .property("eligibility", eligibility.name())
            .property("proofStatus", proofStatus.name())
            .property("counterexampleStatus", counterexampleStatus.name())
            .property("projectNoveltyStatus", evidence.projectNoveltyStatus().name())
            .property("externalNoveltyStatus", externalNoveltyStatus)
            .property("publicEvidenceStatus", publicEvidenceStatus)
            .property("knownRuleSimilarityPermille", knownRuleSimilarityPermille)
            .property("evidenceCompletenessPermille", evidenceCompletenessPermille)
            .object("evidence", this::writeEvidence)
            .array("contributions", array -> contributions.forEach(contribution ->
                array.objectValue(object -> object
                    .property("name", contribution.name())
                    .property("rawPermille", contribution.rawPermille())
                    .property("weightPermille", contribution.weightPermille())
                    .property("weightedPermille", contribution.weightedPermille()))))
            .object("penalties", object -> object
                .property("unresolvedRiskPermille", unresolvedRiskPenaltyPermille)
                .property("controlPermille", controlPenaltyPermille))
            .property("totalPermille", totalPermille)
            .stringArray("hardBlockers", hardBlockers)
            .stringArray("warnings", warnings)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private void writeEvidence(JsonWriter json) {
        json.property("evidenceId", evidence.evidenceId())
            .property("configuredPositiveChecks", evidence.configuredPositiveChecks())
            .property("executedPositiveChecks", evidence.executedPositiveChecks())
            .property("skippedPositiveChecks", evidence.skippedPositiveChecks())
            .property("failedPositiveChecks", evidence.failedPositiveChecks())
            .property("configuredNegativeChecks", evidence.configuredNegativeChecks())
            .property("executedNegativeChecks", evidence.executedNegativeChecks())
            .property("skippedNegativeChecks", evidence.skippedNegativeChecks())
            .property("failedNegativeChecks", evidence.failedNegativeChecks())
            .property("counterexampleSourcesAttempted", evidence.counterexampleSourcesAttempted())
            .property("oracleDisagreed", evidence.oracleDisagreed())
            .property("contributingFamilies", evidence.contributingFamilies())
            .property("heldOutTransferRequired", evidence.heldOutTransferRequired())
            .property("heldOutFamiliesConfigured", evidence.heldOutFamiliesConfigured())
            .property("heldOutFamiliesPassed", evidence.heldOutFamiliesPassed())
            .property("pairedUtilityEvaluated", evidence.pairedUtilityEvaluated())
            .property("pairedUtilityPermille", evidence.pairedUtilityPermille())
            .property("controlClassification", evidence.controlClassification().name());
    }

    public enum Eligibility {
        RANKABLE_COMPLETE(0),
        RANKABLE_INCOMPLETE(1),
        BLOCKED(2);

        private final int rankOrder;

        Eligibility(int rankOrder) {
            this.rankOrder = rankOrder;
        }

        int rankOrder() {
            return rankOrder;
        }
    }

    public record ComponentContribution(
        String name,
        int rawPermille,
        int weightPermille,
        int weightedPermille
    ) {
        public ComponentContribution {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("contribution name must not be blank");
            }
            requirePermille(rawPermille, "rawPermille");
            requirePermille(weightPermille, "weightPermille");
            requirePermille(weightedPermille, "weightedPermille");
        }
    }

    private static List<String> ordered(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String normalizeStatus(String value) {
        return value == null || value.isBlank() ? "NOT_EVALUATED" : value;
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }
}
