package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One occurrence-bound, verifier-backed transition retained by a study row.
 * The numeric path is an evidence projection of the shared TreePosition model,
 * not a second navigation or staleness authority.
 */
public record PolynomialTheoryUtilityTransitionOutcome(
    String transitionId,
    int transitionIndex,
    String executionInputId,
    List<Integer> occurrencePath,
    String sourceOccurrenceExpression,
    String transformedOccurrenceExpression,
    String sourceRootExpression,
    String transformedRootExpression,
    String transformationId,
    String backendId,
    String sourceEvidenceHash,
    String transitionEvidenceHash,
    CacheDisposition cacheDisposition,
    String cacheRevision,
    String cacheEntryId,
    String evictedCacheEntryId,
    PolynomialTheoryUtilityWorkBreakdown work
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-transition-outcome/v1";
    public static final String NO_CACHE_LINEAGE = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityTransitionOutcome {
        transitionId = requireHash(transitionId, "transitionId");
        if (transitionIndex < 0) {
            throw new IllegalArgumentException(
                "transitionIndex must be non-negative"
            );
        }
        executionInputId = requireHash(
            executionInputId,
            "executionInputId"
        );
        occurrencePath = immutablePath(occurrencePath);
        sourceOccurrenceExpression = requireText(
            sourceOccurrenceExpression,
            "sourceOccurrenceExpression"
        );
        transformedOccurrenceExpression = requireText(
            transformedOccurrenceExpression,
            "transformedOccurrenceExpression"
        );
        sourceRootExpression = requireText(
            sourceRootExpression,
            "sourceRootExpression"
        );
        transformedRootExpression = requireText(
            transformedRootExpression,
            "transformedRootExpression"
        );
        transformationId = requireText(
            transformationId,
            "transformationId"
        );
        backendId = requireText(backendId, "backendId");
        sourceEvidenceHash = requireHash(
            sourceEvidenceHash,
            "sourceEvidenceHash"
        );
        transitionEvidenceHash = requireHash(
            transitionEvidenceHash,
            "transitionEvidenceHash"
        );
        cacheDisposition = Objects.requireNonNull(
            cacheDisposition,
            "cacheDisposition"
        );
        cacheRevision = requireText(cacheRevision, "cacheRevision");
        cacheEntryId = requireText(cacheEntryId, "cacheEntryId");
        evictedCacheEntryId = requireText(
            evictedCacheEntryId,
            "evictedCacheEntryId"
        );
        work = Objects.requireNonNull(work, "work");

        if (work.primitiveWork() < 1L) {
            throw new IllegalArgumentException(
                "validated transition must retain primitive work"
            );
        }
        if (sourceOccurrenceExpression.equals(
                transformedOccurrenceExpression)
                || sourceRootExpression.equals(transformedRootExpression)) {
            throw new IllegalArgumentException(
                "transition must change its occurrence and root"
            );
        }
        if (occurrencePath.isEmpty()
                && (!sourceOccurrenceExpression.equals(sourceRootExpression)
                    || !transformedOccurrenceExpression.equals(
                        transformedRootExpression))) {
            throw new IllegalArgumentException(
                "root occurrence must equal its surrounding root"
            );
        }
        requireCacheSemantics(
            cacheDisposition,
            cacheRevision,
            cacheEntryId,
            evictedCacheEntryId,
            work
        );
        if (!transitionId.equals(identity(
                transitionIndex,
                executionInputId,
                occurrencePath,
                sourceOccurrenceExpression,
                transformedOccurrenceExpression,
                sourceRootExpression,
                transformedRootExpression,
                transformationId,
                backendId,
                sourceEvidenceHash,
                transitionEvidenceHash,
                cacheDisposition,
                cacheRevision,
                cacheEntryId,
                evictedCacheEntryId,
                work))) {
            throw new IllegalArgumentException(
                "transition identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityTransitionOutcome create(
        int transitionIndex,
        String executionInputId,
        List<Integer> occurrencePath,
        String sourceOccurrenceExpression,
        String transformedOccurrenceExpression,
        String sourceRootExpression,
        String transformedRootExpression,
        String transformationId,
        String backendId,
        String sourceEvidenceHash,
        String transitionEvidenceHash,
        CacheDisposition cacheDisposition,
        String cacheRevision,
        String cacheEntryId,
        String evictedCacheEntryId,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        List<Integer> path = immutablePath(occurrencePath);
        var retainedWork = Objects.requireNonNull(work, "work");
        return new PolynomialTheoryUtilityTransitionOutcome(
            identity(
                transitionIndex,
                executionInputId,
                path,
                sourceOccurrenceExpression,
                transformedOccurrenceExpression,
                sourceRootExpression,
                transformedRootExpression,
                transformationId,
                backendId,
                sourceEvidenceHash,
                transitionEvidenceHash,
                cacheDisposition,
                cacheRevision,
                cacheEntryId,
                evictedCacheEntryId,
                retainedWork
            ),
            transitionIndex,
            executionInputId,
            path,
            sourceOccurrenceExpression,
            transformedOccurrenceExpression,
            sourceRootExpression,
            transformedRootExpression,
            transformationId,
            backendId,
            sourceEvidenceHash,
            transitionEvidenceHash,
            cacheDisposition,
            cacheRevision,
            cacheEntryId,
            evictedCacheEntryId,
            retainedWork
        );
    }

    public String schema() {
        return SCHEMA;
    }

    void validateAgainst(
        int expectedIndex,
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase,
        PolynomialTheoryUtilityExecutionProfile profile
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(formationCase, "formationCase");
        Objects.requireNonNull(profile, "profile");
        if (transitionIndex != expectedIndex
                || !executionInputId.equals(input.inputId())
                || !input.caseId().equals(formationCase.caseId())
                || !sourceRootExpression.equals(
                    formationCase.sourceExpression())
                || !transformationId.equals(profile.transformationId())
                || !backendId.equals(profile.engineId())
                || "DISABLED".equals(profile.factorizationMode())
                || "NONE".equals(profile.transformationId())
                || "NONE".equals(profile.engineId())) {
            throw new IllegalArgumentException(
                "transition differs from its frozen profile or source"
            );
        }

        boolean cacheEnabled = "READ_WRITE".equals(profile.cacheMode());
        if (cacheEnabled) {
            if (cacheDisposition == CacheDisposition.CACHE_DISABLED
                    || !PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION
                        .equals(cacheRevision)) {
                throw new IllegalArgumentException(
                    "cache-enabled transition lacks frozen cache lineage"
                );
            }
        } else if (cacheDisposition != CacheDisposition.CACHE_DISABLED) {
            throw new IllegalArgumentException(
                "cache-disabled profile retained cache lineage"
            );
        }
    }

    private static String identity(
        int transitionIndex,
        String executionInputId,
        List<Integer> occurrencePath,
        String sourceOccurrenceExpression,
        String transformedOccurrenceExpression,
        String sourceRootExpression,
        String transformedRootExpression,
        String transformationId,
        String backendId,
        String sourceEvidenceHash,
        String transitionEvidenceHash,
        CacheDisposition cacheDisposition,
        String cacheRevision,
        String cacheEntryId,
        String evictedCacheEntryId,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, Integer.toString(transitionIndex));
        append(
            material,
            requireHash(executionInputId, "executionInputId")
        );
        append(material, Integer.toString(occurrencePath.size()));
        for (Integer element : occurrencePath) {
            append(material, Integer.toString(element));
        }
        append(
            material,
            requireText(
                sourceOccurrenceExpression,
                "sourceOccurrenceExpression"
            )
        );
        append(
            material,
            requireText(
                transformedOccurrenceExpression,
                "transformedOccurrenceExpression"
            )
        );
        append(
            material,
            requireText(sourceRootExpression, "sourceRootExpression")
        );
        append(
            material,
            requireText(transformedRootExpression, "transformedRootExpression")
        );
        append(material, requireText(transformationId, "transformationId"));
        append(material, requireText(backendId, "backendId"));
        append(
            material,
            requireHash(sourceEvidenceHash, "sourceEvidenceHash")
        );
        append(
            material,
            requireHash(transitionEvidenceHash, "transitionEvidenceHash")
        );
        append(
            material,
            Objects.requireNonNull(
                cacheDisposition,
                "cacheDisposition"
            ).name()
        );
        append(material, requireText(cacheRevision, "cacheRevision"));
        append(material, requireText(cacheEntryId, "cacheEntryId"));
        append(
            material,
            requireText(evictedCacheEntryId, "evictedCacheEntryId")
        );
        Objects.requireNonNull(work, "work").appendIdentityMaterial(material);
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void requireCacheSemantics(
        CacheDisposition disposition,
        String revision,
        String entryId,
        String evictedEntryId,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        switch (disposition) {
            case CACHE_DISABLED -> requireDisabledCache(
                revision,
                entryId,
                evictedEntryId,
                work
            );
            case CACHE_MISS_INSERTED -> requireCacheMiss(
                revision,
                entryId,
                evictedEntryId,
                work
            );
            case CACHE_HIT_REPLAYED -> requireCacheHit(
                revision,
                entryId,
                evictedEntryId,
                work
            );
        }
    }

    private static void requireDisabledCache(
        String revision,
        String entryId,
        String evictedEntryId,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        if (!NO_CACHE_LINEAGE.equals(revision)
                || !NO_CACHE_LINEAGE.equals(entryId)
                || !NO_CACHE_LINEAGE.equals(evictedEntryId)
                || work.cacheLookupWork() != 0L
                || work.cacheInsertionWork() != 0L
                || work.cacheEvictionWork() != 0L
                || work.cacheReplayWork() != 0L) {
            throw new IllegalArgumentException(
                "cache-disabled transition retained cache state or work"
            );
        }
    }

    private static void requireCacheMiss(
        String revision,
        String entryId,
        String evictedEntryId,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        requireCacheLineage(revision, entryId);
        if (work.cacheLookupWork() < 1L
                || work.cacheInsertionWork() < 1L
                || work.cacheReplayWork() != 0L
                || work.factorizationWork() < 1L) {
            throw new IllegalArgumentException(
                "cache insertion lacks lookup, insertion or factorization work"
            );
        }
        boolean evictionRetained = !NO_CACHE_LINEAGE.equals(
            evictedEntryId
        );
        if (evictionRetained != (work.cacheEvictionWork() > 0L)) {
            throw new IllegalArgumentException(
                "cache eviction identity and work differ"
            );
        }
        if (evictionRetained && !SHA_256.matcher(evictedEntryId).matches()) {
            throw new IllegalArgumentException(
                "evicted cache entry is not SHA-256"
            );
        }
    }

    private static void requireCacheHit(
        String revision,
        String entryId,
        String evictedEntryId,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        requireCacheLineage(revision, entryId);
        if (!NO_CACHE_LINEAGE.equals(evictedEntryId)
                || work.cacheLookupWork() < 1L
                || work.cacheInsertionWork() != 0L
                || work.cacheEvictionWork() != 0L
                || work.cacheReplayWork() < 1L
                || work.factorizationWork() != 0L) {
            throw new IllegalArgumentException(
                "cache replay has inconsistent state or work"
            );
        }
    }

    private static void requireCacheLineage(
        String revision,
        String entryId
    ) {
        if (!PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION.equals(
                revision)
                || !SHA_256.matcher(entryId).matches()) {
            throw new IllegalArgumentException(
                "cache transition differs from the frozen cache contract"
            );
        }
    }

    private static List<Integer> immutablePath(List<Integer> value) {
        Objects.requireNonNull(value, "occurrencePath");
        List<Integer> path = new ArrayList<>(value.size());
        for (Integer element : value) {
            if (element == null || element < 0) {
                throw new IllegalArgumentException(
                    "occurrencePath contains an invalid element"
                );
            }
            path.add(element);
        }
        return List.copyOf(path);
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    public enum CacheDisposition {
        CACHE_DISABLED,
        CACHE_MISS_INSERTED,
        CACHE_HIT_REPLAYED
    }
}
