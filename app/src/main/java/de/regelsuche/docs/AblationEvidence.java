package de.regelsuche.docs;

import java.util.Locale;

/** Structured ablation signal used for promotion and candidate reports. */
record AblationEvidence(
    RunEvidence withCandidate,
    RunEvidence withoutCandidate,
    double improvementRatio,
    String ablationStatus,
    String explanation
) {
    AblationEvidence {
        withCandidate = withCandidate == null ? RunEvidence.unknown() : withCandidate;
        withoutCandidate = withoutCandidate == null ? RunEvidence.unknown() : withoutCandidate;
        ablationStatus = normalizeStatus(ablationStatus);
        explanation = explanation == null ? "" : explanation;
    }

    static AblationEvidence statusOnly(String ablationStatus) {
        return statusOnly(ablationStatus, "structured ablation metrics unavailable");
    }

    static AblationEvidence statusOnly(String ablationStatus, String explanation) {
        return new AblationEvidence(
            RunEvidence.unknown(),
            RunEvidence.unknown(),
            0.0d,
            ablationStatus,
            explanation == null || explanation.isBlank()
                ? "structured ablation metrics unavailable"
                : explanation
        );
    }

    static AblationEvidence compare(
        boolean withSuccess,
        int withPathLength,
        long withStatesExplored,
        boolean withoutSuccess,
        int withoutPathLength,
        long withoutStatesExplored,
        String explanation
    ) {
        RunEvidence with = new RunEvidence(withSuccess, withPathLength, withStatesExplored);
        RunEvidence without = new RunEvidence(withoutSuccess, withoutPathLength, withoutStatesExplored);
        String status = statusFor(with, without);
        return new AblationEvidence(
            with,
            without,
            improvementRatio(with, without),
            status,
            explanation == null || explanation.isBlank() ? defaultExplanation(with, without, status) : explanation
        );
    }

    boolean promotionReady() {
        return "DEGRADED".equals(ablationStatus);
    }

    boolean hasStructuredMetrics() {
        return withCandidate.known() || withoutCandidate.known();
    }

    String compactSummary() {
        return "with=" + withCandidate.compact()
            + "; without=" + withoutCandidate.compact()
            + "; ratio=" + String.format(Locale.ROOT, "%.3f", improvementRatio)
            + "; status=" + ablationStatus;
    }

    private static String statusFor(RunEvidence with, RunEvidence without) {
        if (with.failed()) {
            return "BLOCKED";
        }
        if (without.failed()) {
            return "DEGRADED";
        }
        if (isKnownNonNegative(with.pathLength()) && isKnownNonNegative(without.pathLength())
            && with.pathLength() < without.pathLength()) {
            return "DEGRADED";
        }
        if (isKnownNonNegative(with.statesExplored()) && isKnownNonNegative(without.statesExplored())
            && with.statesExplored() < without.statesExplored()) {
            return "DEGRADED";
        }
        return "UNCHANGED";
    }

    private static double improvementRatio(RunEvidence with, RunEvidence without) {
        if (!with.successful() || !without.successful()) {
            return with.successful() && without.failed() ? 1.0d : 0.0d;
        }
        if (isKnownPositive(without.statesExplored()) && isKnownNonNegative(with.statesExplored())) {
            return Math.max(0.0d, (without.statesExplored() - with.statesExplored()) / (double) without.statesExplored());
        }
        if (isKnownPositive(without.pathLength()) && isKnownNonNegative(with.pathLength())) {
            return Math.max(0.0d, (without.pathLength() - with.pathLength()) / (double) without.pathLength());
        }
        return 0.0d;
    }

    private static String defaultExplanation(RunEvidence with, RunEvidence without, String status) {
        if ("BLOCKED".equals(status)) {
            return "candidate-enabled run did not succeed";
        }
        if ("DEGRADED".equals(status) && without.failed()) {
            return "candidate-enabled run succeeds while disabled run does not";
        }
        if ("DEGRADED".equals(status)) {
            return "candidate-enabled run improves path length or explored states";
        }
        return "candidate-enabled run did not improve path length or explored states";
    }

    private static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "N/A" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isKnownPositive(long value) {
        return value > 0L;
    }

    private static boolean isKnownPositive(int value) {
        return value > 0;
    }

    private static boolean isKnownNonNegative(long value) {
        return value >= 0L;
    }

    private static boolean isKnownNonNegative(int value) {
        return value >= 0;
    }

    record RunEvidence(Boolean success, int pathLength, long statesExplored) {
        static RunEvidence unknown() {
            return new RunEvidence(null, -1, -1L);
        }

        boolean known() {
            return success != null || pathLength >= 0 || statesExplored >= 0;
        }

        boolean successful() {
            return Boolean.TRUE.equals(success);
        }

        boolean failed() {
            return Boolean.FALSE.equals(success);
        }

        String compact() {
            return "success=" + (success == null ? "unknown" : success)
                + ", pathLength=" + render(pathLength)
                + ", statesExplored=" + render(statesExplored);
        }

        private String render(long value) {
            return value < 0 ? "unknown" : Long.toString(value);
        }
    }
}
