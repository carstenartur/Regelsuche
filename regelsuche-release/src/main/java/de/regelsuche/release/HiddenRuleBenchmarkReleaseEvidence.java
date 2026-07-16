package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Factual release-profile adapter over the retained hidden-rule benchmark report. */
public record HiddenRuleBenchmarkReleaseEvidence(
    String schema,
    String sourceSchema,
    String sourceReportHash,
    int cases,
    int families,
    int frozenCandidates,
    int materialAblations,
    int acceptedCases,
    int rediscoveredCases,
    int configuredNegativeHoldouts,
    int executedNegativeHoldouts,
    int skippedNegativeHoldouts,
    int falsePositiveHoldouts,
    int generatedValidationExamples,
    int counterexampleSearches,
    int splitCollisionCount,
    int leakageViolationCount,
    int acceptedIncompleteHoldoutCount,
    int executableRediscoveryCount,
    boolean hiddenReferenceIsolated,
    boolean benchmarkComplete,
    boolean executableRediscoveryRetained,
    String evidenceHash
) {
    public static final String SCHEMA =
        "regelsuche.hidden-rule-release-evidence/v1";
    public static final String SOURCE_SCHEMA =
        "regelsuche.hidden-rule-benchmark/v2";

    public HiddenRuleBenchmarkReleaseEvidence {
        if (!SCHEMA.equals(schema) || !SOURCE_SCHEMA.equals(sourceSchema)) {
            throw new IllegalArgumentException(
                "unsupported hidden-rule release evidence schema");
        }
        requireSha256(sourceReportHash, "sourceReportHash");
        for (int value : new int[] {
                cases,
                families,
                frozenCandidates,
                materialAblations,
                acceptedCases,
                rediscoveredCases,
                configuredNegativeHoldouts,
                executedNegativeHoldouts,
                skippedNegativeHoldouts,
                falsePositiveHoldouts,
                generatedValidationExamples,
                counterexampleSearches,
                splitCollisionCount,
                leakageViolationCount,
                acceptedIncompleteHoldoutCount,
                executableRediscoveryCount}) {
            if (value < 0) {
                throw new IllegalArgumentException(
                    "hidden-rule release evidence counts must be non-negative");
            }
        }
        if (configuredNegativeHoldouts
                != executedNegativeHoldouts + skippedNegativeHoldouts) {
            throw new IllegalArgumentException(
                "hidden-rule negative holdouts must balance configured = executed + skipped");
        }
        if (acceptedCases > cases
                || rediscoveredCases > cases
                || frozenCandidates > cases
                || executableRediscoveryCount > acceptedCases) {
            throw new IllegalArgumentException(
                "hidden-rule evidence counts exceed the retained benchmark cases");
        }
        String expectedHash = canonicalHash(
            schema,
            sourceSchema,
            sourceReportHash,
            cases,
            families,
            frozenCandidates,
            materialAblations,
            acceptedCases,
            rediscoveredCases,
            configuredNegativeHoldouts,
            executedNegativeHoldouts,
            skippedNegativeHoldouts,
            falsePositiveHoldouts,
            generatedValidationExamples,
            counterexampleSearches,
            splitCollisionCount,
            leakageViolationCount,
            acceptedIncompleteHoldoutCount,
            executableRediscoveryCount,
            hiddenReferenceIsolated,
            benchmarkComplete,
            executableRediscoveryRetained);
        requireSha256(evidenceHash, "evidenceHash");
        if (!expectedHash.equals(evidenceHash)) {
            throw new IllegalArgumentException(
                "hidden-rule release evidence hash does not match canonical fields");
        }
    }

    public static HiddenRuleBenchmarkReleaseEvidence read(Path reportPath) {
        Objects.requireNonNull(reportPath, "reportPath");
        try {
            String raw = Files.readString(reportPath, StandardCharsets.UTF_8);
            JsonNode root = new ObjectMapper().readTree(raw);
            String sourceSchema = text(root, "schema");
            if (!SOURCE_SCHEMA.equals(sourceSchema)) {
                throw new IllegalArgumentException(
                    "unexpected hidden-rule benchmark schema: " + sourceSchema);
            }
            JsonNode summary = requiredObject(root, "summary");
            JsonNode caseNodes = requiredArray(root, "cases");

            int cases = integer(summary, "cases");
            int families = integer(summary, "families");
            int frozenCandidates = integer(summary, "frozenCandidates");
            int materialAblations = integer(summary, "materialAblations");
            int acceptedCases = integer(summary, "acceptedCases");
            int rediscoveredCases = integer(summary, "rediscoveredCases");
            int configuredNegatives = integer(summary, "negativeHoldouts");
            int executedNegatives = integer(summary, "evaluatedNegativeHoldouts");
            int skippedNegatives = integer(summary, "skippedNegativeHoldouts");
            int falsePositives = integer(summary, "falsePositiveHoldouts");
            int validationExamples = integer(summary, "generatedValidationExamples");
            int counterexampleSearches = integer(summary, "counterexampleSearches");

            Set<String> familyNames = new HashSet<>();
            int derivedFrozen = 0;
            int derivedAccepted = 0;
            int derivedRediscovered = 0;
            int derivedConfiguredNegatives = 0;
            int derivedExecutedNegatives = 0;
            int derivedFalsePositives = 0;
            int splitCollisions = 0;
            int leakageViolations = 0;
            int acceptedIncomplete = 0;
            int executableRediscoveries = 0;

            for (JsonNode caseNode : caseNodes) {
                familyNames.add(text(caseNode, "family"));
                boolean frozen = bool(caseNode, "candidateFrozen");
                boolean accepted = bool(caseNode, "accepted");
                boolean splitPassed = bool(caseNode, "splitPassed");
                boolean holdoutsComplete = bool(caseNode, "holdoutsComplete");
                boolean holdoutsPassed = bool(caseNode, "holdoutsPassed");
                boolean validationPassed = bool(caseNode, "validationPassed");
                String relation = text(caseNode, "candidateRelation");
                JsonNode split = requiredObject(caseNode, "split");
                JsonNode splitNegatives = requiredArray(split, "negatives");
                JsonNode collisions = requiredArray(split, "collisions");
                JsonNode holdouts = requiredObject(caseNode, "holdouts");
                JsonNode executedNegativeCases = requiredArray(holdouts, "negatives");
                JsonNode leakage = requiredArray(caseNode, "leakageViolations");
                JsonNode candidate = requiredObject(caseNode, "candidate");

                derivedConfiguredNegatives += splitNegatives.size();
                derivedExecutedNegatives += executedNegativeCases.size();
                splitCollisions += collisions.size();
                leakageViolations += leakage.size();
                if (frozen) {
                    derivedFrozen++;
                }
                if (!"NONE".equals(relation) && !"DIFFERENT".equals(relation)) {
                    derivedRediscovered++;
                }
                for (JsonNode negative : executedNegativeCases) {
                    if (!bool(negative, "noApplication")) {
                        derivedFalsePositives++;
                    }
                }
                if (accepted) {
                    derivedAccepted++;
                    if (!splitPassed || !holdoutsComplete || !holdoutsPassed
                            || !validationPassed
                            || splitNegatives.size() != executedNegativeCases.size()) {
                        acceptedIncomplete++;
                    }
                    if (frozen
                            && bool(candidate, "present")
                            && nonBlank(candidate.path("dynamicRuleId").asText(""))
                            && !"NONE".equals(relation)
                            && !"DIFFERENT".equals(relation)
                            && splitPassed
                            && holdoutsComplete
                            && holdoutsPassed
                            && validationPassed) {
                        executableRediscoveries++;
                    }
                }
            }

            requireEqual(cases, caseNodes.size(), "cases");
            requireEqual(families, familyNames.size(), "families");
            requireEqual(frozenCandidates, derivedFrozen, "frozenCandidates");
            requireEqual(acceptedCases, derivedAccepted, "acceptedCases");
            requireEqual(rediscoveredCases, derivedRediscovered, "rediscoveredCases");
            requireEqual(configuredNegatives, derivedConfiguredNegatives,
                "negativeHoldouts");
            requireEqual(executedNegatives, derivedExecutedNegatives,
                "evaluatedNegativeHoldouts");
            requireEqual(skippedNegatives,
                derivedConfiguredNegatives - derivedExecutedNegatives,
                "skippedNegativeHoldouts");
            requireEqual(falsePositives, derivedFalsePositives,
                "falsePositiveHoldouts");

            boolean isolated = splitCollisions == 0
                && leakageViolations == 0
                && !raw.toLowerCase(Locale.ROOT).contains("hidden_");
            boolean complete = cases >= 20
                && families >= 3
                && acceptedCases >= 1
                && validationExamples > 0
                && counterexampleSearches >= acceptedCases
                && acceptedIncomplete == 0
                && falsePositives == 0
                && configuredNegatives
                    == executedNegatives + skippedNegatives;
            boolean executable = executableRediscoveries >= 1;
            String reportHash = AutonomousResearchBriefV2.hash(raw);
            String evidenceHash = canonicalHash(
                SCHEMA,
                sourceSchema,
                reportHash,
                cases,
                families,
                frozenCandidates,
                materialAblations,
                acceptedCases,
                rediscoveredCases,
                configuredNegatives,
                executedNegatives,
                skippedNegatives,
                falsePositives,
                validationExamples,
                counterexampleSearches,
                splitCollisions,
                leakageViolations,
                acceptedIncomplete,
                executableRediscoveries,
                isolated,
                complete,
                executable);
            return new HiddenRuleBenchmarkReleaseEvidence(
                SCHEMA,
                sourceSchema,
                reportHash,
                cases,
                families,
                frozenCandidates,
                materialAblations,
                acceptedCases,
                rediscoveredCases,
                configuredNegatives,
                executedNegatives,
                skippedNegatives,
                falsePositives,
                validationExamples,
                counterexampleSearches,
                splitCollisions,
                leakageViolations,
                acceptedIncomplete,
                executableRediscoveries,
                isolated,
                complete,
                executable,
                evidenceHash);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not read hidden-rule benchmark evidence", exception);
        }
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("sourceSchema", sourceSchema)
            .property("sourceReportHash", sourceReportHash)
            .property("cases", cases)
            .property("families", families)
            .property("frozenCandidates", frozenCandidates)
            .property("materialAblations", materialAblations)
            .property("acceptedCases", acceptedCases)
            .property("rediscoveredCases", rediscoveredCases)
            .property("configuredNegativeHoldouts", configuredNegativeHoldouts)
            .property("executedNegativeHoldouts", executedNegativeHoldouts)
            .property("skippedNegativeHoldouts", skippedNegativeHoldouts)
            .property("falsePositiveHoldouts", falsePositiveHoldouts)
            .property("generatedValidationExamples", generatedValidationExamples)
            .property("counterexampleSearches", counterexampleSearches)
            .property("splitCollisionCount", splitCollisionCount)
            .property("leakageViolationCount", leakageViolationCount)
            .property("acceptedIncompleteHoldoutCount",
                acceptedIncompleteHoldoutCount)
            .property("executableRediscoveryCount", executableRediscoveryCount)
            .property("hiddenReferenceIsolated", hiddenReferenceIsolated)
            .property("benchmarkComplete", benchmarkComplete)
            .property("executableRediscoveryRetained",
                executableRediscoveryRetained)
            .property("evidenceHash", evidenceHash)
            .endObject()
            .toString();
    }

    private static String canonicalHash(
        String schema,
        String sourceSchema,
        String sourceReportHash,
        int cases,
        int families,
        int frozenCandidates,
        int materialAblations,
        int acceptedCases,
        int rediscoveredCases,
        int configuredNegativeHoldouts,
        int executedNegativeHoldouts,
        int skippedNegativeHoldouts,
        int falsePositiveHoldouts,
        int generatedValidationExamples,
        int counterexampleSearches,
        int splitCollisionCount,
        int leakageViolationCount,
        int acceptedIncompleteHoldoutCount,
        int executableRediscoveryCount,
        boolean hiddenReferenceIsolated,
        boolean benchmarkComplete,
        boolean executableRediscoveryRetained
    ) {
        return AutonomousResearchBriefV2.hash(
            schema
                + "\nsourceSchema=" + sourceSchema
                + "\nsourceReportHash=" + sourceReportHash
                + "\ncases=" + cases
                + "\nfamilies=" + families
                + "\nfrozenCandidates=" + frozenCandidates
                + "\nmaterialAblations=" + materialAblations
                + "\nacceptedCases=" + acceptedCases
                + "\nrediscoveredCases=" + rediscoveredCases
                + "\nconfiguredNegativeHoldouts=" + configuredNegativeHoldouts
                + "\nexecutedNegativeHoldouts=" + executedNegativeHoldouts
                + "\nskippedNegativeHoldouts=" + skippedNegativeHoldouts
                + "\nfalsePositiveHoldouts=" + falsePositiveHoldouts
                + "\ngeneratedValidationExamples=" + generatedValidationExamples
                + "\ncounterexampleSearches=" + counterexampleSearches
                + "\nsplitCollisionCount=" + splitCollisionCount
                + "\nleakageViolationCount=" + leakageViolationCount
                + "\nacceptedIncompleteHoldoutCount="
                    + acceptedIncompleteHoldoutCount
                + "\nexecutableRediscoveryCount=" + executableRediscoveryCount
                + "\nhiddenReferenceIsolated=" + hiddenReferenceIsolated
                + "\nbenchmarkComplete=" + benchmarkComplete
                + "\nexecutableRediscoveryRetained="
                    + executableRediscoveryRetained);
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!nonBlank(value)) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException(
                field + " must be a non-negative integer");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireEqual(int declared, int derived, String name) {
        if (declared != derived) {
            throw new IllegalArgumentException(
                name + " does not match retained case evidence: "
                    + declared + " != " + derived);
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
