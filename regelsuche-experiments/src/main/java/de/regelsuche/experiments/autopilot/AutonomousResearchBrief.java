package de.regelsuche.experiments.autopilot;

import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Versioned, target-free input contract for one autonomous discovery campaign.
 *
 * <p>The brief describes allowed generators and resource boundaries. It has no
 * target-expression or expected-answer field; downstream validation remains a
 * separate concern.</p>
 */
public record AutonomousResearchBrief(
    String schema,
    String briefId,
    List<String> allowedDomains,
    List<String> seedGenerators,
    StructuralBounds structuralBounds,
    String inventoryHash,
    String packHash,
    String modelHash,
    long deterministicSeed,
    Set<EvidenceStage> enabledStages,
    List<String> permittedCapabilities,
    int minimumFamilyDiversity,
    int minimumSupportDiversity,
    boolean requireCompleteMandatoryEvidence,
    AllocationPolicy allocationPolicy,
    CampaignBudget budget,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.autonomous-research-brief/v1";

    public AutonomousResearchBrief {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported autonomous research brief schema");
        }
        requireText(briefId, "briefId");
        allowedDomains = sortedText(allowedDomains, "allowedDomains");
        seedGenerators = sortedText(seedGenerators, "seedGenerators");
        if (allowedDomains.isEmpty() || seedGenerators.isEmpty()) {
            throw new IllegalArgumentException(
                "target-free research brief requires domains and seed generators");
        }
        structuralBounds = Objects.requireNonNull(structuralBounds, "structuralBounds");
        requireSha256(inventoryHash, "inventoryHash");
        requireSha256(packHash, "packHash");
        requireSha256(modelHash, "modelHash");
        enabledStages = immutableStages(enabledStages);
        if (!enabledStages.contains(EvidenceStage.GENERATION)
                || !enabledStages.contains(EvidenceStage.CANDIDATE_FORMATION)) {
            throw new IllegalArgumentException(
                "generation and candidate formation must be enabled");
        }
        permittedCapabilities = sortedText(
            permittedCapabilities, "permittedCapabilities");
        if (minimumFamilyDiversity < 2 || minimumSupportDiversity < 2) {
            throw new IllegalArgumentException(
                "family and support diversity must both be at least two");
        }
        allocationPolicy = Objects.requireNonNull(allocationPolicy, "allocationPolicy");
        budget = Objects.requireNonNull(budget, "budget");
        budget.validateFor(enabledStages);
        requireSha256(contentHash, "contentHash");
    }

    public static AutonomousResearchBrief create(
        String briefId,
        List<String> allowedDomains,
        List<String> seedGenerators,
        StructuralBounds structuralBounds,
        String inventoryHash,
        String packHash,
        String modelHash,
        long deterministicSeed,
        Set<EvidenceStage> enabledStages,
        List<String> permittedCapabilities,
        int minimumFamilyDiversity,
        int minimumSupportDiversity,
        boolean requireCompleteMandatoryEvidence,
        AllocationPolicy allocationPolicy,
        CampaignBudget budget
    ) {
        List<String> domains = sortedText(allowedDomains, "allowedDomains");
        List<String> generators = sortedText(seedGenerators, "seedGenerators");
        Set<EvidenceStage> stages = immutableStages(enabledStages);
        List<String> capabilities = sortedText(
            permittedCapabilities, "permittedCapabilities");
        String hash = hash(canonicalMaterial(
            briefId,
            domains,
            generators,
            structuralBounds,
            inventoryHash,
            packHash,
            modelHash,
            deterministicSeed,
            stages,
            capabilities,
            minimumFamilyDiversity,
            minimumSupportDiversity,
            requireCompleteMandatoryEvidence,
            allocationPolicy,
            budget));
        return new AutonomousResearchBrief(
            SCHEMA,
            briefId,
            domains,
            generators,
            structuralBounds,
            inventoryHash,
            packHash,
            modelHash,
            deterministicSeed,
            stages,
            capabilities,
            minimumFamilyDiversity,
            minimumSupportDiversity,
            requireCompleteMandatoryEvidence,
            allocationPolicy,
            budget,
            hash);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("briefId", briefId)
            .stringArray("allowedDomains", allowedDomains)
            .stringArray("seedGenerators", seedGenerators)
            .object("structuralBounds", object -> object
                .property("maximumDepth", structuralBounds.maximumDepth())
                .property("maximumExpressionNodes",
                    structuralBounds.maximumExpressionNodes())
                .property("maximumBranchesPerSeed",
                    structuralBounds.maximumBranchesPerSeed()))
            .property("inventoryHash", inventoryHash)
            .property("packHash", packHash)
            .property("modelHash", modelHash)
            .property("deterministicSeed", deterministicSeed)
            .stringArray("enabledStages", enabledStages.stream()
                .map(Enum::name).sorted().toList())
            .stringArray("permittedCapabilities", permittedCapabilities)
            .property("minimumFamilyDiversity", minimumFamilyDiversity)
            .property("minimumSupportDiversity", minimumSupportDiversity)
            .property("requireCompleteMandatoryEvidence",
                requireCompleteMandatoryEvidence)
            .property("allocationPolicy", allocationPolicy.name())
            .array("stageBudgets", array -> budget.stageBudgets().entrySet().stream()
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

    private static String canonicalMaterial(
        String briefId,
        List<String> domains,
        List<String> generators,
        StructuralBounds bounds,
        String inventoryHash,
        String packHash,
        String modelHash,
        long deterministicSeed,
        Set<EvidenceStage> stages,
        List<String> capabilities,
        int minimumFamilyDiversity,
        int minimumSupportDiversity,
        boolean requireCompleteMandatoryEvidence,
        AllocationPolicy allocationPolicy,
        CampaignBudget budget
    ) {
        return SCHEMA
            + "\nbriefId=" + briefId
            + "\ndomains=" + domains
            + "\ngenerators=" + generators
            + "\nbounds=" + bounds.canonicalMaterial()
            + "\ninventory=" + inventoryHash
            + "\npack=" + packHash
            + "\nmodel=" + modelHash
            + "\nseed=" + deterministicSeed
            + "\nstages=" + stages.stream().map(Enum::name).sorted().toList()
            + "\ncapabilities=" + capabilities
            + "\nminimumFamilyDiversity=" + minimumFamilyDiversity
            + "\nminimumSupportDiversity=" + minimumSupportDiversity
            + "\nrequireComplete=" + requireCompleteMandatoryEvidence
            + "\npolicy=" + allocationPolicy.name()
            + "\nbudget=" + budget.canonicalMaterial();
    }

    public enum EvidenceStage {
        GENERATION,
        CANDIDATE_FORMATION,
        VALIDATION,
        COUNTEREXAMPLE_SEARCH,
        PROOF
    }

    public enum ResourceKind {
        WALL_CLOCK_MILLIS,
        GENERATED_STATES,
        EXPLORED_STATES,
        CANDIDATES,
        VALIDATION_CHECKS,
        COUNTEREXAMPLE_ATTEMPTS,
        PROOF_ATTEMPTS
    }

    public enum AllocationPolicy {
        ROUND_ROBIN,
        EVIDENCE_COMPLETION_FIRST,
        COUNTEREXAMPLE_RISK_FIRST,
        BALANCED
    }

    public record StructuralBounds(
        int maximumDepth,
        int maximumExpressionNodes,
        int maximumBranchesPerSeed
    ) {
        public StructuralBounds {
            if (maximumDepth < 1
                    || maximumExpressionNodes < 1
                    || maximumBranchesPerSeed < 1) {
                throw new IllegalArgumentException(
                    "structural bounds must all be positive");
            }
        }

        String canonicalMaterial() {
            return maximumDepth + "|" + maximumExpressionNodes + "|"
                + maximumBranchesPerSeed;
        }
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

    public record CampaignBudget(Map<EvidenceStage, StageBudget> stageBudgets) {
        public CampaignBudget {
            Objects.requireNonNull(stageBudgets, "stageBudgets");
            EnumMap<EvidenceStage, StageBudget> ordered =
                new EnumMap<>(EvidenceStage.class);
            stageBudgets.forEach((stage, stageBudget) ->
                ordered.put(
                    Objects.requireNonNull(stage, "stage"),
                    Objects.requireNonNull(stageBudget, "stageBudget")));
            stageBudgets = Collections.unmodifiableMap(ordered);
        }

        public StageBudget forStage(EvidenceStage stage) {
            return stageBudgets.getOrDefault(stage, new StageBudget(Map.of()));
        }

        void validateFor(Set<EvidenceStage> enabledStages) {
            for (EvidenceStage stage : enabledStages) {
                if (!forStage(stage).hasWork()) {
                    throw new IllegalArgumentException(
                        "enabled evidence stage has no configured budget: " + stage);
                }
            }
            for (Map.Entry<EvidenceStage, StageBudget> entry : stageBudgets.entrySet()) {
                if (!enabledStages.contains(entry.getKey()) && entry.getValue().hasWork()) {
                    throw new IllegalArgumentException(
                        "disabled evidence stage has configured budget: " + entry.getKey());
                }
                validateResourcePlacement(entry.getKey(), entry.getValue());
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
                case PROOF -> EnumSet.of(
                    ResourceKind.WALL_CLOCK_MILLIS,
                    ResourceKind.PROOF_ATTEMPTS);
            };
            Set<ResourceKind> unexpected = new TreeSet<>(
                Comparator.comparing(Enum::name));
            unexpected.addAll(budget.resources().keySet());
            unexpected.removeAll(allowed);
            if (!unexpected.isEmpty()) {
                throw new IllegalArgumentException(
                    "resources assigned to the wrong stage " + stage + ": " + unexpected);
            }
        }

        String canonicalMaterial() {
            return stageBudgets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name() + ':'
                    + entry.getValue().canonicalMaterial())
                .toList()
                .toString();
        }
    }

    private static Set<EvidenceStage> immutableStages(Set<EvidenceStage> values) {
        Objects.requireNonNull(values, "enabledStages");
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("enabledStages must not be empty or contain null");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
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

    static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
