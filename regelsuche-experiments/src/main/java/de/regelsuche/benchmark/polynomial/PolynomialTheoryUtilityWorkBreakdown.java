package de.regelsuche.benchmark.polynomial;

import java.util.Objects;

/** Typed canonical-work accounting for one utility-study result or transition. */
public record PolynomialTheoryUtilityWorkBreakdown(
    long primitiveWork,
    long matchingWork,
    long sourceValidationWork,
    long factorizationWork,
    long verificationWork,
    long renderingWork,
    long reparseWork,
    long reconstructionWork,
    long occurrenceReplacementWork,
    long cacheLookupWork,
    long cacheInsertionWork,
    long cacheEvictionWork,
    long cacheReplayWork,
    long evidenceConstructionWork
) {
    public PolynomialTheoryUtilityWorkBreakdown {
        requireNonNegative(
            primitiveWork,
            matchingWork,
            sourceValidationWork,
            factorizationWork,
            verificationWork,
            renderingWork,
            reparseWork,
            reconstructionWork,
            occurrenceReplacementWork,
            cacheLookupWork,
            cacheInsertionWork,
            cacheEvictionWork,
            cacheReplayWork,
            evidenceConstructionWork
        );
        long mechanical = mechanicalWork(
            matchingWork,
            sourceValidationWork,
            factorizationWork,
            verificationWork,
            renderingWork,
            reparseWork,
            reconstructionWork,
            occurrenceReplacementWork,
            cacheLookupWork,
            cacheInsertionWork,
            cacheEvictionWork,
            cacheReplayWork,
            evidenceConstructionWork
        );
        Math.addExact(primitiveWork, mechanical);
    }

    public static PolynomialTheoryUtilityWorkBreakdown zero() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L
        );
    }

    public long mechanicalWork() {
        return mechanicalWork(
            matchingWork,
            sourceValidationWork,
            factorizationWork,
            verificationWork,
            renderingWork,
            reparseWork,
            reconstructionWork,
            occurrenceReplacementWork,
            cacheLookupWork,
            cacheInsertionWork,
            cacheEvictionWork,
            cacheReplayWork,
            evidenceConstructionWork
        );
    }

    public long totalWork() {
        return Math.addExact(primitiveWork, mechanicalWork());
    }

    public PolynomialTheoryUtilityWorkBreakdown plus(
        PolynomialTheoryUtilityWorkBreakdown other
    ) {
        var value = Objects.requireNonNull(other, "other");
        return new PolynomialTheoryUtilityWorkBreakdown(
            Math.addExact(primitiveWork, value.primitiveWork),
            Math.addExact(matchingWork, value.matchingWork),
            Math.addExact(
                sourceValidationWork,
                value.sourceValidationWork
            ),
            Math.addExact(factorizationWork, value.factorizationWork),
            Math.addExact(verificationWork, value.verificationWork),
            Math.addExact(renderingWork, value.renderingWork),
            Math.addExact(reparseWork, value.reparseWork),
            Math.addExact(reconstructionWork, value.reconstructionWork),
            Math.addExact(
                occurrenceReplacementWork,
                value.occurrenceReplacementWork
            ),
            Math.addExact(cacheLookupWork, value.cacheLookupWork),
            Math.addExact(
                cacheInsertionWork,
                value.cacheInsertionWork
            ),
            Math.addExact(
                cacheEvictionWork,
                value.cacheEvictionWork
            ),
            Math.addExact(cacheReplayWork, value.cacheReplayWork),
            Math.addExact(
                evidenceConstructionWork,
                value.evidenceConstructionWork
            )
        );
    }

    public boolean covers(PolynomialTheoryUtilityWorkBreakdown other) {
        var value = Objects.requireNonNull(other, "other");
        return primitiveWork >= value.primitiveWork
            && matchingWork >= value.matchingWork
            && sourceValidationWork >= value.sourceValidationWork
            && factorizationWork >= value.factorizationWork
            && verificationWork >= value.verificationWork
            && renderingWork >= value.renderingWork
            && reparseWork >= value.reparseWork
            && reconstructionWork >= value.reconstructionWork
            && occurrenceReplacementWork >= value.occurrenceReplacementWork
            && cacheLookupWork >= value.cacheLookupWork
            && cacheInsertionWork >= value.cacheInsertionWork
            && cacheEvictionWork >= value.cacheEvictionWork
            && cacheReplayWork >= value.cacheReplayWork
            && evidenceConstructionWork >= value.evidenceConstructionWork;
    }

    void appendIdentityMaterial(StringBuilder target) {
        Objects.requireNonNull(target, "target");
        append(target, primitiveWork);
        append(target, matchingWork);
        append(target, sourceValidationWork);
        append(target, factorizationWork);
        append(target, verificationWork);
        append(target, renderingWork);
        append(target, reparseWork);
        append(target, reconstructionWork);
        append(target, occurrenceReplacementWork);
        append(target, cacheLookupWork);
        append(target, cacheInsertionWork);
        append(target, cacheEvictionWork);
        append(target, cacheReplayWork);
        append(target, evidenceConstructionWork);
    }

    private static long mechanicalWork(
        long matching,
        long sourceValidation,
        long factorization,
        long verification,
        long rendering,
        long reparse,
        long reconstruction,
        long occurrenceReplacement,
        long cacheLookup,
        long cacheInsertion,
        long cacheEviction,
        long cacheReplay,
        long evidenceConstruction
    ) {
        long total = 0L;
        total = Math.addExact(total, matching);
        total = Math.addExact(total, sourceValidation);
        total = Math.addExact(total, factorization);
        total = Math.addExact(total, verification);
        total = Math.addExact(total, rendering);
        total = Math.addExact(total, reparse);
        total = Math.addExact(total, reconstruction);
        total = Math.addExact(total, occurrenceReplacement);
        total = Math.addExact(total, cacheLookup);
        total = Math.addExact(total, cacheInsertion);
        total = Math.addExact(total, cacheEviction);
        total = Math.addExact(total, cacheReplay);
        return Math.addExact(total, evidenceConstruction);
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                    "work components must be non-negative"
                );
            }
        }
    }

    private static void append(StringBuilder target, long value) {
        String text = Long.toString(value);
        target.append(text.length()).append(':').append(text);
    }
}
