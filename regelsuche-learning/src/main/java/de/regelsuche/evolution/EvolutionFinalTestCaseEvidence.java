package de.regelsuche.evolution;

import java.util.Objects;

/** Complete paired evidence for one ordered FINAL TEST case. */
public record EvolutionFinalTestCaseEvidence(
    String caseId,
    String family,
    EvolutionFinalTestMeasurement baseline,
    EvolutionFinalTestMeasurement selected,
    boolean newlySolved,
    boolean reachabilityRegression,
    boolean correctnessFailure,
    boolean correctnessRegression,
    boolean evaluationFailed
) {
    public EvolutionFinalTestCaseEvidence {
        EvolutionValidationArtifactSupport.requireText(caseId, "caseId");
        EvolutionValidationArtifactSupport.requireText(family, "family");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(selected, "selected");
        boolean bothCompleted = completed(baseline) && completed(selected);
        boolean expectedNewlySolved = bothCompleted
            && !baseline.reached()
            && selected.reached()
            && selected.correctnessStatus()
                == EvolutionCorrectnessStatus.CONFIRMED;
        if (newlySolved != expectedNewlySolved) {
            throw new IllegalArgumentException(
                "newlySolved differs from FINAL TEST evidence");
        }
        boolean expectedReachabilityRegression = bothCompleted
            && baseline.reached()
            && !selected.reached();
        if (reachabilityRegression != expectedReachabilityRegression) {
            throw new IllegalArgumentException(
                "reachabilityRegression differs from FINAL TEST evidence");
        }
        boolean expectedCorrectnessFailure = completed(selected)
            && selected.reached()
            && selected.correctnessStatus()
                == EvolutionCorrectnessStatus.REFUTED;
        if (correctnessFailure != expectedCorrectnessFailure) {
            throw new IllegalArgumentException(
                "correctnessFailure differs from FINAL TEST evidence");
        }
        boolean expectedCorrectnessRegression = bothCompleted
            && baseline.reached()
            && baseline.correctnessStatus()
                == EvolutionCorrectnessStatus.CONFIRMED
            && selected.reached()
            && selected.correctnessStatus()
                == EvolutionCorrectnessStatus.REFUTED;
        if (correctnessRegression != expectedCorrectnessRegression) {
            throw new IllegalArgumentException(
                "correctnessRegression differs from FINAL TEST evidence");
        }
        boolean expectedFailure = !bothCompleted;
        if (evaluationFailed != expectedFailure) {
            throw new IllegalArgumentException(
                "evaluationFailed differs from FINAL TEST evidence");
        }
    }

    public static EvolutionFinalTestCaseEvidence create(
        EvolutionFinalTestSuite.CaseDefinition definition,
        EvolutionFinalTestMeasurement baseline,
        EvolutionFinalTestMeasurement selected
    ) {
        boolean bothCompleted = completed(baseline) && completed(selected);
        return new EvolutionFinalTestCaseEvidence(
            definition.caseId(), definition.family(), baseline, selected,
            bothCompleted && !baseline.reached() && selected.reached()
                && selected.correctnessStatus()
                    == EvolutionCorrectnessStatus.CONFIRMED,
            bothCompleted && baseline.reached() && !selected.reached(),
            completed(selected) && selected.reached()
                && selected.correctnessStatus()
                    == EvolutionCorrectnessStatus.REFUTED,
            bothCompleted && baseline.reached()
                && baseline.correctnessStatus()
                    == EvolutionCorrectnessStatus.CONFIRMED
                && selected.reached()
                && selected.correctnessStatus()
                    == EvolutionCorrectnessStatus.REFUTED,
            !bothCompleted);
    }

    private static boolean completed(EvolutionFinalTestMeasurement value) {
        return value.status() == EvolutionFinalTestMeasurement.Status.COMPLETED;
    }
}
