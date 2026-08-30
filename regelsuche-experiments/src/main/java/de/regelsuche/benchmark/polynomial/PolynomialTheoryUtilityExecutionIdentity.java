package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Content-addresses execution runs, rows and complete artifacts. */
final class PolynomialTheoryUtilityExecutionIdentity {
    private PolynomialTheoryUtilityExecutionIdentity() {
    }

    static String runId(
        PolynomialTheoryUtilityExecutionProfile profile,
        PolynomialTheoryUtilityExecutionCheckpoint checkpoint
    ) {
        StringBuilder material = new StringBuilder();
        for (String value : List.of(
                PolynomialTheoryUtilityExecutionPlan.SCHEMA + ".run",
                PolynomialTheoryUtilityPreregistration.STUDY_ID,
                PolynomialTheoryUtilityPreregistration.CONTENT_HASH,
                PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH,
                PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH,
                PolynomialTheoryUtilityExecutionPlan.VERIFIER_ID,
                PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID,
                PolynomialTheoryUtilityExecutionPlan.CACHE_SCHEMA,
                PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
                Integer.toString(
                    PolynomialTheoryUtilityExecutionPlan.CACHE_CAPACITY),
                PolynomialTheoryUtilityExecutionPlan.CACHE_LOOKUP,
                PolynomialTheoryUtilityExecutionPlan.CACHE_REPLAY,
                PolynomialTheoryUtilityExecutionPlan.CACHE_EVICTION,
                PolynomialTheoryUtilityExecutionPlan.EXTERNAL_RUNTIME_ID,
                PolynomialTheoryUtilityExecutionPlan.EXTERNAL_LOCK_PATH,
                PolynomialTheoryUtilityExecutionPlan.RUN_GROUPING,
                PolynomialTheoryUtilityExecutionPlan.ROW_ORDER,
                PolynomialTheoryUtilityExecutionPlan.CASE_ORDER,
                PolynomialTheoryUtilityExecutionPlan.PROFILE_ISOLATION,
                PolynomialTheoryUtilityExecutionPlan.CHECKPOINT_ISOLATION,
                PolynomialTheoryUtilityExecutionPlan.CACHE_INITIAL_STATE,
                PolynomialTheoryUtilityExecutionPlan.CACHE_LIFETIME,
                PolynomialTheoryUtilityExecutionPlan.QUALIFICATION_ACCESS,
                PolynomialTheoryUtilityExecutionPlan.BACKEND_SUBSTITUTION,
                PolynomialTheoryUtilityExecutionPlan.FAILURE_RETENTION,
                profile.profileId(),
                profile.adapterId(),
                profile.scope(),
                profile.factorizationMode(),
                profile.engineId(),
                profile.transformationId(),
                profile.cacheMode(),
                profile.fallbackMode(),
                profile.candidateSelection(),
                checkpoint.checkpointId(),
                Integer.toString(checkpoint.ordinal()),
                Integer.toString(checkpoint.numerator()),
                Integer.toString(checkpoint.denominator()))) {
            append(material, value);
        }
        return sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String rowId(
        String runId,
        String caseId,
        PolynomialTheoryUtilityExecutionProfile profile,
        PolynomialTheoryUtilityExecutionCheckpoint checkpoint,
        int primitive,
        int mechanical,
        int factorization
    ) {
        StringBuilder material = new StringBuilder();
        for (String value : List.of(
                PolynomialTheoryUtilityExecutionPlan.SCHEMA,
                PolynomialTheoryUtilityPreregistration.STUDY_ID,
                PolynomialTheoryUtilityPreregistration.CONTENT_HASH,
                PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH,
                PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH,
                PolynomialTheoryUtilityExecutionPlan.VERIFIER_ID,
                PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID,
                PolynomialTheoryUtilityExecutionPlan.CACHE_SCHEMA,
                Integer.toString(
                    PolynomialTheoryUtilityExecutionPlan.CACHE_CAPACITY),
                PolynomialTheoryUtilityExecutionPlan.CACHE_LOOKUP,
                PolynomialTheoryUtilityExecutionPlan.CACHE_REPLAY,
                PolynomialTheoryUtilityExecutionPlan.CACHE_EVICTION,
                PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
                PolynomialTheoryUtilityExecutionPlan.EXTERNAL_RUNTIME_ID,
                PolynomialTheoryUtilityExecutionPlan.EXTERNAL_LOCK_PATH,
                PolynomialTheoryUtilityExecutionPlan.RUN_GROUPING,
                PolynomialTheoryUtilityExecutionPlan.ROW_ORDER,
                PolynomialTheoryUtilityExecutionPlan.CASE_ORDER,
                PolynomialTheoryUtilityExecutionPlan.PROFILE_ISOLATION,
                PolynomialTheoryUtilityExecutionPlan.CHECKPOINT_ISOLATION,
                PolynomialTheoryUtilityExecutionPlan.CACHE_INITIAL_STATE,
                PolynomialTheoryUtilityExecutionPlan.CACHE_LIFETIME,
                PolynomialTheoryUtilityExecutionPlan.QUALIFICATION_ACCESS,
                PolynomialTheoryUtilityExecutionPlan.BACKEND_SUBSTITUTION,
                PolynomialTheoryUtilityExecutionPlan.FAILURE_RETENTION,
                runId,
                caseId,
                profile.profileId(),
                profile.adapterId(),
                profile.scope(),
                profile.factorizationMode(),
                profile.engineId(),
                profile.transformationId(),
                profile.cacheMode(),
                profile.fallbackMode(),
                profile.candidateSelection(),
                checkpoint.checkpointId(),
                Integer.toString(checkpoint.ordinal()),
                Integer.toString(checkpoint.numerator()),
                Integer.toString(checkpoint.denominator()),
                Integer.toString(primitive),
                Integer.toString(mechanical),
                Integer.toString(factorization),
                PolynomialTheoryUtilityExecutionPlan.RESULT_STATUS)) {
            append(material, value);
        }
        return sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
