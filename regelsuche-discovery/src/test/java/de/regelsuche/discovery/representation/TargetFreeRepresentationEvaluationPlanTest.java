package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeRepresentationEvaluationPlanTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void freezesTheExactTargetBlindMatrixWithoutQualificationLeakage() {
        var plan = TargetFreeRepresentationEvaluationPlan.create(
            REPOSITORY_REVISION);
        var content = plan.content();

        assertEquals(6, content.configuredCaseCount());
        assertEquals(4, content.configuredPolicyCount());
        assertEquals(24, content.configuredEntryCount());
        assertEquals(
            TargetFreeRepresentationEvaluationPlan
                .QUALIFICATION_DISCLOSURE,
            content.qualificationDisclosure()
        );
        assertEquals(
            List.of(new TargetFreeRepresentationEvaluationPlan.StatusCount(
                TargetFreeRepresentationEvaluationPlan.ENTRY_STATUS,
                24
            )),
            content.statusCounts()
        );
        assertEquals(
            IntStream.rangeClosed(1, 24).boxed().toList(),
            content.entries().stream()
                .map(TargetFreeRepresentationEvaluationPlan
                    .PlanEntry::sequence)
                .toList()
        );
        assertEquals(
            content.configuredEntryCount(),
            content.entries().stream()
                .map(TargetFreeRepresentationEvaluationPlan
                    .PlanEntry::configurationId)
                .distinct()
                .count()
        );

        List<String> expectedMatrix = content.cases().stream()
            .flatMap(benchmarkCase -> content.policies().stream()
                .map(policy -> benchmarkCase.id() + "/" + policy.id()))
            .toList();
        assertEquals(
            expectedMatrix,
            content.entries().stream()
                .map(entry -> entry.caseId() + "/" + entry.policyId())
                .toList()
        );
        assertTrue(content.entries().stream().allMatch(entry ->
            TargetFreeRepresentationEvaluationPlan.ENTRY_STATUS.equals(
                entry.status())
                && TargetFreeRepresentationEvaluationPlan.TERMINAL_REASON
                    .equals(entry.terminalReason())
        ));
        content.policies().forEach(policy ->
            assertDoesNotThrow(() -> Class.forName(policy.adapter())));

        String canonical = plan.toCanonicalJson();
        assertFalse(canonical.contains("\"referenceExpressions\""));
        assertFalse(canonical.contains("\"requiredCapabilities\""));
        assertFalse(canonical.contains("\"acceptedCandidateTypes\""));
        assertFalse(canonical.contains("(a + b)^2 + y"));
        assertFalse(canonical.contains("1 / n - 1 / (n + 1)"));
        assertFalse(canonical.contains("2 * x"));
        assertFalse(canonical.contains(
            "rule:sympy.trig.pythagorean"));
        assertEquals(
            Set.of(
                "BOUNDED_ENUMERATION_V1",
                "RANDOM_MONTE_CARLO_V1",
                "SCALAR_BEST_FIRST_V1",
                "STRUCTURAL_DIVERSITY_V1"
            ),
            content.policies().stream()
                .map(TargetFreeRepresentationEvaluationPlan
                    .PolicyDefinition::id)
                .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void preregistrationBindsBothResourcesButGenerationReadsOnlyFormation()
            throws Exception {
        byte[] preregistrationBytes =
            TargetFreeRepresentationEvaluationPlan.readResource(
                TargetFreeRepresentationEvaluationPlan
                    .PREREGISTRATION_RESOURCE
            );
        JsonNode preregistration = JSON.readTree(preregistrationBytes);
        byte[] formation = TargetFreeRepresentationEvaluationPlan
            .readResource(preregistration.path(
                "formationResource").asText());
        byte[] qualification = TargetFreeRepresentationEvaluationPlan
            .readResource(preregistration.path(
                "qualificationResource").asText());

        assertEquals(
            preregistration.path("formationByteLength").asLong(),
            formation.length
        );
        assertEquals(
            preregistration.path("formationSha256").asText(),
            TargetFreeRepresentationEvaluationPlan.sha256(formation)
        );
        assertEquals(
            preregistration.path("qualificationByteLength").asLong(),
            qualification.length
        );
        assertEquals(
            preregistration.path("qualificationSha256").asText(),
            TargetFreeRepresentationEvaluationPlan.sha256(qualification)
        );

        String generated =
            TargetFreeRepresentationEvaluationPlan.create(
                REPOSITORY_REVISION).toCanonicalJson();
        String qualificationText = new String(
            qualification, StandardCharsets.UTF_8);
        for (String forbidden : List.of(
            "\"referenceExpressions\"",
            "\"requiredCapabilities\"",
            "\"acceptedCandidateTypes\"",
            "(a + b)^2 + y",
            "1 / n - 1 / (n + 1)"
        )) {
            assertTrue(qualificationText.contains(forbidden));
            assertFalse(generated.contains(forbidden));
        }
    }

    @Test
    void retainedPlanIsByteStableCanonicalAndTamperEvident(
        @TempDir Path temporary
    ) throws Exception {
        var first = TargetFreeRepresentationEvaluationPlan.write(
            temporary,
            REPOSITORY_REVISION
        );
        byte[] firstBytes = Files.readAllBytes(temporary.resolve(
            TargetFreeRepresentationEvaluationPlan.FILE_NAME));
        var second = TargetFreeRepresentationEvaluationPlan.write(
            temporary,
            REPOSITORY_REVISION
        );
        byte[] secondBytes = Files.readAllBytes(temporary.resolve(
            TargetFreeRepresentationEvaluationPlan.FILE_NAME));

        assertEquals(first, second);
        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(
            first,
            TargetFreeRepresentationEvaluationPlan.EvaluationPlan
                .fromCanonicalJson(new String(
                    firstBytes, StandardCharsets.UTF_8))
        );
        assertFalse(new String(
            firstBytes, StandardCharsets.UTF_8).contains("\r\n"));

        String canonical = first.toCanonicalJson();
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationEvaluationPlan.EvaluationPlan
                .fromCanonicalJson(canonical + "\n")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationEvaluationPlan.EvaluationPlan
                .fromCanonicalJson(canonical.replaceFirst(
                    "\"status\":\"REMAINING\"",
                    "\"status\":\"EXECUTED\""
                ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeRepresentationEvaluationPlan
                .EvaluationPlan(
                    first.content(),
                    "sha256:" + "0".repeat(64)
                )
        );
    }

    @Test
    void rejectsNonCommitRepositoryRevisions() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationEvaluationPlan.create("WORKTREE")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationEvaluationPlan.create(
                REPOSITORY_REVISION.toUpperCase())
        );
    }
}
