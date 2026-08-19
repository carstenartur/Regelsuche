package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeRepresentationEvaluationPlanTest {
    private static final ObjectMapper JSON = new ObjectMapper();
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

        assertEquals(
            CASE_IDS,
            content.cases().stream()
                .map(TargetFreeRepresentationEvaluationPlan
                    .CaseDefinition::id)
                .toList()
        );
        assertEquals(
            POLICY_IDS,
            content.policies().stream()
                .map(TargetFreeRepresentationEvaluationPlan
                    .PolicyDefinition::id)
                .toList()
        );
        List<String> expectedMatrix = CASE_IDS.stream()
            .flatMap(caseId -> POLICY_IDS.stream()
                .map(policyId -> caseId + "/" + policyId))
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
        assertPolicyConstructionContracts(content.policies());
        assertTrue(content.cases().stream().allMatch(benchmarkCase ->
            benchmarkCase.ruleProfile()
                == de.regelsuche.knowledge.RuleProfile.MINIMAL_KERNEL));
        assertEquals(
            Set.of(
                "core-polynomial-division",
                "core-term-collection",
                "sympy-rational",
                "sympy-trigonometry"
            ),
            content.cases().stream()
                .flatMap(benchmarkCase -> benchmarkCase
                    .enabledRulePackIds().stream())
                .collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(content.policies().stream().allMatch(policy ->
            policy.initialAssumptionPolicy()
                == TargetFreeRepresentationEvaluationPlan
                    .InitialAssumptionPolicy
                    .UNION_WITH_RETAINED_STATE_ASSUMPTIONS));

        String canonical = plan.toCanonicalJson();
        assertFalse(canonical.contains("\"referenceExpressions\""));
        assertFalse(canonical.contains("\"requiredCapabilities\""));
        assertFalse(canonical.contains("\"acceptedCandidateTypes\""));
        assertFalse(canonical.contains(
            "y * (sin(x)^2 + cos(x)^2)"));
        assertFalse(canonical.contains(
            "rule:sympy.rational.partial_fraction.telescoping"));
        assertFalse(canonical.contains("2 * x"));
        assertFalse(canonical.contains(
            "rule:sympy.trig.pythagorean"));
    }

    @Test
    void preregistrationBindsBothResourcesButGenerationReadsOnlyFormation()
            throws Exception {
        byte[] preregistrationBytes =
            TargetFreeRepresentationEvaluationPlan.readResource(
                TargetFreeRepresentationEvaluationPlan
                    .PREREGISTRATION_RESOURCE
            );
        assertEquals(
            TargetFreeRepresentationEvaluationPlan
                .PREREGISTRATION_BYTE_LENGTH,
            preregistrationBytes.length
        );
        assertEquals(
            TargetFreeRepresentationEvaluationPlan.PREREGISTRATION_SHA256,
            TargetFreeRepresentationEvaluationPlan.sha256(
                preregistrationBytes)
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
            "y * (sin(x)^2 + cos(x)^2)",
            "rule:sympy.rational.partial_fraction.telescoping"
        )) {
            assertTrue(qualificationText.contains(forbidden));
            assertFalse(generated.contains(forbidden));
        }
    }

    @Test
    void assumptionSensitiveControlCanFormItsQualifiedCandidate() {
        var benchmarkCase = TargetFreeRepresentationEvaluationPlan.create(
            REPOSITORY_REVISION).content().cases().stream()
            .filter(value -> value.id().equals(
                "assumption-sensitive-cancellation-control"))
            .findFirst()
            .orElseThrow();
        var boundary = boundary(benchmarkCase);
        var result = new TargetFreeRepresentationSearch().search(
            benchmarkCase.sourceExpression(),
            boundary.candidateFormationRules(),
            budget(benchmarkCase.budget())
        );

        var candidate = result.content().candidateStates().stream()
            .filter(state -> state.expression().equals("1"))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("x != 0"), candidate.assumptions());
    }

    @Test
    void occurrenceLocalBridgeIsActuallyExposedAfterFreeze() {
        var benchmarkCase = TargetFreeRepresentationEvaluationPlan.create(
            REPOSITORY_REVISION).content().cases().stream()
            .filter(value -> value.id().equals(
                "occurrence-local-trigonometric-bridge"))
            .findFirst()
            .orElseThrow();
        var boundary = boundary(benchmarkCase);
        var result = new TargetFreeRepresentationSearch().search(
            benchmarkCase.sourceExpression(),
            boundary.candidateFormationRules(),
            budget(benchmarkCase.budget())
        );
        var disclosure = boundary.disclosePostFreeze(
            boundary.freezeCandidates(List.of()));
        KnownStructureMatcher matcher = new KnownStructureMatcher(
            disclosure.classificationCatalog());

        assertTrue(result.content().candidateStates().stream()
            .flatMap(state -> matcher.match(state.expression()).stream())
            .anyMatch(match -> !match.wholeExpression()
                && match.consequenceIds().contains(
                    "rule:sympy.trig.pythagorean")));
    }

    @Test
    void telescopingKnowledgeIsWithheldUntilCandidateFreeze() {
        var benchmarkCase = TargetFreeRepresentationEvaluationPlan.create(
            REPOSITORY_REVISION).content().cases().stream()
            .filter(value -> value.id().equals(
                "telescoping-capability-bridge"))
            .findFirst()
            .orElseThrow();
        var boundary = boundary(benchmarkCase);

        assertFalse(boundary.candidateFormationRules().stream()
            .anyMatch(rule -> rule.id().equals(
                "sympy.rational.partial_fraction.telescoping")));
        var result = new TargetFreeRepresentationSearch().search(
            benchmarkCase.sourceExpression(),
            boundary.candidateFormationRules(),
            budget(benchmarkCase.budget())
        );
        assertTrue(result.content().candidateStates().stream()
            .anyMatch(state -> state.expression().equals(
                "1 / (n * (n + 1))")));
        var disclosure = boundary.disclosePostFreeze(
            boundary.freezeCandidates(List.of()));
        assertTrue(disclosure.classificationCatalog().structures().stream()
            .anyMatch(structure -> structure.consequenceIds().contains(
                "rule:sympy.rational.partial_fraction.telescoping")));
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

    private static RepresentationDiscoveryInformationBoundary boundary(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase
    ) {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            benchmarkCase.ruleProfile());
        for (String packId : benchmarkCase.enabledRulePackIds()) {
            selection = selection.enablePack(packId);
        }
        return RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
            new KnowledgePackRegistry(),
            benchmarkCase.informationTrack(),
            selection,
            Set.of()
        );
    }

    private static TargetFreeRepresentationSearch.Budget budget(
        TargetFreeRepresentationEvaluationPlan.WorkBudget budget
    ) {
        return new TargetFreeRepresentationSearch.Budget(
            budget.maxDepth(),
            budget.maxExploredStates(),
            budget.maxRetainedStates(),
            budget.maxGeneratedTransitions(),
            budget.maxCandidatesPerState(),
            budget.maxAstSizeIncreasePerStep()
        );
    }

    private static void assertPolicyConstructionContracts(
        List<TargetFreeRepresentationEvaluationPlan.PolicyDefinition> policies
    ) {
        Map<String, TargetFreeRepresentationEvaluationPlan.PolicyDefinition>
            byId = policies.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    TargetFreeRepresentationEvaluationPlan
                        .PolicyDefinition::id,
                    policy -> policy
                )
            );
        var bounded = byId.get("BOUNDED_ENUMERATION_V1");
        assertEquals(
            TargetFreeRepresentationEvaluationPlan.AdapterInterface
                .TARGET_FREE_REPRESENTATION_SEARCH,
            bounded.adapterInterface()
        );
        var random = byId.get("RANDOM_MONTE_CARLO_V1");
        assertEquals(
            TargetFreeRepresentationEvaluationPlan.AdapterConstructor
                .LONG_SEED,
            random.adapterConstructor()
        );
        assertEquals(0L, random.deterministicSeed());
        assertTrue(policies.stream().allMatch(policy ->
            policy.initialAssumptionPolicy()
                == TargetFreeRepresentationEvaluationPlan
                    .InitialAssumptionPolicy
                    .UNION_WITH_RETAINED_STATE_ASSUMPTIONS));
        assertTrue(byId.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(
                "BOUNDED_ENUMERATION_V1"))
            .allMatch(entry -> entry.getValue().adapterInterface()
                == TargetFreeRepresentationEvaluationPlan
                    .AdapterInterface.SEARCH_STRATEGY));
        assertTrue(byId.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(
                "RANDOM_MONTE_CARLO_V1"))
            .allMatch(entry -> entry.getValue().adapterConstructor()
                == TargetFreeRepresentationEvaluationPlan
                    .AdapterConstructor.NO_ARGUMENT
                && entry.getValue().deterministicSeed() == 0L));

        policies.forEach(policy -> assertDoesNotThrow(() -> {
            Class<?> adapter = Class.forName(policy.adapter());
            switch (policy.adapterInterface()) {
                case TARGET_FREE_REPRESENTATION_SEARCH -> assertEquals(
                    TargetFreeRepresentationSearch.class,
                    adapter
                );
                case SEARCH_STRATEGY -> assertTrue(
                    de.regelsuche.search.strategy.SearchStrategy.class
                        .isAssignableFrom(adapter)
                );
            }
            switch (policy.adapterConstructor()) {
                case NO_ARGUMENT -> adapter.getConstructor();
                case LONG_SEED -> adapter.getConstructor(long.class);
            }
        }));
    }
}
