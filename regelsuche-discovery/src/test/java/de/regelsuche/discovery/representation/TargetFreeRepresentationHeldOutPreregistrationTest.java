package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track;
import de.regelsuche.knowledge.CoreRuleCatalog;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TargetFreeRepresentationHeldOutPreregistrationTest {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private static final ExpressionCanonicalizer CANONICALIZER =
        new ExpressionCanonicalizer();
    private static final String PREREGISTRATION_RESOURCE =
        "/de/regelsuche/discovery/representation/"
            + "target-free-held-out-preregistration-v1.json";
    private static final long PREREGISTRATION_BYTE_LENGTH = 1512L;
    private static final String PREREGISTRATION_SHA256 =
        "sha256:9d79fe60a2a2cf3fda9255cc3b26577b4ac23d61b8359cd22b3a7a0dcdb7bd29";
    private static final List<Integer> CHECKPOINTS =
        List.of(8, 16, 32, 64, 128, 256);
    private static final List<String> POLICY_IDS = List.of(
        "BOUNDED_ENUMERATION_V1",
        "RANDOM_MONTE_CARLO_V1",
        "SCALAR_BEST_FIRST_V1",
        "STRUCTURAL_DIVERSITY_V1");

    @Test
    void freezesTheMatrixWithoutQualificationLeakage() throws Exception {
        Resources resources = resources();
        JsonNode preregistration = resources.preregistration();
        JsonNode formation = resources.formation();
        JsonNode qualification = resources.qualification();
        assertEquals("regelsuche.target-free-held-out-preregistration/v1",
            preregistration.path("schema").asText());
        assertEquals("regelsuche.target-free-held-out-formation/v1",
            formation.path("schema").asText());
        assertEquals("regelsuche.target-free-held-out-qualification/v1",
            qualification.path("schema").asText());
        assertEquals("FROZEN_NOT_EXECUTED",
            preregistration.path("evidenceStatus").asText());
        assertEquals("FROZEN_NOT_EXECUTED",
            formation.path("evidenceStatus").asText());
        assertEquals("SEALED_POST_FREEZE",
            qualification.path("evidenceStatus").asText());

        Map<String, JsonNode> cases = byId(formation.path("cases"));
        Map<String, JsonNode> qualifications =
            byId(qualification.path("caseQualifications"));
        Map<String, JsonNode> policies = byId(formation.path("policies"));
        assertEquals(6, cases.size());
        assertEquals(cases.keySet(), qualifications.keySet());
        assertEquals(POLICY_IDS, List.copyOf(policies.keySet()));
        assertEquals(CHECKPOINTS, integers(
            formation.path("workMatching").path("checkpoints")));
        assertEquals(144,
            preregistration.path("configuredEntryCount").asInt());
        assertEquals(cases.size() * policies.size() * CHECKPOINTS.size(),
            preregistration.path("configuredEntryCount").asInt());

        int positives = 0;
        int negatives = 0;
        int valleys = 0;
        for (Map.Entry<String, JsonNode> entry : cases.entrySet()) {
            JsonNode benchmarkCase = entry.getValue();
            JsonNode rule = qualifications.get(entry.getKey());
            assertEquals("MINIMAL_KERNEL",
                benchmarkCase.path("ruleProfile").asText());
            List<String> distractors = strings(
                benchmarkCase.path("distractorRulePackIds"));
            assertFalse(distractors.isEmpty());
            assertTrue(strings(benchmarkCase.path("enabledRulePackIds"))
                .containsAll(distractors));
            assertAvailablePacks(benchmarkCase);
            if ("NO_POLICY_QUALIFIES".equals(
                    rule.path("expectedOutcome").asText())) {
                negatives++;
                assertEquals(0, rule.path("minimumQualifiedDepth").asInt());
                assertEquals(0, rule.path("maximumQualifiedDepth").asInt());
                assertTrue(strings(rule.path(
                    "oracleWitnessRequiredRuleIds")).isEmpty());
            } else {
                positives++;
                int minimum = rule.path("minimumQualifiedDepth").asInt();
                int maximum = rule.path("maximumQualifiedDepth").asInt();
                assertTrue(minimum >= 3 && minimum <= maximum
                    && maximum <= 10);
                assertFalse(strings(rule.path(
                    "oracleWitnessRequiredRuleIds")).isEmpty());
            }
            if (rule.path("requireTemporaryComplexityIncrease").asBoolean()) {
                valleys++;
            }
        }
        assertEquals(5, positives);
        assertEquals(1, negatives);
        assertTrue(valleys >= 2);

        String formationText = new String(
            resources.formationBytes(), StandardCharsets.UTF_8);
        for (JsonNode rule : qualification.path("caseQualifications")) {
            assertFalse(formationText.contains(
                rule.path("expectedOutcome").asText()));
            assertNoLeakage(formationText,
                rule.path("referenceExpressions"), "reference");
            assertNoLeakage(formationText,
                rule.path("requiredCapabilities"), "capability");
            assertNoLeakage(formationText,
                rule.path("oracleWitnessRequiredRuleIds"), "witness rule");
        }
    }

    @Test
    void positiveCasesHaveNoDirectEdgeAndHaveBoundedWitnesses()
            throws Exception {
        Resources resources = resources();
        Map<String, JsonNode> qualifications = byId(
            resources.qualification().path("caseQualifications"));
        for (JsonNode benchmarkCase : resources.formation().path("cases")) {
            JsonNode rule = Objects.requireNonNull(
                qualifications.get(benchmarkCase.path("id").asText()));
            BoundaryEngine boundaryEngine = boundaryEngine(benchmarkCase);
            Set<String> availableRuleIds = boundaryEngine.boundary()
                .candidateFormationRules().stream()
                .map(value -> value.id())
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(availableRuleIds.containsAll(strings(
                rule.path("oracleWitnessRequiredRuleIds"))));
            Set<String> references = strings(rule.path("referenceExpressions"))
                .stream().map(CANONICALIZER::canonicalize)
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(boundaryEngine.engine().transform(
                benchmarkCase.path("sourceExpression").asText()).stream()
                .noneMatch(transformation -> references.contains(
                    CANONICALIZER.canonicalize(
                        transformation.transformedExpression()))),
                () -> "direct primitive edge reaches a held-out reference for "
                    + benchmarkCase.path("id").asText());
            if ("NO_POLICY_QUALIFIES".equals(
                    rule.path("expectedOutcome").asText())) {
                continue;
            }
            Witness witness = shortestWitness(
                benchmarkCase, rule, boundaryEngine.engine(), references);
            assertNotNull(witness,
                () -> "no bounded witness found for "
                    + benchmarkCase.path("id").asText());
            int minimum = rule.path("minimumQualifiedDepth").asInt();
            int maximum = rule.path("maximumQualifiedDepth").asInt();
            assertTrue(witness.depth() >= minimum
                && witness.depth() <= maximum,
                () -> "witness depth " + witness.depth()
                    + " outside " + minimum + ".." + maximum
                    + " for " + benchmarkCase.path("id").asText()
                    + "; path=" + witness.ruleIds());
            if (rule.path("requireTemporaryComplexityIncrease").asBoolean()) {
                assertTrue(witness.temporaryComplexityIncrease(),
                    () -> "required complexity valley missing for "
                        + benchmarkCase.path("id").asText());
            }
        }
    }

    @Test
    void matchedWorkComparisonIsFailClosed() throws Exception {
        JsonNode work = resources().formation().path("workMatching");
        assertEquals("ADMITTED_PRIMITIVE_STEPS",
            work.path("authority").asText());
        assertEquals("ALL_POLICIES_REACHED_EXACT_CHECKPOINT",
            work.path("comparisonEligibility").asText());
        assertEquals("EXHAUSTED_BEFORE_CHECKPOINT_NOT_COMPARABLE",
            work.path("earlyExhaustionStatus").asText());
        assertEquals("STOP_BEFORE_ADMITTING_A_STEP_BEYOND_THE_CHECKPOINT",
            work.path("stopSemantics").asText());
        assertEquals("RECORDED_SECONDARY_METRIC",
            work.path("engineCalls").asText());
        assertEquals("RECORDED_SECONDARY_METRIC",
            work.path("generatedTransitions").asText());
        assertEquals("RECORDED_DIAGNOSTIC_ONLY",
            work.path("wallClock").asText());
    }

    private static Resources resources() throws Exception {
        byte[] preregistrationBytes =
            TargetFreeRepresentationEvaluationPlan.readResource(
                PREREGISTRATION_RESOURCE);
        assertEquals(PREREGISTRATION_BYTE_LENGTH,
            preregistrationBytes.length);
        assertEquals(PREREGISTRATION_SHA256,
            TargetFreeRepresentationEvaluationPlan.sha256(
                preregistrationBytes));
        JsonNode preregistration = JSON.readTree(preregistrationBytes);
        byte[] formationBytes =
            TargetFreeRepresentationEvaluationPlan.readResource(
                preregistration.path("formationResource").asText());
        byte[] qualificationBytes =
            TargetFreeRepresentationEvaluationPlan.readResource(
                preregistration.path("qualificationResource").asText());
        assertEquals(preregistration.path("formationByteLength").asLong(),
            formationBytes.length);
        assertEquals(preregistration.path("formationSha256").asText(),
            TargetFreeRepresentationEvaluationPlan.sha256(formationBytes));
        assertEquals(preregistration.path("qualificationByteLength").asLong(),
            qualificationBytes.length);
        assertEquals(preregistration.path("qualificationSha256").asText(),
            TargetFreeRepresentationEvaluationPlan.sha256(
                qualificationBytes));
        return new Resources(
            preregistration,
            JSON.readTree(formationBytes),
            JSON.readTree(qualificationBytes),
            formationBytes);
    }

    private static BoundaryEngine boundaryEngine(JsonNode benchmarkCase) {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            RuleProfile.valueOf(
                benchmarkCase.path("ruleProfile").asText()));
        for (String packId : strings(
                benchmarkCase.path("enabledRulePackIds"))) {
            selection = selection.enablePack(packId);
        }
        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                new KnowledgePackRegistry(),
                Track.valueOf(benchmarkCase.path(
                    "informationTrack").asText()),
                selection,
                Set.of());
        JsonNode budget = benchmarkCase.path("budget");
        return new BoundaryEngine(
            boundary,
            new AstRewriteTransformationEngine(
                boundary.candidateFormationRules(),
                budget.path("maxAstSizeIncreasePerStep").asInt(),
                budget.path("maxCandidatesPerState").asInt()));
    }

    private static Witness shortestWitness(
        JsonNode benchmarkCase,
        JsonNode qualification,
        AstRewriteTransformationEngine engine,
        Set<String> references
    ) {
        String source = benchmarkCase.path("sourceExpression").asText();
        int sourceSize = CANONICALIZER.astNodeCount(source);
        int maximumDepth = qualification.path(
            "maximumQualifiedDepth").asInt();
        int maximumStates = benchmarkCase.path("budget")
            .path("maxExploredStates").asInt();
        Set<String> requiredAssumptions = Set.copyOf(strings(
            qualification.path("requiredAssumptions")));
        TreeSet<String> initialAssumptions = new TreeSet<>(
            strings(benchmarkCase.path("assumptions")));
        ArrayDeque<SearchState> queue = new ArrayDeque<>();
        queue.add(new SearchState(
            source, List.copyOf(initialAssumptions), 0, false, List.of()));
        Set<String> seen = new HashSet<>();
        seen.add(stateKey(source, initialAssumptions, false));
        int explored = 0;
        while (!queue.isEmpty() && explored++ < maximumStates) {
            SearchState state = queue.removeFirst();
            if (state.depth() >= maximumDepth) {
                continue;
            }
            List<Transformation> transformations = new ArrayList<>(
                engine.transform(state.expression()));
            transformations.sort(Comparator
                .comparing(Transformation::rule)
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(value ->
                    String.join("\u0000", value.assumptions())));
            for (Transformation transformation : transformations) {
                TreeSet<String> assumptions = new TreeSet<>(
                    state.assumptions());
                assumptions.addAll(transformation.assumptions());
                String expression = transformation.transformedExpression();
                boolean complexityIncrease =
                    state.temporaryComplexityIncrease()
                        || CANONICALIZER.astNodeCount(expression) > sourceSize;
                List<String> path = new ArrayList<>(state.ruleIds());
                path.addAll(transformation.primitiveRuleIds());
                int depth = state.depth()
                    + transformation.primitiveStepCount();
                if (depth <= maximumDepth
                        && references.contains(
                            CANONICALIZER.canonicalize(expression))
                        && assumptions.containsAll(requiredAssumptions)) {
                    return new Witness(
                        depth, complexityIncrease, List.copyOf(path));
                }
                if (depth < maximumDepth && seen.add(stateKey(
                        expression, assumptions, complexityIncrease))) {
                    queue.addLast(new SearchState(
                        expression,
                        List.copyOf(assumptions),
                        depth,
                        complexityIncrease,
                        List.copyOf(path)));
                }
            }
        }
        return null;
    }

    private static String stateKey(
        String expression,
        Set<String> assumptions,
        boolean complexityIncrease
    ) {
        return CANONICALIZER.stableHash(expression)
            + "|" + String.join("\u0000", assumptions)
            + "|" + complexityIncrease;
    }

    private static void assertAvailablePacks(JsonNode benchmarkCase) {
        Set<String> available = new HashSet<>(CoreRuleCatalog.packIds());
        new KnowledgePackRegistry().allPacks().forEach(pack ->
            available.add(pack.packId()));
        for (String packId : strings(
                benchmarkCase.path("enabledRulePackIds"))) {
            assertTrue(available.contains(packId),
                () -> "unknown rule pack " + packId
                    + " for " + benchmarkCase.path("id").asText());
        }
    }

    private static void assertNoLeakage(
        String formationText,
        JsonNode values,
        String label
    ) {
        for (String value : strings(values)) {
            assertFalse(formationText.contains(value),
                () -> label + " leaked into formation: " + value);
        }
    }

    private static Map<String, JsonNode> byId(JsonNode values) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String id = value.path("id").asText();
            assertFalse(id.isBlank());
            assertEquals(null, result.put(id, value),
                () -> "duplicate id " + id);
        }
        return result;
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static List<Integer> integers(JsonNode values) {
        List<Integer> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asInt()));
        return List.copyOf(result);
    }

    private record Resources(
        JsonNode preregistration,
        JsonNode formation,
        JsonNode qualification,
        byte[] formationBytes
    ) {
        private Resources {
            formationBytes = formationBytes.clone();
        }

        @Override
        public byte[] formationBytes() {
            return formationBytes.clone();
        }
    }

    private record BoundaryEngine(
        RepresentationDiscoveryInformationBoundary boundary,
        AstRewriteTransformationEngine engine
    ) {
    }

    private record SearchState(
        String expression,
        List<String> assumptions,
        int depth,
        boolean temporaryComplexityIncrease,
        List<String> ruleIds
    ) {
    }

    private record Witness(
        int depth,
        boolean temporaryComplexityIncrease,
        List<String> ruleIds
    ) {
    }
}
