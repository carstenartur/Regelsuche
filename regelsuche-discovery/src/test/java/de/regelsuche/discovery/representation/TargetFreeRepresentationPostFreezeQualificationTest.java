package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TargetFreeRepresentationPostFreezeQualificationTest {
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final TargetFreeRepresentationCandidateFreeze.FreezeArtifact
        FREEZE = TargetFreeRepresentationCandidateFreeze.run(
            REPOSITORY_REVISION);

    @Test
    void opensTheSealedQualificationOnlyForTheExactFrozenMatrix() {
        var first = TargetFreeRepresentationPostFreezeQualification.qualify(
            FREEZE, REPOSITORY_REVISION);
        var second = TargetFreeRepresentationPostFreezeQualification.qualify(
            FREEZE, REPOSITORY_REVISION);
        var content = first.content();

        assertEquals(first, second);
        assertEquals(
            TargetFreeRepresentationPostFreezeQualification.EVIDENCE_STATUS,
            content.evidenceStatus());
        assertEquals(
            TargetFreeRepresentationPostFreezeQualification.DISCLOSURE,
            content.qualificationDisclosure());
        assertEquals(FREEZE.contentHash(), content.candidateFreezeHash());
        assertEquals(24, content.entries().size());
        assertEquals(
            FREEZE.content().summary().candidateCount(),
            content.summary().evaluatedCandidates());
        assertTrue(content.summary().qualifiedEntries() > 0);
        assertTrue(content.summary().qualifiedCandidates() > 0);

        assertEquals(
            FREEZE.content().entries().stream()
                .map(entry -> List.of(
                    entry.configurationId(),
                    entry.candidateBatchHash(),
                    entry.candidateSetHash(),
                    entry.candidateFreezeReceiptHash(),
                    entry.work().contentHash(),
                    entry.workAuthorityHash()))
                .toList(),
            content.entries().stream()
                .map(entry -> List.of(
                    entry.configurationId(),
                    entry.candidateBatchHash(),
                    entry.candidateSetHash(),
                    entry.candidateFreezeReceiptHash(),
                    entry.workLedgerHash(),
                    entry.workAuthorityHash()))
                .toList()
        );

        assertTrue(content.entries().stream()
            .flatMap(entry -> entry.candidates().stream())
            .filter(
                TargetFreeRepresentationPostFreezeQualification
                    .QualifiedCandidate::qualified)
            .allMatch(candidate ->
                candidate.referenceMatched()
                    && candidate.disqualificationReasons().isEmpty()
                    && candidate.proofStatus().equals(
                        "SYMBOLICALLY_VERIFIED"))
        );
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "assumption-sensitive-cancellation-control"))
            .flatMap(entry -> entry.candidates().stream())
            .anyMatch(candidate -> candidate.expression().equals("1")
                && candidate.qualified()
                && candidate.assumptions().contains("x != 0"))
        );
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "catalog-blind-trigonometric-bridge")
                || entry.caseId().equals(
                    "occurrence-local-trigonometric-bridge"))
            .flatMap(entry -> entry.candidates().stream())
            .filter(
                TargetFreeRepresentationPostFreezeQualification
                    .QualifiedCandidate::qualified)
            .anyMatch(candidate ->
                candidate.executableCapabilities().contains(
                    "rule:sympy.trig.pythagorean"))
        );
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "telescoping-capability-bridge"))
            .flatMap(entry -> entry.candidates().stream())
            .filter(
                TargetFreeRepresentationPostFreezeQualification
                    .QualifiedCandidate::qualified)
            .anyMatch(candidate ->
                candidate.executableCapabilities().contains(
                    "rule:sympy.rational.partial_fraction.telescoping"))
        );

        String canonical = first.toCanonicalJson();
        assertEquals(
            first,
            TargetFreeRepresentationPostFreezeQualification
                .QualificationArtifact.fromCanonicalJson(canonical)
        );
        assertFalse(canonical.contains(
            "\"qualificationDisclosure\":\"NOT_DISCLOSED\""));
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationPostFreezeQualification
                .QualificationArtifact.fromCanonicalJson(canonical + "\n")
        );
    }

    @Test
    void rejectsAFreezeBoundToAnotherRepositoryRevision() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationPostFreezeQualification.qualify(
                FREEZE,
                "fedcba9876543210fedcba9876543210fedcba98"
            )
        );
    }
}
