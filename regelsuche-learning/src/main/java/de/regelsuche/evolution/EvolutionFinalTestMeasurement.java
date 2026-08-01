package de.regelsuche.evolution;

import java.util.Objects;

/** One bounded baseline or selected-configuration FINAL TEST measurement. */
public record EvolutionFinalTestMeasurement(
    Status status,
    boolean reached,
    String terminalReason,
    int depth,
    long exploredStates,
    long candidateEvaluations,
    EvolutionCorrectnessStatus correctnessStatus,
    String resultArtifactHash
) {
    public EvolutionFinalTestMeasurement {
        Objects.requireNonNull(status, "status");
        EvolutionValidationArtifactSupport.requireText(
            terminalReason, "terminalReason");
        Objects.requireNonNull(correctnessStatus, "correctnessStatus");
        resultArtifactHash = resultArtifactHash == null
            ? "" : resultArtifactHash;
        if (!resultArtifactHash.isEmpty()) {
            EvolutionGenome.requireSha256(
                resultArtifactHash, "resultArtifactHash");
        }
        if (depth < -1 || exploredStates < 0 || candidateEvaluations < 0) {
            throw new IllegalArgumentException(
                "FINAL TEST measurement is outside bounded ranges");
        }
        if (status == Status.FAILED) {
            requireFailedMeasurement(
                reached, depth, correctnessStatus, resultArtifactHash);
        } else {
            requireCompletedMeasurement(
                reached, depth, correctnessStatus, resultArtifactHash);
        }
    }

    public static EvolutionFinalTestMeasurement failed(String reason) {
        return new EvolutionFinalTestMeasurement(
            Status.FAILED, false, reason, -1, 0, 0,
            EvolutionCorrectnessStatus.NOT_EVALUATED, "");
    }

    private static void requireFailedMeasurement(
        boolean reached,
        int depth,
        EvolutionCorrectnessStatus correctnessStatus,
        String resultArtifactHash
    ) {
        if (reached || depth != -1
                || correctnessStatus
                    != EvolutionCorrectnessStatus.NOT_EVALUATED
                || !resultArtifactHash.isEmpty()) {
            throw new IllegalArgumentException(
                "failed measurement cannot claim a result");
        }
    }

    private static void requireCompletedMeasurement(
        boolean reached,
        int depth,
        EvolutionCorrectnessStatus correctnessStatus,
        String resultArtifactHash
    ) {
        if (reached && depth < 0) {
            throw new IllegalArgumentException(
                "reached measurement requires a path depth");
        }
        if (!reached && depth != -1) {
            throw new IllegalArgumentException(
                "unreached measurement cannot retain a path depth");
        }
        if (reached
                && correctnessStatus
                    == EvolutionCorrectnessStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                "reached measurement requires correctness evidence");
        }
        if (!reached
                && correctnessStatus
                    != EvolutionCorrectnessStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                "unreached measurement cannot claim correctness evidence");
        }
        if (reached && resultArtifactHash.isEmpty()) {
            throw new IllegalArgumentException(
                "reached measurement requires a result artifact hash");
        }
    }

    public enum Status {
        COMPLETED,
        FAILED
    }
}
