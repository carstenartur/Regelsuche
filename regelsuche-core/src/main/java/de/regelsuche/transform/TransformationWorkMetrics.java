package de.regelsuche.transform;

/**
 * Deterministic work ledger for producing one batch of transformations.
 *
 * <p>The fields are deliberately mechanical rather than wall-clock based. They
 * can be summed across engines and search states and are therefore suitable for
 * information-parity evaluation and reproducible budget enforcement.</p>
 */
public record TransformationWorkMetrics(
    long engineInvocations,
    long programNodeVisits,
    long sourceInvocations,
    long sourceCandidates,
    long composedCandidates,
    long requirementEvaluations,
    long requirementRejections,
    long priorityCandidatesOrdered,
    long prunedCandidates,
    long repeatIterations,
    long repeatEndpoints,
    long alternativeSelections,
    long alternativesSkipped,
    long duplicateCandidatesDropped
) {
    public static final TransformationWorkMetrics ZERO =
        new TransformationWorkMetrics(
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0);

    public TransformationWorkMetrics {
        requireNonNegative(engineInvocations, "engineInvocations");
        requireNonNegative(programNodeVisits, "programNodeVisits");
        requireNonNegative(sourceInvocations, "sourceInvocations");
        requireNonNegative(sourceCandidates, "sourceCandidates");
        requireNonNegative(composedCandidates, "composedCandidates");
        requireNonNegative(requirementEvaluations, "requirementEvaluations");
        requireNonNegative(requirementRejections, "requirementRejections");
        requireNonNegative(
            priorityCandidatesOrdered, "priorityCandidatesOrdered");
        requireNonNegative(prunedCandidates, "prunedCandidates");
        requireNonNegative(repeatIterations, "repeatIterations");
        requireNonNegative(repeatEndpoints, "repeatEndpoints");
        requireNonNegative(alternativeSelections, "alternativeSelections");
        requireNonNegative(alternativesSkipped, "alternativesSkipped");
        requireNonNegative(
            duplicateCandidatesDropped, "duplicateCandidatesDropped");
        if (requirementRejections > requirementEvaluations) {
            throw new IllegalArgumentException(
                "requirementRejections must not exceed requirementEvaluations");
        }
    }

    /** Work accounting for one ordinary, non-program engine invocation. */
    public static TransformationWorkMetrics flatEngine(int candidates) {
        if (candidates < 0) {
            throw new IllegalArgumentException("candidates must not be negative");
        }
        return new TransformationWorkMetrics(
            1,
            0,
            1,
            candidates,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0);
    }

    public TransformationWorkMetrics plus(TransformationWorkMetrics other) {
        if (other == null) {
            return this;
        }
        return new TransformationWorkMetrics(
            add(engineInvocations, other.engineInvocations),
            add(programNodeVisits, other.programNodeVisits),
            add(sourceInvocations, other.sourceInvocations),
            add(sourceCandidates, other.sourceCandidates),
            add(composedCandidates, other.composedCandidates),
            add(requirementEvaluations, other.requirementEvaluations),
            add(requirementRejections, other.requirementRejections),
            add(priorityCandidatesOrdered, other.priorityCandidatesOrdered),
            add(prunedCandidates, other.prunedCandidates),
            add(repeatIterations, other.repeatIterations),
            add(repeatEndpoints, other.repeatEndpoints),
            add(alternativeSelections, other.alternativeSelections),
            add(alternativesSkipped, other.alternativesSkipped),
            add(duplicateCandidatesDropped, other.duplicateCandidatesDropped));
    }

    public TransformationWorkMetrics withDuplicateCandidatesDropped(
        long additional
    ) {
        requireNonNegative(additional, "additional");
        return new TransformationWorkMetrics(
            engineInvocations,
            programNodeVisits,
            sourceInvocations,
            sourceCandidates,
            composedCandidates,
            requirementEvaluations,
            requirementRejections,
            priorityCandidatesOrdered,
            prunedCandidates,
            repeatIterations,
            repeatEndpoints,
            alternativeSelections,
            alternativesSkipped,
            add(duplicateCandidatesDropped, additional));
    }

    /**
     * Frozen v1 scalar work formula. Every retained mechanical event contributes
     * one unit. Individual fields remain authoritative for later analysis.
     */
    public long totalWorkUnits() {
        long total = 0;
        total = add(total, engineInvocations);
        total = add(total, programNodeVisits);
        total = add(total, sourceInvocations);
        total = add(total, sourceCandidates);
        total = add(total, composedCandidates);
        total = add(total, requirementEvaluations);
        total = add(total, requirementRejections);
        total = add(total, priorityCandidatesOrdered);
        total = add(total, prunedCandidates);
        total = add(total, repeatIterations);
        total = add(total, repeatEndpoints);
        total = add(total, alternativeSelections);
        total = add(total, alternativesSkipped);
        return add(total, duplicateCandidatesDropped);
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
