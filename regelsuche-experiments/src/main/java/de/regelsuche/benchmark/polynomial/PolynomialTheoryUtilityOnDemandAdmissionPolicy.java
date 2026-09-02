package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen pre-execution admission policy for the native on-demand profile.
 *
 * <p>The two floors are derived only from visible, content-addressed formation
 * data before profile execution: for every non-tiny formation row at the full
 * checkpoint, the row authority is split by the already frozen occurrence
 * plan; the least resulting per-occurrence mechanical and factorization
 * authorities become the all-or-none execution floors. Qualification data and
 * mathematical outcomes are neither loaded nor represented here.</p>
 */
public final class PolynomialTheoryUtilityOnDemandAdmissionPolicy {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-on-demand-admission/v1";
    public static final String PROFILE_ID =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    public static final String FULL_CHECKPOINT_ID = "CP06_FULL";
    public static final String TINY_CONTROL_SUFFIX = "-tiny-budget";
    public static final String CONTROL_EXCLUSION =
        "VISIBLE_CASE_ID_SUFFIX:" + TINY_CONTROL_SUFFIX;
    public static final long FROZEN_MINIMUM_MECHANICAL_AUTHORITY = 256L;
    public static final long FROZEN_MINIMUM_FACTORIZATION_AUTHORITY = 16L;

    private static final Policy FROZEN = derive();

    private PolynomialTheoryUtilityOnDemandAdmissionPolicy() {
    }

    public static Policy freeze() {
        return FROZEN;
    }

    private static Policy derive() {
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        Map<String, PolynomialTheoryUtilityCaseCorpus.FormationCase> cases =
            new LinkedHashMap<>();
        formation.cases().forEach(value -> cases.put(value.caseId(), value));

        List<PolynomialTheoryUtilityExecutionInput> fullInputs =
            PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
                .filter(value -> PROFILE_ID.equals(value.profileId()))
                .filter(value ->
                    FULL_CHECKPOINT_ID.equals(value.checkpointId())
                )
                .toList();
        if (fullInputs.size() != formation.cases().size()) {
            throw new IllegalStateException(
                "on-demand admission derivation lacks one full formation row"
            );
        }

        long minimumMechanical = Long.MAX_VALUE;
        long minimumFactorization = Long.MAX_VALUE;
        int eligibleOccurrences = 0;
        for (var input : fullInputs) {
            var formationCase = cases.get(input.caseId());
            if (formationCase == null) {
                throw new IllegalStateException(
                    "on-demand admission input is absent from formation"
                );
            }
            if (formationCase.caseId().endsWith(TINY_CONTROL_SUFFIX)) {
                continue;
            }
            var plan = PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
                input,
                formationCase
            );
            for (var occurrence : plan.occurrences()) {
                minimumMechanical = Math.min(
                    minimumMechanical,
                    occurrence.totalMechanicalWork()
                );
                minimumFactorization = Math.min(
                    minimumFactorization,
                    occurrence.factorizationWork()
                );
                eligibleOccurrences++;
            }
        }
        if (eligibleOccurrences < 1
                || minimumMechanical
                    != FROZEN_MINIMUM_MECHANICAL_AUTHORITY
                || minimumFactorization
                    != FROZEN_MINIMUM_FACTORIZATION_AUTHORITY) {
            throw new IllegalStateException(
                "visible formation no longer derives the frozen on-demand "
                    + "admission floors; bump the policy revision before "
                    + "profile execution"
            );
        }

        return Policy.create(
            formation.contentHash(),
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH,
            CONTROL_EXCLUSION,
            eligibleOccurrences,
            minimumMechanical,
            minimumFactorization
        );
    }

    /** Content-addressed all-or-none admission decision. */
    public record Policy(
        String policyId,
        String schema,
        String formationContentHash,
        String executionPlanContentHash,
        String controlExclusion,
        int eligibleOccurrenceCount,
        long minimumMechanicalAuthority,
        long minimumFactorizationAuthority
    ) {
        public Policy {
            policyId = requireHash(policyId, "policyId");
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "on-demand admission schema differs from its revision"
                );
            }
            formationContentHash = requireHash(
                formationContentHash,
                "formationContentHash"
            );
            executionPlanContentHash = requireHash(
                executionPlanContentHash,
                "executionPlanContentHash"
            );
            controlExclusion = requireText(
                controlExclusion,
                "controlExclusion"
            );
            if (eligibleOccurrenceCount < 1
                    || minimumMechanicalAuthority < 1L
                    || minimumFactorizationAuthority < 1L) {
                throw new IllegalArgumentException(
                    "on-demand admission authority is invalid"
                );
            }
            String expected = identity(
                formationContentHash,
                executionPlanContentHash,
                controlExclusion,
                eligibleOccurrenceCount,
                minimumMechanicalAuthority,
                minimumFactorizationAuthority
            );
            if (!policyId.equals(expected)) {
                throw new IllegalArgumentException(
                    "on-demand admission identity differs from its evidence"
                );
            }
        }

        private static Policy create(
            String formationContentHash,
            String executionPlanContentHash,
            String controlExclusion,
            int eligibleOccurrenceCount,
            long minimumMechanicalAuthority,
            long minimumFactorizationAuthority
        ) {
            return new Policy(
                identity(
                    formationContentHash,
                    executionPlanContentHash,
                    controlExclusion,
                    eligibleOccurrenceCount,
                    minimumMechanicalAuthority,
                    minimumFactorizationAuthority
                ),
                SCHEMA,
                formationContentHash,
                executionPlanContentHash,
                controlExclusion,
                eligibleOccurrenceCount,
                minimumMechanicalAuthority,
                minimumFactorizationAuthority
            );
        }

        public boolean admits(
            PolynomialTheoryUtilityOnDemandOccurrencePlan.Plan plan
        ) {
            var value = Objects.requireNonNull(plan, "plan");
            return value.occurrences().stream().allMatch(this::admits);
        }

        public boolean admits(
            PolynomialTheoryUtilityOnDemandOccurrencePlan.Occurrence occurrence
        ) {
            var value = Objects.requireNonNull(occurrence, "occurrence");
            return value.totalMechanicalWork() >= minimumMechanicalAuthority
                && value.factorizationWork()
                    >= minimumFactorizationAuthority;
        }

        public String canonicalMaterial() {
            StringBuilder material = new StringBuilder();
            append(material, schema);
            append(material, formationContentHash);
            append(material, executionPlanContentHash);
            append(material, controlExclusion);
            append(material, Integer.toString(eligibleOccurrenceCount));
            append(material, Long.toString(minimumMechanicalAuthority));
            append(material, Long.toString(minimumFactorizationAuthority));
            append(material, policyId);
            return material.toString();
        }

        private static String identity(
            String formationContentHash,
            String executionPlanContentHash,
            String controlExclusion,
            int eligibleOccurrenceCount,
            long minimumMechanicalAuthority,
            long minimumFactorizationAuthority
        ) {
            StringBuilder material = new StringBuilder();
            append(material, SCHEMA);
            append(
                material,
                requireHash(formationContentHash, "formationContentHash")
            );
            append(
                material,
                requireHash(
                    executionPlanContentHash,
                    "executionPlanContentHash"
                )
            );
            append(
                material,
                requireText(controlExclusion, "controlExclusion")
            );
            append(material, Integer.toString(eligibleOccurrenceCount));
            append(material, Long.toString(minimumMechanicalAuthority));
            append(material, Long.toString(minimumFactorizationAuthority));
            return PolynomialTheoryUtilityExecutionIdentity.sha256(
                material.toString().getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!text.matches("sha256:[0-9a-f]{64}")) {
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
}
