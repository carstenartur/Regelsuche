package de.regelsuche.experiments.autopilot;

import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Target-free research brief with explicit novelty and lifecycle stages. */
public record AutonomousResearchBriefV2(
    String schema,
    String briefId,
    List<String> allowedDomains,
    List<String> seedGenerators,
    String inventoryHash,
    String packHash,
    String modelHash,
    long deterministicSeed,
    int minimumAggregateInputs,
    int minimumDistinctFamilies,
    int minimumDistinctEvidence,
    String outputNamespace,
    Map<EvidenceStage, StageBudget> stageBudgets,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.autonomous-research-brief/v2";

    public AutonomousResearchBriefV2 {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported autonomous research brief schema");
        }
        requireText(briefId, "briefId");
        allowedDomains = sortedText(allowedDomains, "allowedDomains");
        seedGenerators = sortedText(seedGenerators, "seedGenerators");
        if (allowedDomains.isEmpty() || seedGenerators.isEmpty()) {
            throw new IllegalArgumentException("domains and seed generators must not be empty");
        }
        requireSha256(inventoryHash, "inventoryHash");
        requireSha256(packHash, "packHash");
        requireSha256(modelHash, "modelHash");
        if (minimumAggregateInputs < 2
                || minimumDistinctFamilies < 2
                || minimumDistinctEvidence < 2) {
            throw new IllegalArgumentException(
                "aggregate input, family and evidence minima must be at least two");
        }
        if (minimumDistinctFamilies > minimumAggregateInputs
                || minimumDistinctEvidence > minimumAggregateInputs) {
            throw new IllegalArgumentException(
                "diversity minima cannot exceed the aggregate input minimum");
        }
        requireText(outputNamespace, "outputNamespace");
        stageBudgets = immutableBudgets(stageBudgets);
        if (!stageBudgets.keySet().equals(EnumSet.allOf(EvidenceStage.class))) {
            throw new IllegalArgumentException(
                "research brief requires budgets for every explicit evidence stage");
        }
        stageBudgets.forEach(AutonomousResearchBriefV2::validateStageBudget);
        requireSha256(contentHash, "contentHash");
    }

    public static AutonomousResearchBriefV2 create(
        String briefId,
        List<String> allowedDomains,
        List<String> seedGenerators,
        String inventoryHash,
        String packHash,
        String modelHash,
        long deterministicSeed,
        int minimumAggregateInputs,
        int minimumDistinctFamilies,
        int minimumDistinctEvidence,
        String outputNamespace,
        Map<EvidenceStage, StageBudget> stageBudgets
    ) {
        List<String> domains = sortedText(allowedDomains, "allowedDomains");
        List<String> generators = sortedText(seedGenerators, "seedGenerators");
        Map<EvidenceStage, StageBudget> budgets = immutableBudgets(stageBudgets);
        String material = canonicalMaterial(
            briefId,
            domains,
            generators,
            inventoryHash,
            packHash,
            modelHash,
            deterministicSeed,
            minimumAggregateInputs,
            minimumDistinctFamilies,
            minimumDistinctEvidence,
            outputNamespace,
            budgets);
        return new AutonomousResearchBriefV2(
            SCHEMA,
            briefId,
            domains,
            generators,
            inventoryHash,
            packHash,
            modelHash,
            deterministicSeed,
            minimumAggregateInputs,
            minimumDistinctFamilies,
            minimumDistinctEvidence,
            outputNamespace,
            budgets,
            hash(material));
    }

    public StageBudget budget(EvidenceStage stage) {
        return stageBudgets.get(stage);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("briefId", briefId)
            .stringArray("allowedDomains", allowedDomains)
            .stringArray("seedGenerators", seedGenerators)
            .property("inventoryHash", inventoryHash)
            .property("packHash", packHash)
            .property("modelHash", modelHash)
            .property("deterministicSeed", deterministicSeed)
            .property("minimumAggregateInputs", minimumAggregateInputs)
            .property("minimumDistinctFamilies", minimumDistinctFamilies)
            .property("minimumDistinctEvidence", minimumDistinctEvidence)
            .property("outputNamespace", outputNamespace)
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
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    public static String hash(String material) {
        Objects.requireNonNull(material, "material");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String canonicalMaterial(
        String briefId,
        List<String> domains,
        List<String> generators,
        String inventoryHash,
        String packHash,
        String modelHash,
        long deterministicSeed,
        int minimumAggregateInputs,
        int minimumDistinctFamilies,
        int minimumDistinctEvidence,
        String outputNamespace,
        Map<EvidenceStage, StageBudget> budgets
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nbriefId=").append(briefId)
            .append("\ndomains=").append(domains)
            .append("\ngenerators=").append(generators)
            .append("\ninventory=").append(inventoryHash)
            .append("\npack=").append(packHash)
            .append("\nmodel=").append(modelHash)
            .append("\nseed=").append(deterministicSeed)
            .append("\nminimumAggregateInputs=").append(minimumAggregateInputs)
            .append("\nminimumDistinctFamilies=").append(minimumDistinctFamilies)
            .append("\nminimumDistinctEvidence=").append(minimumDistinctEvidence)
            .append("\noutputNamespace=").append(outputNamespace);
        budgets.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> material.append("\nbudget=")
                .append(entry.getKey().name())
                .append('|')
                .append(entry.getValue().canonicalMaterial()));
        return material.toString();
    }

    private static Map<EvidenceStage, StageBudget> immutableBudgets(
        Map<EvidenceStage, StageBudget> values
    ) {
        Objects.requireNonNull(values, "stageBudgets");
        EnumMap<EvidenceStage, StageBudget> ordered = new EnumMap<>(EvidenceStage.class);
        values.forEach((stage, budget) -> ordered.put(
            Objects.requireNonNull(stage, "stage"),
            Objects.requireNonNull(budget, "stageBudget")));
        return Collections.unmodifiableMap(ordered);
    }

    private static void validateStageBudget(EvidenceStage stage, StageBudget budget) {
        Set<ResourceKind> allowed = switch (stage) {
            case GENERATION -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.GENERATED_STATES,
                ResourceKind.EXPLORED_STATES,
                ResourceKind.OBSERVATIONS);
            case CANDIDATE_FORMATION -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.MINING_BATCHES,
                ResourceKind.CANDIDATES);
            case VALIDATION -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.VALIDATION_CHECKS);
            case COUNTEREXAMPLE_SEARCH -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.COUNTEREXAMPLE_ATTEMPTS);
            case PROJECT_NOVELTY -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.NOVELTY_COMPARISONS);
            case PROOF -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.PROOF_ATTEMPTS);
            case LIFECYCLE_HANDOFF -> EnumSet.of(
                ResourceKind.WALL_CLOCK_MILLIS,
                ResourceKind.LIFECYCLE_HANDOFFS);
        };
        if (!allowed.containsAll(budget.resources().keySet())) {
            EnumSet<ResourceKind> invalid = EnumSet.copyOf(budget.resources().keySet());
            invalid.removeAll(allowed);
            throw new IllegalArgumentException(
                "resources assigned to the wrong stage " + stage + ": " + invalid);
        }
        boolean hasDomainWork = budget.resources().entrySet().stream()
            .anyMatch(entry -> entry.getKey() != ResourceKind.WALL_CLOCK_MILLIS
                && entry.getValue() > 0L);
        if (!hasDomainWork) {
            throw new IllegalArgumentException(
                "every stage requires configured non-time domain work: " + stage);
        }
    }

    private static List<String> sortedText(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return values.stream().distinct().sorted().toList();
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
        OBSERVATIONS,
        MINING_BATCHES,
        CANDIDATES,
        VALIDATION_CHECKS,
        COUNTEREXAMPLE_ATTEMPTS,
        NOVELTY_COMPARISONS,
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

        public long configured(ResourceKind resource) {
            return resources.getOrDefault(resource, 0L);
        }

        String canonicalMaterial() {
            return resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name() + '=' + entry.getValue())
                .toList()
                .toString();
        }
    }
}
