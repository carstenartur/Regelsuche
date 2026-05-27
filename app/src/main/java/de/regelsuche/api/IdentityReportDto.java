package de.regelsuche.api;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.mining.MacroRuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.util.List;

/**
 * UI- and export-oriented view of a {@link MacroRuleCandidate}: presents the
 * recurring macro-rule together with concrete witnesses and a "known" flag
 * derived from {@link de.regelsuche.mining.KnownRuleRepository}.
 *
 * <p>Returned by {@code GET /api/identities}. {@code POST /api/identities/{id}/promote}
 * persists the macro into the {@link de.regelsuche.inventory.RuleInventoryRepository}.</p>
 */
public record IdentityReportDto(
    String id,
    String leftPattern,
    String rightPattern,
    List<String> ruleIdSequence,
    int occurrences,
    double compressionRatio,
    CandidateProofStatus proofStatus,
    RuleStatus knownRuleStatus,
    List<String> supportingTransformationIds,
    CounterexampleSearchService.Status counterexampleStatus,
    List<String> counterexampleAttemptedSources,
    List<String> inferredAssumptions,
    String counterexampleExplanation
) {
    public IdentityReportDto(
        String id,
        String leftPattern,
        String rightPattern,
        List<String> ruleIdSequence,
        int occurrences,
        double compressionRatio,
        CandidateProofStatus proofStatus,
        RuleStatus knownRuleStatus,
        List<String> supportingTransformationIds
    ) {
        this(id, leftPattern, rightPattern, ruleIdSequence, occurrences, compressionRatio,
            proofStatus, knownRuleStatus, supportingTransformationIds,
            proofStatus == CandidateProofStatus.REJECTED
                ? CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND
                : null,
            List.of(), List.of(), "");
    }

    public IdentityReportDto {
        ruleIdSequence = List.copyOf(ruleIdSequence);
        supportingTransformationIds = List.copyOf(supportingTransformationIds);
        counterexampleAttemptedSources = counterexampleAttemptedSources == null
            ? List.of()
            : List.copyOf(counterexampleAttemptedSources);
        inferredAssumptions = inferredAssumptions == null ? List.of() : List.copyOf(inferredAssumptions);
        counterexampleExplanation = counterexampleExplanation == null ? "" : counterexampleExplanation;
    }

    public static IdentityReportDto from(MacroRuleCandidate candidate, RuleStatus knownRuleStatus) {
        return new IdentityReportDto(
            candidate.id(),
            candidate.leftPattern(),
            candidate.rightPattern(),
            candidate.ruleIdSequence(),
            candidate.occurrences(),
            candidate.compressionRatio(),
            candidate.proofStatus(),
            knownRuleStatus,
            candidate.supportingTransformationIds()
        );
    }
}
