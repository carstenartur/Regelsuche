package de.regelsuche.benchmark.polynomial;

import java.util.List;

/** Canonical UTF-8/LF representation of the frozen execution matrix. */
final class PolynomialTheoryUtilityExecutionJson {
    private PolynomialTheoryUtilityExecutionJson() {
    }

    static String canonical(
        List<PolynomialTheoryUtilityExecutionRow> rows
    ) {
        StringBuilder target = new StringBuilder(240_000);
        target.append("{\n");
        field(target, "schema", PolynomialTheoryUtilityExecutionPlan.SCHEMA);
        field(
            target,
            "studyId",
            PolynomialTheoryUtilityPreregistration.STUDY_ID
        );
        field(
            target,
            "evidenceStatus",
            PolynomialTheoryUtilityExecutionPlan.EVIDENCE_STATUS
        );
        field(
            target,
            "planSelectionTiming",
            PolynomialTheoryUtilityExecutionPlan.PLAN_SELECTION_TIMING
        );
        field(
            target,
            "profileSelectionTiming",
            PolynomialTheoryUtilityPreregistration.PROFILE_SELECTION_TIMING
        );
        field(
            target,
            "qualificationExposure",
            PolynomialTheoryUtilityExecutionPlan.QUALIFICATION_EXPOSURE
        );
        binding(
            target,
            "preregistrationBinding",
            PolynomialTheoryUtilityPreregistration.BYTE_LENGTH,
            PolynomialTheoryUtilityPreregistration.CONTENT_HASH
        );
        binding(
            target,
            "formationBinding",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
        );
        target.append("  \"qualificationBinding\": {\"path\":\"")
            .append(PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME)
            .append("\",\"byteLength\":")
            .append(PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH)
            .append(",\"contentHash\":\"")
            .append(PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH)
            .append("\"},\n");
        number(target, "caseCount", 20);
        number(
            target,
            "profileCount",
            PolynomialTheoryUtilityExecutionPlan.PROFILES.size()
        );
        number(
            target,
            "checkpointCount",
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size()
        );
        number(target, "rowCount", rows.size());
        workMatching(target);
        executionSchedule(target);
        sharedAuthorities(target);
        target.append("  \"profiles\": [\n");
        appendProfiles(target);
        target.append("  ],\n  \"checkpoints\": [\n");
        appendCheckpoints(target);
        target.append("  ],\n  \"rows\": [\n");
        appendRows(target, rows);
        return target.append("  ]\n}\n").toString();
    }

    private static void workMatching(StringBuilder target) {
        target.append("  \"workMatching\": {")
            .append("\"visibleInventory\":\"IDENTICAL_ACROSS_PROFILES\",")
            .append("\"assumptions\":\"IDENTICAL_ACROSS_PROFILES\",")
            .append("\"admittedPrimitiveWork\":")
            .append("\"MATCH_AT_EVERY_POLICY_CHECKPOINT\",")
            .append("\"totalMechanicalWork\":")
            .append("\"MATCH_AT_EVERY_POLICY_CHECKPOINT\",")
            .append("\"factorizationWork\":")
            .append("\"MATCH_AT_EVERY_POLICY_CHECKPOINT\",")
            .append("\"runtimeRole\":")
            .append("\"ENVIRONMENT_QUALIFIED_DIAGNOSTIC_ONLY\",")
            .append("\"hiddenBestOfSelection\":\"FORBIDDEN\"},\n");
    }

    private static void executionSchedule(StringBuilder target) {
        target.append("  \"executionSchedule\": {")
            .append("\"runGrouping\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.RUN_GROUPING)
            .append("\",\"caseOrder\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CASE_ORDER)
            .append("\",\"profileIsolation\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.PROFILE_ISOLATION)
            .append("\",\"checkpointIsolation\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CHECKPOINT_ISOLATION)
            .append("\",\"cacheInitialState\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_INITIAL_STATE)
            .append("\",\"cacheLifetime\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_LIFETIME)
            .append("\",\"qualificationAccess\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.QUALIFICATION_ACCESS)
            .append("\",\"backendSubstitution\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.BACKEND_SUBSTITUTION)
            .append("\",\"failureRetention\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.FAILURE_RETENTION)
            .append("\"},\n");
    }

    private static void sharedAuthorities(StringBuilder target) {
        target.append("  \"sharedAuthorities\": {\"verifierId\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.VERIFIER_ID)
            .append("\",\"exactTransformationId\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID)
            .append("\",\"cacheSchema\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_SCHEMA)
            .append("\",\"cacheRevision\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION)
            .append("\",\"cacheCapacity\":")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_CAPACITY)
            .append(",\"cacheLookup\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_LOOKUP)
            .append("\",\"cacheReplay\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_REPLAY)
            .append("\",\"cacheEviction\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.CACHE_EVICTION)
            .append("\",\"externalRuntimeId\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.EXTERNAL_RUNTIME_ID)
            .append("\",\"externalLockPath\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.EXTERNAL_LOCK_PATH)
            .append("\"},\n");
    }

    private static void appendProfiles(StringBuilder target) {
        var values = PolynomialTheoryUtilityExecutionPlan.PROFILES;
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            target.append("    {\"profileId\":\"")
                .append(value.profileId())
                .append("\",\"adapterId\":\"").append(value.adapterId())
                .append("\",\"scope\":\"").append(value.scope())
                .append("\",\"factorizationMode\":\"")
                .append(value.factorizationMode())
                .append("\",\"engineId\":\"").append(value.engineId())
                .append("\",\"transformationId\":\"")
                .append(value.transformationId())
                .append("\",\"cacheMode\":\"").append(value.cacheMode())
                .append("\",\"fallbackMode\":\"")
                .append(value.fallbackMode())
                .append("\",\"candidateSelection\":\"")
                .append(value.candidateSelection()).append("\"}")
                .append(index + 1 < values.size() ? ",\n" : "\n");
        }
    }

    private static void appendCheckpoints(StringBuilder target) {
        var values = PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS;
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            target.append("    {\"checkpointId\":\"")
                .append(value.checkpointId())
                .append("\",\"ordinal\":").append(value.ordinal())
                .append(",\"numerator\":").append(value.numerator())
                .append(",\"denominator\":").append(value.denominator())
                .append("}")
                .append(index + 1 < values.size() ? ",\n" : "\n");
        }
    }

    private static void appendRows(
        StringBuilder target,
        List<PolynomialTheoryUtilityExecutionRow> rows
    ) {
        for (int index = 0; index < rows.size(); index++) {
            var value = rows.get(index);
            target.append("    {\"rowId\":\"").append(value.rowId())
                .append("\",\"runId\":\"").append(value.runId())
                .append("\",\"caseId\":\"").append(value.caseId())
                .append("\",\"profileId\":\"").append(value.profileId())
                .append("\",\"checkpointId\":\"")
                .append(value.checkpointId())
                .append("\",\"admittedPrimitiveWork\":")
                .append(value.admittedPrimitiveWork())
                .append(",\"totalMechanicalWork\":")
                .append(value.totalMechanicalWork())
                .append(",\"factorizationWork\":")
                .append(value.factorizationWork())
                .append(",\"resultStatus\":\"")
                .append(value.resultStatus()).append("\"}")
                .append(index + 1 < rows.size() ? ",\n" : "\n");
        }
    }

    private static void field(
        StringBuilder target,
        String name,
        String value
    ) {
        target.append("  \"").append(name).append("\": \"")
            .append(value).append("\",\n");
    }

    private static void number(
        StringBuilder target,
        String name,
        long value
    ) {
        target.append("  \"").append(name).append("\": ")
            .append(value).append(",\n");
    }

    private static void binding(
        StringBuilder target,
        String name,
        long byteLength,
        String hash
    ) {
        target.append("  \"").append(name)
            .append("\": {\"byteLength\":").append(byteLength)
            .append(",\"contentHash\":\"").append(hash)
            .append("\"},\n");
    }
}
