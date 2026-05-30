package de.regelsuche.discovery;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Optional;

/** Immutable replay/search summary shared by corpus tests, reports and gallery exports. */
public record DiscoveryTrace(
    String inputExpression,
    String finalExpression,
    DiscoveryResultKind resultKind,
    List<String> replayPath,
    List<String> rulePath,
    List<String> hypothesisCandidates,
    Optional<String> learnedMacroId,
    Optional<String> reusedMacroId,
    CandidateProofStatus proofStatus,
    String notes
) {
    public DiscoveryTrace {
        if (inputExpression == null || inputExpression.isBlank()) {
            throw new IllegalArgumentException("inputExpression is required");
        }
        finalExpression = finalExpression == null ? "" : finalExpression;
        resultKind = resultKind == null ? DiscoveryResultKind.NO_CANDIDATE : resultKind;
        replayPath = replayPath == null ? List.of() : List.copyOf(replayPath);
        rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        hypothesisCandidates = hypothesisCandidates == null ? List.of() : List.copyOf(hypothesisCandidates);
        learnedMacroId = learnedMacroId == null ? Optional.empty() : learnedMacroId;
        reusedMacroId = reusedMacroId == null ? Optional.empty() : reusedMacroId;
        proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED : proofStatus;
        notes = notes == null ? "" : notes;
    }

    public static DiscoveryTrace noCandidate(String inputExpression, String notes) {
        return new DiscoveryTrace(inputExpression, inputExpression, DiscoveryResultKind.NO_CANDIDATE,
            List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(), CandidateProofStatus.OBSERVED, notes);
    }
}
