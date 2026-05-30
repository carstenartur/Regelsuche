package de.regelsuche.discovery;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    Set<DiscoveryEvidenceKind> evidence,
    CandidateProofStatus proofStatus,
    List<String> assumptions,
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
        evidence = evidence == null ? Set.of() : Set.copyOf(evidence);
        proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED : proofStatus;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        notes = notes == null ? "" : notes;
    }

    public DiscoveryTrace(
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
        this(inputExpression, finalExpression, resultKind, replayPath, rulePath, hypothesisCandidates,
            learnedMacroId, reusedMacroId, Set.of(), proofStatus, List.of(), notes);
    }

    public DiscoveryTrace(
        String inputExpression,
        String finalExpression,
        DiscoveryResultKind resultKind,
        List<String> replayPath,
        List<String> rulePath,
        List<String> hypothesisCandidates,
        Optional<String> learnedMacroId,
        Optional<String> reusedMacroId,
        Set<DiscoveryEvidenceKind> evidence,
        CandidateProofStatus proofStatus,
        String notes
    ) {
        this(inputExpression, finalExpression, resultKind, replayPath, rulePath, hypothesisCandidates,
            learnedMacroId, reusedMacroId, evidence, proofStatus, List.of(), notes);
    }

    public static DiscoveryTrace noCandidate(String inputExpression, String notes) {
        return new DiscoveryTrace(inputExpression, inputExpression, DiscoveryResultKind.NO_CANDIDATE,
            List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(), Set.of(), CandidateProofStatus.OBSERVED,
            List.of(), notes);
    }
}
