package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TargetFreeRepresentationStudyConclusionTest {
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

    @Test
    void retainsTheBoundedScientificConclusionOfTheExactMatrix() {
        var first = TargetFreeRepresentationStudyConclusion.conclude(
            FREEZE,
            QUALIFICATION
        );
        var second = TargetFreeRepresentationStudyConclusion.conclude(
            FREEZE,
            QUALIFICATION
        );
        var content = first.content();
        var summary = content.summary();

        assertEquals(first, second);
        assertEquals(24, summary.configuredEntries());
        assertEquals(41, summary.evaluatedCandidates());
        assertEquals(20, summary.qualifiedEntries());
        assertEquals(20, summary.qualifiedCandidates());
        assertEquals(6, summary.configuredCases());
        assertEquals(5, summary.uniformlyQualifiedCaseCount());
        assertEquals(1, summary.uniformlyUnqualifiedCaseCount());
        assertEquals(0, summary.policyDifferentiatingCaseCount());
        assertEquals(4, summary.configuredPolicies());
        assertEquals(0, summary.allPolicyMatchedWorkCaseCount());
        assertEquals(20, summary.qualifiedCandidatesAtDepthOne());
        assertEquals(0, summary.qualifiedCandidatesAtDepthAtLeastThree());
        assertEquals(0, summary.assumptionViolationCandidates());
        assertEquals(0, summary.directRuleLeakCandidates());

        assertEquals(
            TargetFreeRepresentationStudyConclusion.RESULT_CLASSIFICATION,
            content.decision().evidenceClassification()
        );
        assertEquals(
            TargetFreeRepresentationStudyConclusion
                .POLICY_COMPARISON_STATUS,
            content.decision().policyComparisonStatus()
        );
        assertEquals(
            TargetFreeRepresentationStudyConclusion.DIFFICULTY_STATUS,
            content.decision().difficultyStatus()
        );
        assertEquals(
            TargetFreeRepresentationStudyConclusion.NEXT_REQUIRED_STAGE,
            content.decision().nextRequiredStage()
        );

        assertTrue(content.policies().stream().allMatch(policy ->
            policy.configuredCases() == 6
                && policy.qualifiedCases() == 5
        ));
        assertEquals(
            List.of(
                "assumption-sensitive-cancellation-control",
                "catalog-blind-trigonometric-bridge",
                "neutral-element-compression",
                "occurrence-local-trigonometric-bridge",
                "repeated-term-compression",
                "telescoping-capability-bridge"
            ),
            content.cases().stream()
                .map(TargetFreeRepresentationStudyConclusion
                    .CaseConclusion::caseId)
                .toList()
        );
        assertEquals(
            "NO_POLICY_QUALIFIED",
            content.cases().stream()
                .filter(value -> value.caseId().equals(
                    "repeated-term-compression"))
                .findFirst().orElseThrow().status()
        );
        assertTrue(content.cases().stream()
            .filter(value -> !value.caseId().equals(
                "repeated-term-compression"))
            .allMatch(value -> value.status().equals(
                "QUALIFIED_BY_ALL_POLICIES")));

        String canonical = first.toCanonicalJson();
        assertEquals(
            first,
            TargetFreeRepresentationStudyConclusion.ConclusionArtifact
                .fromCanonicalJson(canonical)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationStudyConclusion.ConclusionArtifact
                .fromCanonicalJson(canonical + "\n")
        );
    }

    @Test
    void rejectsAQualificationBoundToAnotherFreeze() {
        var content = QUALIFICATION.content();
        var changed = new TargetFreeRepresentationPostFreezeQualification
            .QualificationContent(
                content.schema(),
                content.evidenceStatus(),
                content.repositoryRevision(),
                content.evaluationPlanHash(),
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                content.qualificationResource(),
                content.qualificationHash(),
                content.qualificationByteLength(),
                content.qualificationDisclosure(),
                content.entries(),
                content.summary(),
                content.qualificationClaimBoundary(),
                content.claimBoundary()
            );
        var changedArtifact =
            TargetFreeRepresentationPostFreezeQualification
                .QualificationArtifact.create(changed);

        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationStudyConclusion.conclude(
                FREEZE,
                changedArtifact
            )
        );
    }
}
