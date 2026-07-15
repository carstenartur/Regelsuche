package de.regelsuche.experiments.autopilot;

import de.regelsuche.json.JsonWriter;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned target-free research brief for aggregate Autopilot campaigns.
 *
 * <p>v2 binds the immutable v1 brief identity while adding explicit project
 * novelty and lifecycle-handoff stages. It does not mutate v1 enums or hashes.</p>
 */
public record AutonomousResearchBriefV2(
    String schema,
    String briefId,
    String v1BriefHash,
    long deterministicSeed,
    Set<EvidenceStage> enabledStages,
    Map<EvidenceStage, StageBudget> stageBudgets,
    int minimumAggregateInputs,
    int minimumSupportDiversity,
    int minimumAlphaDiversity,
    String outputNamespace,
    String promotionStatus,
    String publicEvidenceStatus,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.autonomous-research-brief/v2";

    public AutonomousResearchBriefV2 {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported Autopilot v2 brief schema");
        }
        requireText(briefId, "briefId");
        requireSha256(v1BriefHash, "v1BriefHash");
        enabledStages = immutableStages(enabledStages);
        stageBudgets = immutableBudgets(stageBudgets);
        if (!enabledStages.contains(EvidenceStage.GENERATION)
                || !enabledStages.contains(EvidenceStage.CANDIDATE_FORMATION)) {
            throw new IllegalArgumentException(
                "generation and candidate formation must be enabled");
        }
        if (!enabledStages.contains(EvidenceStage.PROJECT_NOVELTY)
                || !enabledStages.contains(EvidenceStage.LIFECYCLE_HANDOFF)) {
            throw new IllegalArgumentException(
                "v2 requires explicit project novelty and lifecycle handoff stages");
        }
        validateBudgets(enabledStages, stageBudgets);
        if (minimumAggregateInputs < 2
                || minimumSupportDiversity < 2
                || minimumAlphaDiversity < 2) {
            throw new IllegalArgumentException(
                "aggregate input, support and alpha diversity minima must be at least two");
        }
        requireText(outputNamespace, "outputNamespace");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
        requireSha256(contentHash, "contentHash");
    }

    public static AutonomousResearchBriefV2 create(
        String briefId,
        AutonomousResearchBrief v1Brief,
        Set<EvidenceStage> enabledStages,
        Map<EvidenceStage, StageBudget> stageBudgets,
        int minimumAggregateInputs,
        int minimumSupportDiversity,
        int minimumAlphaDiversity,
        String outputNamespace
    ) {
        Objects.requireNonNull(v1Brief, "v1Brief");
        Set<EvidenceStage> orderedStages = immutableStages(enabledStages);
        Map<EvidenceStage, StageBudget> orderedBudgets = immutableBudgets(stageBudgets);
        String material = canonicalMaterial(
            briefId,
            v1Brief.contentHash(),
            v1Brief.deterministicSeed(),
            orderedStages,
            orderedBudgets,
            minimumAggregateInputs,
            minimumSupportDiversity,
            minimumAlphaDiversity,
            outputNamespace);
        return new AutonomousResearchBriefV2(
            SCHEMA,
            briefId,
            v1Brief.contentHash(),
            v1Brief.deterministicSeed(),
            orderedStages,
            orderedBudgets,
            minimumAggregateInputs,
            minimumSupportDiversity,
            minimumAlphaDiversity,
            outputNamespace,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            AutonomousResearchBrief.hash(material));
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("briefId", briefId)
            .property("v1BriefHash", v1BriefHash)
            .property("deterministicSeed", deterministicSeed)
            .stringArray("enabledStages", enabledStages.stream()
                .map(Enum::name).sorted().toList())
            .array("stageBudgets", array -> stageBudgets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> array.objectValue(object -> object
                    .property("stage", entry.getKey().name())
                    .array("resources", resources -> entry.getValue().resources()
                        .entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(resource -> resources.objectValue(item -> item
                            .property("resource", resource.getKey().name())
                            .property("configured", resource.getValue())))))))
            .property("minimumAggregateInputs", minimumAggregateInputs)
            .property("minimumSupportDiversity", minimumSupportDiversity)
            .property("minimumAlphaDiversity", minimumAlphaDiversity)
            .property("outputNamespace", outputNamespace)
            .property("promotionStatus", promotionStatus)
            .property("publicEvidenceStatus", publicEvidenceStatus)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static String canonicalMaterial(
        String briefId,
        String v1BriefHash,
        long deterministicSeed,
        Set<EvidenceStage> stages,
        Map<EvidenceStage, StageBudget> budgets,
        int minimumAggregateInputs,
        int minimumSupportDiversity,
        int minimumAlphaDiversity,
        String outputNamespace
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nbriefId=").append(briefId)
            .append("\nv1BriefHash=").append(v1BriefHash)
            .append("\nseed=").append(deterministicSeed)
            .append("\nstages=").append(stages.stream()
                .map(Enum::name).sorted().toList())
            .append("\nminimumAggregateInputs=").append(minimumAggregateInputs)
            .append("\nminimumSupportDiversity=").append(minimumSupportDiversity)
            .append("\nminimumAlphaDiversity=").append(minimumAlphaDiversity)
            .append("\noutputNamespace=").append(outputNamespace);
        budgets.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> material.append("\nbudget=")
                .append(entry.getKey().name()).append('|')
                .append(entry.getValue().canonicalMaterial()));
        return material.toString();
    }

    public enum EvidenceStage {
        GENERATION,
        CANDIDATE_FORMATION,
        VALIDATION,
        COUNTEREXAMPLE_SEARCH,
        PROJECT_NOVELTY,
        PROOF,
        LIFECYCLE_HANDOFF
    }

    public enum ResourceKind {
        WALL_CLOCK_MILLIS,
        GENERATED_STATES,
        EXPLORED_STATES,
        CANDIDATES,
        VALIDATION_CHECKS,
        COUNTEREXAMPLE_ATTEMPTS,
        NOVELTY_CHECKS,
        PROOF_ATTEMPTS,
        LIFECYCLE_HANDOFFS
    }

    public record StageBudget(Map<ResourceKind, Long> resources) {
        public StageBudget {
            Objects.requireNonNull(resources, "resources");
            EnumMap<ResourceKind, Long> ordered = new EnumMap<>(ResourceKind.class);
            resources.forEach((resource, amount) -> {
                Objects.requireNonNull(resource, "resource");
                if (amount == null || amount < 0L) {
                    throw new IllegalArgumentException(
                        "configured resource amounts must be non-negative");
                }
                if (amount > 0L) {
                    ordered.put(resource, amount);
                }
            });
            resources = Collections.unmodifiableMap(ordered);
        }

        boolean hasWork() {
            return resources.values().stream().anyMatch(amount -> amount > 0L);
        }

        String canonicalMaterial() {
            return resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name() + '=' + entry.getValue())
                .toList()
                .toString();
        }
    }

    private static Set<EvidenceStage> immutableStages(Set<EvidenceStage> values) {
        Objects.requireNonNull(values, "enabledStages");
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "enabledStages must not be empty or contain null");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    private static Map<EvidenceStage, StageBudget> immutableBudgets(
        Map<EvidenceStage, StageBudget> values
    ) {
        Objects.requireNonNull(values, "stageBudgets");
        EnumMap<EvidenceStage, StageBudget> ordered =
            new EnumMap<>(EvidenceStage.class);
        values.forEach((stage, budget) -> ordered.put(
            Objects.requireNonNull(stage, "stage"),
            Objects.requireNonNull(budget, "stageBudget")));
        return Collections.unmodifiableMap(ordered);
    }

    private static void validateBudgets(
        Set<EvidenceStage> enabledStages,
        Map<EvidenceStage, StageBudget> budgets
    ) {
        for (EvidenceStage stage : enabledStages) {
            StageBudget budget = budgets.get(stage);
            if (budget == null || !budget.hasWork()) {
                throw new IllegalArgumentException(
                    "enabled v2 stage has no configured budget: " + stage);
            }
            validateResourcePlacement(stage, budget);
        }
        for (Map.Entry<EvidenceStage, StageBudget> entry : budgets.entrySet()) {
            if (!enabledStages.contains(entry.getKey()) && entry.getValue().hasWork()) {
                throw new IllegalArgumentException(
                    "disabled v2 stage has configured budget: " + entry.getKey());
            }
        }
    }

    private static void validateResourcePlacement(
        EvidenceStage stage,
        StageBudget budget
    ) {
        Set<ResourceKind> allowed = switch (stage) {
            case GENERATION -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.GENERATED_STATES,
                ResourceKind.EXPLORED_STATES);
            case CANDIDATE_FORMATION -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.CANDIDATES);
            case VALIDATION -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.VALIDATION_CHECKS);
            case COUNTEREXAMPLE_SEARCH -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.COUNTEREXAMPLE_ATTEMPTS);
            case PROJECT_NOVELTY -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.NOVELTY_CHECKS);
            case PROOF -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.PROOF_ATTEMPTS);
            case LIFECYCLE_HANDOFF -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.LIFECYCLE_HANDOFFS);
        };
        List<ResourceKind> unexpected = budget.resources().keySet().stream()
            .filter(resource -> !allowed.contains(resource))
            .sorted()
            .toList();
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                "resources assigned to wrong v2 stage " + stage + ": " + unexpected);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}
