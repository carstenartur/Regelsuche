package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;
import java.util.Objects;

/**
 * Independent post-formation evidence consumed by interestingness assessment.
 *
 * <p>The contract deliberately contains no relevance label and no hidden expected
 * answer. Truth, novelty and evidence completeness remain inspectable inputs rather
 * than being inferred from the final rank.</p>
 */
public record InterestingnessEvidence(
    String evidenceId,
    int configuredPositiveChecks,
    int executedPositiveChecks,
    int skippedPositiveChecks,
    int failedPositiveChecks,
    int configuredNegativeChecks,
    int executedNegativeChecks,
    int skippedNegativeChecks,
    int failedNegativeChecks,
    CounterexampleSearchService.Status counterexampleStatus,
    int counterexampleSourcesAttempted,
    boolean oracleDisagreed,
    ProjectNoveltyStatus projectNoveltyStatus,
    int contributingFamilies,
    boolean heldOutTransferRequired,
    int heldOutFamiliesConfigured,
    int heldOutFamiliesPassed,
    boolean pairedUtilityEvaluated,
    int pairedUtilityPermille,
    ControlClassification controlClassification
) {
    public InterestingnessEvidence {
        if (evidenceId == null || evidenceId.isBlank()) {
            throw new IllegalArgumentException("evidenceId must not be blank");
        }
        requireNonNegative(configuredPositiveChecks, "configuredPositiveChecks");
        requireNonNegative(executedPositiveChecks, "executedPositiveChecks");
        requireNonNegative(skippedPositiveChecks, "skippedPositiveChecks");
        requireNonNegative(failedPositiveChecks, "failedPositiveChecks");
        requireNonNegative(configuredNegativeChecks, "configuredNegativeChecks");
        requireNonNegative(executedNegativeChecks, "executedNegativeChecks");
        requireNonNegative(skippedNegativeChecks, "skippedNegativeChecks");
        requireNonNegative(failedNegativeChecks, "failedNegativeChecks");
        requireNonNegative(counterexampleSourcesAttempted, "counterexampleSourcesAttempted");
        requireNonNegative(contributingFamilies, "contributingFamilies");
        requireNonNegative(heldOutFamiliesConfigured, "heldOutFamiliesConfigured");
        requireNonNegative(heldOutFamiliesPassed, "heldOutFamiliesPassed");
        if (pairedUtilityPermille < 0 || pairedUtilityPermille > 1000) {
            throw new IllegalArgumentException("pairedUtilityPermille must be in [0,1000]");
        }
        counterexampleStatus = Objects.requireNonNull(
            counterexampleStatus, "counterexampleStatus");
        projectNoveltyStatus = Objects.requireNonNull(
            projectNoveltyStatus, "projectNoveltyStatus");
        controlClassification = Objects.requireNonNull(
            controlClassification, "controlClassification");
    }

    public boolean positiveAccountingComplete() {
        return configuredPositiveChecks == executedPositiveChecks + skippedPositiveChecks
            && failedPositiveChecks <= executedPositiveChecks;
    }

    public boolean negativeAccountingComplete() {
        return configuredNegativeChecks == executedNegativeChecks + skippedNegativeChecks
            && failedNegativeChecks <= executedNegativeChecks;
    }

    public boolean heldOutAccountingComplete() {
        return heldOutFamiliesPassed <= heldOutFamiliesConfigured;
    }

    public enum ProjectNoveltyStatus {
        NOVEL_WITHIN_PROJECT,
        DUPLICATE,
        ALPHA_EQUIVALENT,
        UNKNOWN
    }

    public enum ControlClassification {
        NONE,
        ALPHA_RENAMING_ONLY,
        FORMAT_ONLY,
        GENERIC_NORMALIZATION
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
