package de.regelsuche.evolution;

import java.util.List;
import java.util.Objects;

/** Complete VALIDATION evidence for one genome/search configuration. */
public record EvolutionValidationCandidate(
    String genomeHash,
    String alphaStructuralHash,
    EvolutionValidationSearchConfiguration searchConfiguration,
    String configurationHash,
    List<EvolutionValidationCaseEvidence> cases,
    int reachedCases,
    int newlySolvedCases,
    int reachabilityRegressions,
    int correctnessFailures,
    int correctnessRegressions,
    long exploredStates,
    long candidateEvaluations,
    List<String> blockers
) {
    public EvolutionValidationCandidate {
        EvolutionGenome.requireSha256(genomeHash, "genomeHash");
        EvolutionGenome.requireSha256(
            alphaStructuralHash, "alphaStructuralHash");
        Objects.requireNonNull(searchConfiguration, "searchConfiguration");
        EvolutionGenome.requireSha256(configurationHash, "configurationHash");
        String expectedHash = EvolutionValidationArtifactSupport.configurationHash(
            genomeHash, alphaStructuralHash, searchConfiguration);
        if (!expectedHash.equals(configurationHash)) {
            throw new IllegalArgumentException(
                "candidate configurationHash mismatch");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException(
                "candidate validation requires case evidence");
        }
        cases = List.copyOf(cases);
        if (cases.stream().map(EvolutionValidationCaseEvidence::caseId)
                .distinct().count() != cases.size()) {
            throw new IllegalArgumentException(
                "candidate validation contains duplicate case ids");
        }
        int actualReached = Math.toIntExact(cases.stream()
            .filter(EvolutionValidationCaseEvidence::candidateReached).count());
        int actualNewlySolved = Math.toIntExact(cases.stream()
            .filter(EvolutionValidationCaseEvidence::newlySolved).count());
        int actualReachabilityRegressions = Math.toIntExact(cases.stream()
            .filter(EvolutionValidationCaseEvidence::reachabilityRegression)
            .count());
        int actualCorrectnessFailures = Math.toIntExact(cases.stream()
            .filter(EvolutionValidationCaseEvidence::correctnessFailure).count());
        int actualCorrectnessRegressions = Math.toIntExact(cases.stream()
            .filter(EvolutionValidationCaseEvidence::correctnessRegression)
            .count());
        long actualExplored = cases.stream().mapToLong(
            EvolutionValidationCaseEvidence::candidateExploredStates).sum();
        long actualEvaluations = cases.stream().mapToLong(
            EvolutionValidationCaseEvidence::candidateCandidateEvaluations)
            .sum();
        if (reachedCases != actualReached
                || newlySolvedCases != actualNewlySolved
                || reachabilityRegressions != actualReachabilityRegressions
                || correctnessFailures != actualCorrectnessFailures
                || correctnessRegressions != actualCorrectnessRegressions
                || exploredStates != actualExplored
                || candidateEvaluations != actualEvaluations) {
            throw new IllegalArgumentException(
                "candidate validation aggregates differ from case evidence");
        }
        blockers = EvolutionValidationArtifactSupport.canonicalStrings(
            blockers, "blocker");
    }

    public static EvolutionValidationCandidate create(
        String genomeHash,
        String alphaStructuralHash,
        EvolutionValidationSearchConfiguration searchConfiguration,
        List<EvolutionValidationCaseEvidence> cases,
        List<String> blockers
    ) {
        List<EvolutionValidationCaseEvidence> retained = List.copyOf(cases);
        return new EvolutionValidationCandidate(
            genomeHash,
            alphaStructuralHash,
            searchConfiguration,
            EvolutionValidationArtifactSupport.configurationHash(
                genomeHash, alphaStructuralHash, searchConfiguration),
            retained,
            Math.toIntExact(retained.stream()
                .filter(EvolutionValidationCaseEvidence::candidateReached)
                .count()),
            Math.toIntExact(retained.stream()
                .filter(EvolutionValidationCaseEvidence::newlySolved).count()),
            Math.toIntExact(retained.stream()
                .filter(EvolutionValidationCaseEvidence::reachabilityRegression)
                .count()),
            Math.toIntExact(retained.stream()
                .filter(EvolutionValidationCaseEvidence::correctnessFailure)
                .count()),
            Math.toIntExact(retained.stream()
                .filter(EvolutionValidationCaseEvidence::correctnessRegression)
                .count()),
            retained.stream().mapToLong(
                EvolutionValidationCaseEvidence::candidateExploredStates).sum(),
            retained.stream().mapToLong(
                EvolutionValidationCaseEvidence::candidateCandidateEvaluations)
                .sum(),
            blockers);
    }

    public boolean eligible() {
        return blockers.isEmpty()
            && reachabilityRegressions == 0
            && correctnessFailures == 0;
    }
}
