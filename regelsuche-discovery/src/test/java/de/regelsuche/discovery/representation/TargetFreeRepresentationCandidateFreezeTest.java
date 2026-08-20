package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeRepresentationCandidateFreezeTest {
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final List<String> CASE_IDS = List.of(
        "assumption-sensitive-cancellation-control",
        "catalog-blind-trigonometric-bridge",
        "neutral-element-compression",
        "occurrence-local-trigonometric-bridge",
        "repeated-term-compression",
        "telescoping-capability-bridge"
    );
    private static final List<String> POLICY_IDS = List.of(
        "BOUNDED_ENUMERATION_V1",
        "RANDOM_MONTE_CARLO_V1",
        "SCALAR_BEST_FIRST_V1",
        "STRUCTURAL_DIVERSITY_V1"
    );

    @Test
    void executesAndFreezesTheExactTargetBlindMatrix() {
        var artifact = TargetFreeRepresentationCandidateFreeze.run(
            REPOSITORY_REVISION);
        var content = artifact.content();

        assertEquals(
            TargetFreeRepresentationCandidateFreeze.EVIDENCE_STATUS,
            content.evidenceStatus()
        );
        assertEquals(
            TargetFreeRepresentationCandidateFreeze
                .QUALIFICATION_DISCLOSURE,
            content.qualificationDisclosure()
        );
        assertEquals(24, content.entries().size());
        assertEquals(24, content.summary().configuredEntryCount());
        assertEquals(24, content.summary().executedEntryCount());
        assertEquals(
            24L,
            content.entries().stream()
                .map(TargetFreeRepresentationCandidateFreeze
                    .ExecutionEntry::configurationId)
                .distinct()
                .count()
        );
        assertEquals(
            CASE_IDS.stream()
                .flatMap(caseId -> POLICY_IDS.stream()
                    .map(policyId -> caseId + "/" + policyId))
                .toList(),
            content.entries().stream()
                .map(entry -> entry.caseId() + "/" + entry.policyId())
                .toList()
        );
        assertTrue(content.entries().stream().allMatch(entry ->
            entry.candidateCount() == entry.candidates().size()
                && entry.candidateBatchHash().startsWith("sha256:")
                && entry.candidateSetHash().startsWith("sha256:")
                && entry.candidateFreezeReceiptHash().startsWith("sha256:")
                && entry.work().contentHash().startsWith("sha256:")
        ));

        var neutralEnumeration = content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "neutral-element-compression"))
            .filter(entry -> entry.policyId().equals(
                "BOUNDED_ENUMERATION_V1"))
            .findFirst()
            .orElseThrow();
        assertTrue(neutralEnumeration.candidates().stream()
            .anyMatch(candidate -> candidate.expression().equals("x")));

        assertEquals(
            TargetFreeRepresentationCandidateFreeze.VISIBLE_KNOWLEDGE_POLICY,
            content.visibleKnowledgePolicy()
        );
        assertTrue(content.entries().stream().allMatch(entry ->
            entry.appliedSearchBudget().significantImprovementThreshold()
                == entry.configuredBudget().significantImprovementThreshold()
                && entry.appliedSearchBudget().maxExpandingSteps()
                    == entry.configuredBudget().maxExpandingSteps()
                && entry.appliedSearchBudget().beamWidth()
                    == entry.configuredBudget().beamWidth()
        ));
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "assumption-sensitive-cancellation-control"))
            .allMatch(entry -> entry.candidates().stream()
                .anyMatch(candidate -> candidate.expression().equals("1")
                    && candidate.assumptions().contains("x != 0")))
        );
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "catalog-blind-trigonometric-bridge")
                || entry.caseId().equals(
                    "occurrence-local-trigonometric-bridge"))
            .flatMap(entry -> entry.candidates().stream())
            .flatMap(candidate -> candidate.pathRuleIds().stream())
            .noneMatch(rule -> rule.equals(
                "sympy.trig.pythagorean"))
        );
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "telescoping-capability-bridge"))
            .flatMap(entry -> entry.candidates().stream())
            .flatMap(candidate -> candidate.pathRuleIds().stream())
            .noneMatch(rule -> rule.equals(
                "sympy.rational.partial_fraction.telescoping"))
        );

        String canonical = artifact.toCanonicalJson();
        assertFalse(canonical.contains("\"referenceExpressions\""));
        assertFalse(canonical.contains("\"requiredCapabilities\""));
        assertFalse(canonical.contains("\"acceptedCandidateTypes\""));
        assertFalse(canonical.contains(
            "capability:finite-sum-telescoping"));
    }

    @Test
    void nativeEnumerationFailsClosedOnConstructorAndSeedDrift() {
        var valid = TargetFreeRepresentationEvaluationPlan.create(
            REPOSITORY_REVISION
        ).content().policies().stream()
            .filter(policy -> policy.id().equals("BOUNDED_ENUMERATION_V1"))
            .findFirst()
            .orElseThrow();
        TargetFreeRepresentationCandidateFreeze
            .requireEnumerationInvocationContract(valid);

        var wrongConstructor = new TargetFreeRepresentationEvaluationPlan
            .PolicyDefinition(
                valid.id(),
                valid.adapter(),
                TargetFreeRepresentationEvaluationPlan
                    .AdapterConstructor.LONG_SEED,
                valid.adapterInterface(),
                valid.initialAssumptionPolicy(),
                0L,
                valid.selectionBoundary()
            );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationCandidateFreeze
                .requireEnumerationInvocationContract(wrongConstructor)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> {
                var wrongSeed = new TargetFreeRepresentationEvaluationPlan
                    .PolicyDefinition(
                        valid.id(),
                        valid.adapter(),
                        valid.adapterConstructor(),
                        valid.adapterInterface(),
                        valid.initialAssumptionPolicy(),
                        1L,
                        valid.selectionBoundary()
                    );
                TargetFreeRepresentationCandidateFreeze
                    .requireEnumerationInvocationContract(wrongSeed);
            }
        );
    }

    @Test
    void retainedFreezeIsCanonicalByteStableAndTamperEvident(
        @TempDir Path temporary
    ) throws Exception {
        var first = TargetFreeRepresentationCandidateFreeze.write(
            temporary,
            REPOSITORY_REVISION
        );
        byte[] firstBytes = Files.readAllBytes(temporary.resolve(
            TargetFreeRepresentationCandidateFreeze.FILE_NAME));
        var second = TargetFreeRepresentationCandidateFreeze.write(
            temporary,
            REPOSITORY_REVISION
        );
        byte[] secondBytes = Files.readAllBytes(temporary.resolve(
            TargetFreeRepresentationCandidateFreeze.FILE_NAME));

        assertEquals(first, second);
        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(
            first,
            TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(new String(
                    firstBytes,
                    StandardCharsets.UTF_8
                ))
        );
        assertFalse(new String(
            firstBytes,
            StandardCharsets.UTF_8
        ).contains("\r\n"));
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(first.toCanonicalJson() + "\n")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(first.toCanonicalJson().replaceFirst(
                    "\"status\":\"EXECUTED\"",
                    "\"status\":\"REMAINING\""
                ))
        );
    }
}
