package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;

import java.util.List;

/**
 * A macro-rule candidate – a recurring contiguous sequence of atomic rule ids
 * observed across multiple successful transformations.
 *
 * <p>The {@link MacroRuleMiner} discovers these by sliding-window frequency
 * analysis over the rule-id sequences of {@link
 * de.regelsuche.discovery.DiscoveredTransformation}s. The patterns themselves
 * are concrete first witnesses; future PRs may add anti-unification to
 * abstract them.</p>
 *
 * <p>{@code compressionRatio} is defined as {@code sequenceLength / 1.0} as
 * specified in the plan – higher means the macro replaces more atomic steps.
 * </p>
 */
public record MacroRuleCandidate(
    String id,
    List<String> ruleIdSequence,
    int occurrences,
    String leftPattern,
    String rightPattern,
    double compressionRatio,
    CandidateProofStatus proofStatus,
    List<String> supportingTransformationIds
) {
    public MacroRuleCandidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        ruleIdSequence = List.copyOf(ruleIdSequence);
        leftPattern = leftPattern == null ? "" : leftPattern;
        rightPattern = rightPattern == null ? "" : rightPattern;
        proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED : proofStatus;
        supportingTransformationIds = supportingTransformationIds == null
            ? List.of()
            : List.copyOf(supportingTransformationIds);
    }
}
