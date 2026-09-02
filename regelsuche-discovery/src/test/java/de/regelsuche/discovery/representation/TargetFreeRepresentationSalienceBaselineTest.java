package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Freezes the first empirical separation between search and salience failures.
 *
 * <p>The assertions intentionally run the unchanged pre-existing formation and
 * post-freeze qualification. They are a baseline, not a repair of the assessor
 * or its thresholds.</p>
 */
class TargetFreeRepresentationSalienceBaselineTest {
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final TargetFreeRepresentationCandidateFreeze.FreezeArtifact
        FREEZE = TargetFreeRepresentationCandidateFreeze.run(
            REPOSITORY_REVISION);
    private static final TargetFreeRepresentationPostFreezeQualification
        .QualificationArtifact QUALIFICATION =
            TargetFreeRepresentationPostFreezeQualification.qualify(
                FREEZE,
                REPOSITORY_REVISION
            );
    private static final Map<
        String,
        TargetFreeRepresentationCandidateFreeze.ExecutionEntry
    > FROZEN_BY_CONFIGURATION = FREEZE.content().entries().stream()
        .collect(Collectors.toUnmodifiableMap(
            TargetFreeRepresentationCandidateFreeze.ExecutionEntry
                ::configurationId,
            Function.identity()
        ));

    @Test
    void provesRepeatedTermIsRetainedButNotRecognized() {
        var rows = QUALIFICATION.content().entries().stream()
            .filter(value -> "repeated-term-compression".equals(
                value.caseId()))
            .toList();

        assertEquals(4, rows.size());
        for (var row : rows) {
            var reference = row.candidates().stream()
                .filter(TargetFreeRepresentationPostFreezeQualification
                    .QualifiedCandidate::referenceMatched)
                .findFirst()
                .orElseThrow();
            var frozen = FROZEN_BY_CONFIGURATION.get(row.configurationId());

            assertNotNull(frozen);
            assertTrue(frozen.candidates().stream().anyMatch(value ->
                value.candidateHash().equals(reference.candidateHash())
                    && value.expression().equals(reference.expression())
            ));
            assertEquals("2 * x", reference.expression());
            assertEquals(
                java.util.List.of("NO_MATERIAL_REPRESENTATION_GAIN"),
                reference.candidateTypes()
            );
            assertEquals(
                Set.of(
                    "ACCEPTED_CANDIDATE_TYPE_NOT_OBSERVED",
                    "FORBIDDEN_OUTCOME_OBSERVED",
                    "TOKEN_SAVINGS_BELOW_MINIMUM"
                ),
                Set.copyOf(reference.disqualificationReasons())
            );
            assertFalse(reference.qualified());
        }
    }

    @Test
    void reportsPerfectRetentionButOnlyFiveSixthsRecognition() {
        long retainedReferenceRows = QUALIFICATION.content().entries().stream()
            .filter(row -> row.candidates().stream().anyMatch(candidate ->
                candidate.referenceMatched()
                    && requireFrozenConfiguration(row.configurationId())
                        .candidates().stream().anyMatch(frozen ->
                            frozen.candidateHash().equals(
                                candidate.candidateHash()))
            ))
            .count();
        long recognizedReferenceRows =
            QUALIFICATION.content().entries().stream()
                .filter(row -> row.candidates().stream().anyMatch(candidate ->
                    candidate.referenceMatched()
                        && !candidate.candidateTypes().contains(
                            "NO_MATERIAL_REPRESENTATION_GAIN")
                ))
                .count();

        assertEquals(24L, retainedReferenceRows);
        assertEquals(20L, recognizedReferenceRows);
        assertEquals(20, QUALIFICATION.content().summary()
            .qualifiedEntries());
    }

    @Test
    void exposesAcEquivalentVariantsBlockedAfterStructuralRecognition() {
        var variants = QUALIFICATION.content().entries().stream()
            .filter(value ->
                value.caseId().contains("trigonometric-bridge"))
            .flatMap(value -> value.candidates().stream())
            .filter(candidate -> !candidate.referenceMatched())
            .filter(candidate -> candidate.candidateTypes().stream()
                .anyMatch(type -> type.equals("KNOWN_WHOLE_FORM_BRIDGE")
                    || type.equals("KNOWN_SUBFORM_BRIDGE")))
            .toList();

        assertEquals(13, variants.size());
        assertTrue(variants.stream().anyMatch(candidate ->
            "cos(x) ^ 2 + sin(x) ^ 2".equals(candidate.expression())
        ));
        assertTrue(variants.stream().allMatch(candidate ->
            "OBSERVED".equals(candidate.proofStatus())
                && "NOT_RUN_REFERENCE_MISS".equals(candidate.oracleStatus())
                && candidate.disqualificationReasons().contains(
                    "REFERENCE_NOT_MATCHED")
        ));
        assertTrue(variants.stream().allMatch(candidate ->
            candidate.disqualificationReasons().contains(
                "SYMBOLIC_VALIDATION_NOT_CONFIRMED")
        ));
    }

    private static TargetFreeRepresentationCandidateFreeze.ExecutionEntry
            requireFrozenConfiguration(String configurationId) {
        var entry = FROZEN_BY_CONFIGURATION.get(configurationId);
        if (entry == null) {
            throw new IllegalStateException(
                "Missing frozen configuration " + configurationId
            );
        }
        return entry;
    }
}
