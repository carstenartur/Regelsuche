package de.regelsuche.experiments.autopilot;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner.SeedRunOutcome;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.ResourceKind;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.AllocationDecision;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.AllocationPlan;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BranchSnapshot;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BranchStatus;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BudgetDelta;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.DecisionKind;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes explicit planner allocations through the existing deterministic seed runner.
 *
 * <p>The stage evaluator must return factual resource receipts. Runner success is
 * never interpreted as proof, novelty, promotion or public evidence.</p>
 */
public final class AutonomousCampaignExecutionAdapter {
    public static final String EXECUTION_SCHEMA =
        "regelsuche.autonomous-campaign-execution/v1";
    public static final String ROUND_SCHEMA =
        "regelsuche.autonomous-campaign-round/v1";

    public CampaignRound executeAndReplan(
        AutonomousResearchBrief brief,
        CampaignBudgetLedger ledger,
        AllocationPlan plan,
        List<BranchSnapshot> snapshots,
        List<SeedExpression> seedCatalog,
        int parallelism,
        StageSeedEvaluator evaluator,
        DeterministicCampaignPlanner planner
    ) {
        CampaignExecutionReport execution = execute(
            brief, ledger, plan, snapshots, seedCatalog, parallelism, evaluator);
        AllocationPlan nextPlan = Objects.requireNonNull(planner, "planner").plan(
            brief, execution.updatedLedger(), execution.nextSnapshots());
        String contentHash = AutonomousResearchBrief.hash(
            ROUND_SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\nexecution=" + execution.logicalContentHash()
                + "\nnextPlan=" + nextPlan.contentHash());
        return new CampaignRound(
            ROUND_SCHEMA,
            brief.contentHash(),
            execution,
            nextPlan,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public CampaignExecutionReport execute(
        AutonomousResearchBrief brief,
        CampaignBudgetLedger ledger,
        AllocationPlan plan,
        List<BranchSnapshot> suppliedSnapshots,
        List<SeedExpression> seedCatalog,
        int parallelism,
        StageSeedEvaluator evaluator
    ) {
        validateIdentity(brief, ledger, plan);
        Map<String, BranchSnapshot> snapshots = indexSnapshots(suppliedSnapshots);
        Map<String, SeedExpression> seeds = indexEligibleSeeds(brief, seedCatalog);
        Map<String, AllocationDecision> allocations = plan.decisions().stream()
            .filter(decision -> decision.kind() == DecisionKind.ALLOCATE)
            .collect(java.util.stream.Collectors.toMap(
                AllocationDecision::branchId,
                decision -> decision,
                (left, right) -> {
                    throw new IllegalArgumentException(
                        "duplicate allocation for branch " + left.branchId());
                },
                LinkedHashMap::new));
        validateAllocations(allocations, snapshots);

        Map<String, StageExecution> completed = new ConcurrentHashMap<>();
        List<SeedExpression> runnableSeeds = new ArrayList<>();
        for (AllocationDecision decision : allocations.values()) {
            SeedExpression seed = seeds.get(decision.branchId());
            if (seed == null) {
                completed.put(decision.branchId(), unavailable(decision,
                    "eligible seed missing from supplied catalog"));
            } else {
                runnableSeeds.add(seed);
            }
        }
        runnableSeeds.sort(Comparator.comparing(SeedExpression::stableKey));

        StageSeedEvaluator safeEvaluator = Objects.requireNonNull(evaluator, "evaluator");
        DeterministicDiscoveryExperimentRunner runner =
            new DeterministicDiscoveryExperimentRunner(
                runnableSeeds.size(),
                Math.max(1, parallelism),
                seed -> evaluateSafely(
                    seed,
                    allocations.get(seed.stableKey()),
                    safeEvaluator,
                    completed));
        DeterministicDiscoveryExperimentRunner.DiscoveryReport runnerReport =
            runner.runDetailed(runnableSeeds);

        List<ExecutionReceipt> receipts = allocations.values().stream()
            .map(decision -> receipt(
                decision,
                snapshots.get(decision.branchId()),
                seeds.get(decision.branchId()),
                completed.get(decision.branchId())))
            .sorted(Comparator.comparing(ExecutionReceipt::branchId))
            .toList();
        CampaignBudgetLedger updatedLedger = updateLedger(ledger, receipts);
        List<BranchSnapshot> nextSnapshots = nextSnapshots(
            snapshots, receipts);
        String logicalHash = AutonomousResearchBrief.hash(logicalMaterial(
            brief, plan, receipts, nextSnapshots));
        String runtimeHash = AutonomousResearchBrief.hash(runtimeMaterial(
            runnerReport, receipts, updatedLedger));
        return new CampaignExecutionReport(
            EXECUTION_SCHEMA,
            brief.contentHash(),
            ledger.contentHash(),
            plan.contentHash(),
            updatedLedger,
            receipts,
            nextSnapshots,
            runnerReport.runtimeMillis(),
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            logicalHash,
            runtimeHash);
    }

    private static SeedRunOutcome evaluateSafely(
        SeedExpression seed,
        AllocationDecision decision,
        StageSeedEvaluator evaluator,
        Map<String, StageExecution> completed
    ) {
        EvidenceStage stage = parseStage(decision);
        StageExecution execution;
        try {
            execution = evaluator.execute(stage, seed);
            validateExecution(decision, stage, execution);
        } catch (RuntimeException exception) {
            execution = unavailable(
                decision,
                "stage evaluator failed: " + exception.getClass().getSimpleName());
        }
        completed.put(seed.stableKey(), execution);
        return execution.runnerOutcome();
    }

    private static void validateExecution(
        AllocationDecision decision,
        EvidenceStage stage,
        StageExecution execution
    ) {
        Objects.requireNonNull(execution, "stage execution");
        Map<ResourceKind, Long> planned = decision.budgetDeltas().stream()
            .collect(java.util.stream.Collectors.toMap(
                BudgetDelta::resource,
                BudgetDelta::amount,
                Math::addExact,
                () -> new EnumMap<>(ResourceKind.class)));
        Set<ResourceKind> allowed = allowedResources(stage);
        for (ResourceKind resource : execution.resources()) {
            if (!allowed.contains(resource) || !planned.containsKey(resource)) {
                throw new IllegalArgumentException(
                    "execution reported an unplanned resource: " + resource);
            }
            long consumed = Math.addExact(
                execution.executed(resource), execution.skipped(resource));
            if (consumed > planned.get(resource)) {
                throw new IllegalArgumentException(
                    "execution exceeds planned resource " + resource);
            }
        }
    }

    private static StageExecution unavailable(
        AllocationDecision decision,
        String explanation
    ) {
        Map<ResourceKind, Long> skipped = new EnumMap<>(ResourceKind.class);
        decision.budgetDeltas().forEach(delta -> skipped.merge(
            delta.resource(), delta.amount(), Math::addExact));
        return new StageExecution(
            SeedRunOutcome.fail(explanation),
            ExecutionDisposition.BACKEND_UNAVAILABLE,
            Map.of(),
            skipped,
            explanation);
    }

    private static ExecutionReceipt receipt(
        AllocationDecision decision,
        BranchSnapshot snapshot,
        SeedExpression seed,
        StageExecution execution
    ) {
        if (execution == null) {
            execution = unavailable(decision, "execution receipt missing");
        }
        EvidenceStage stage = parseStage(decision);
        return new ExecutionReceipt(
            decision.branchId(),
            decision.branchSnapshotHash(),
            seed == null ? "" : seed.stableKey(),
            stage,
            execution.disposition(),
            execution.runnerOutcome().success(),
            execution.runnerOutcome().summary(),
            execution.runnerOutcome().hypotheses().size(),
            execution.runnerOutcome().counterexamples().size(),
            execution.runnerOutcome().counterexampleSearchStatus().name(),
            execution.runnerOutcome().resultKind().name(),
            execution.executedResources(),
            execution.skippedResources(),
            execution.runnerOutcome().elapsedMillis(),
            execution.explanation(),
            nextSnapshot(snapshot, stage, execution).snapshotHash());
    }

    private static CampaignBudgetLedger updateLedger(
        CampaignBudgetLedger ledger,
        List<ExecutionReceipt> receipts
    ) {
        CampaignBudgetLedger updated = ledger;
        for (ExecutionReceipt receipt : receipts) {
            Set<ResourceKind> resources = EnumSet.noneOf(ResourceKind.class);
            resources.addAll(receipt.executedResources().keySet());
            resources.addAll(receipt.skippedResources().keySet());
            for (ResourceKind resource : resources) {
                updated = updated.record(
                    receipt.stage(),
                    resource,
                    receipt.executed(resource),
                    receipt.skipped(resource));
            }
        }
        return updated;
    }

    private static List<BranchSnapshot> nextSnapshots(
        Map<String, BranchSnapshot> snapshots,
        List<ExecutionReceipt> receipts
    ) {
        Map<String, ExecutionReceipt> byBranch = receipts.stream()
            .collect(java.util.stream.Collectors.toMap(
                ExecutionReceipt::branchId, receipt -> receipt));
        return snapshots.values().stream()
            .map(snapshot -> {
                ExecutionReceipt receipt = byBranch.get(snapshot.branchId());
                if (receipt == null) {
                    return snapshot;
                }
                StageExecution execution = new StageExecution(
                    SeedRunOutcome.fail(receipt.summary()),
                    receipt.disposition(),
                    receipt.executedResources(),
                    receipt.skippedResources(),
                    receipt.explanation());
                return nextSnapshot(snapshot, receipt.stage(), execution);
            })
            .sorted(Comparator.comparing(BranchSnapshot::branchId))
            .toList();
    }

    private static BranchSnapshot nextSnapshot(
        BranchSnapshot snapshot,
        EvidenceStage stage,
        StageExecution execution
    ) {
        EnumSet<EvidenceStage> completed = EnumSet.copyOf(snapshot.completedStages());
        if (execution.disposition() == ExecutionDisposition.COMPLETED) {
            completed.add(stage);
        }
        BranchStatus status = switch (execution.disposition()) {
            case DUPLICATE -> BranchStatus.DUPLICATE;
            case DISPROVED -> BranchStatus.DISPROVED;
            case UNSAFE -> BranchStatus.UNSAFE;
            case PERSISTENTLY_WEAK -> BranchStatus.PERSISTENTLY_WEAK;
            case COMPLETED -> completed.containsAll(snapshot.mandatoryStages())
                ? BranchStatus.COMPLETE_EVIDENCE
                : BranchStatus.ELIGIBLE_INCOMPLETE;
            case INCONCLUSIVE, BACKEND_UNAVAILABLE ->
                BranchStatus.ELIGIBLE_INCOMPLETE;
        };
        return BranchSnapshot.create(
            snapshot.branchId(),
            snapshot.familyId(),
            status,
            snapshot.mandatoryStages(),
            completed,
            snapshot.supportDiversity(),
            snapshot.counterexampleRiskPermille(),
            snapshot.interestingnessPermille());
    }

    private static Map<String, BranchSnapshot> indexSnapshots(
        List<BranchSnapshot> supplied
    ) {
        Objects.requireNonNull(supplied, "snapshots");
        Map<String, BranchSnapshot> result = new LinkedHashMap<>();
        for (BranchSnapshot snapshot : supplied.stream()
                .sorted(Comparator.comparing(BranchSnapshot::branchId)).toList()) {
            if (snapshot == null) {
                throw new IllegalArgumentException("snapshots must not contain null");
            }
            if (result.putIfAbsent(snapshot.branchId(), snapshot) != null) {
                throw new IllegalArgumentException(
                    "duplicate branch snapshot: " + snapshot.branchId());
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, SeedExpression> indexEligibleSeeds(
        AutonomousResearchBrief brief,
        List<SeedExpression> supplied
    ) {
        Objects.requireNonNull(supplied, "seedCatalog");
        Set<String> domains = brief.allowedDomains().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        Set<String> generators = Set.copyOf(brief.seedGenerators());
        Map<String, SeedExpression> result = new TreeMap<>();
        for (SeedExpression seed : supplied) {
            if (seed == null || seed.expression().isBlank()) {
                continue;
            }
            boolean domainAllowed = domains.contains(
                seed.category().toLowerCase(Locale.ROOT));
            boolean generatorAllowed = generators.contains(seed.source());
            if (!domainAllowed || !generatorAllowed) {
                continue;
            }
            if (result.putIfAbsent(seed.stableKey(), seed) != null) {
                throw new IllegalArgumentException(
                    "duplicate eligible seed key: " + seed.stableKey());
            }
        }
        return Map.copyOf(result);
    }

    private static void validateIdentity(
        AutonomousResearchBrief brief,
        CampaignBudgetLedger ledger,
        AllocationPlan plan
    ) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(plan, "plan");
        ledger.validateAgainst(brief);
        if (!brief.contentHash().equals(plan.briefHash())) {
            throw new IllegalArgumentException("plan belongs to a different brief");
        }
        if (!ledger.contentHash().equals(plan.ledgerHash())) {
            throw new IllegalArgumentException("plan belongs to a different ledger state");
        }
    }

    private static void validateAllocations(
        Map<String, AllocationDecision> allocations,
        Map<String, BranchSnapshot> snapshots
    ) {
        for (AllocationDecision decision : allocations.values()) {
            BranchSnapshot snapshot = snapshots.get(decision.branchId());
            if (snapshot == null) {
                throw new IllegalArgumentException(
                    "allocation has no branch snapshot: " + decision.branchId());
            }
            if (!snapshot.snapshotHash().equals(decision.branchSnapshotHash())) {
                throw new IllegalArgumentException(
                    "allocation and branch snapshot hash differ: " + decision.branchId());
            }
        }
    }

    private static EvidenceStage parseStage(AllocationDecision decision) {
        try {
            return EvidenceStage.valueOf(decision.stage());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "allocation stage is not an evidence stage: " + decision.stage(),
                exception);
        }
    }

    private static Set<ResourceKind> allowedResources(EvidenceStage stage) {
        return switch (stage) {
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
    }

    private static String logicalMaterial(
        AutonomousResearchBrief brief,
        AllocationPlan plan,
        List<ExecutionReceipt> receipts,
        List<BranchSnapshot> nextSnapshots
    ) {
        StringBuilder material = new StringBuilder(EXECUTION_SCHEMA)
            .append("\nbrief=").append(brief.contentHash())
            .append("\nplan=").append(plan.contentHash());
        receipts.forEach(receipt -> material.append("\nreceipt=")
            .append(receipt.logicalMaterial()));
        nextSnapshots.forEach(snapshot -> material.append("\nnext=")
            .append(snapshot.branchId()).append('|')
            .append(snapshot.snapshotHash()));
        return material.toString();
    }

    private static String runtimeMaterial(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport runnerReport,
        List<ExecutionReceipt> receipts,
        CampaignBudgetLedger updatedLedger
    ) {
        StringBuilder material = new StringBuilder("regelsuche.autonomous-runtime/v1")
            .append("\nrunnerMillis=").append(runnerReport.runtimeMillis())
            .append("\nupdatedLedger=").append(updatedLedger.contentHash());
        receipts.forEach(receipt -> material.append("\nruntime=")
            .append(receipt.branchId()).append('|').append(receipt.elapsedMillis()));
        return material.toString();
    }

    @FunctionalInterface
    public interface StageSeedEvaluator {
        StageExecution execute(EvidenceStage stage, SeedExpression seed);
    }

    public enum ExecutionDisposition {
        COMPLETED,
        INCONCLUSIVE,
        DUPLICATE,
        DISPROVED,
        UNSAFE,
        PERSISTENTLY_WEAK,
        BACKEND_UNAVAILABLE
    }

    public record StageExecution(
        SeedRunOutcome runnerOutcome,
        ExecutionDisposition disposition,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources,
        String explanation
    ) {
        public StageExecution {
            runnerOutcome = Objects.requireNonNull(runnerOutcome, "runnerOutcome");
            disposition = Objects.requireNonNull(disposition, "disposition");
            executedResources = immutableResources(
                executedResources, "executedResources");
            skippedResources = immutableResources(
                skippedResources, "skippedResources");
            explanation = explanation == null ? "" : explanation;
        }

        public long executed(ResourceKind resource) {
            return executedResources.getOrDefault(resource, 0L);
        }

        public long skipped(ResourceKind resource) {
            return skippedResources.getOrDefault(resource, 0L);
        }

        Set<ResourceKind> resources() {
            EnumSet<ResourceKind> resources = EnumSet.noneOf(ResourceKind.class);
            resources.addAll(executedResources.keySet());
            resources.addAll(skippedResources.keySet());
            return resources;
        }
    }

    public record ExecutionReceipt(
        String branchId,
        String branchSnapshotHash,
        String seedStableKey,
        EvidenceStage stage,
        ExecutionDisposition disposition,
        boolean runnerSuccess,
        String summary,
        int hypothesisCount,
        int counterexampleCount,
        String counterexampleStatus,
        String discoveryResultKind,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources,
        long elapsedMillis,
        String explanation,
        String nextSnapshotHash
    ) {
        public ExecutionReceipt {
            requireText(branchId, "branchId");
            requireSha256(branchSnapshotHash, "branchSnapshotHash");
            seedStableKey = seedStableKey == null ? "" : seedStableKey;
            stage = Objects.requireNonNull(stage, "stage");
            disposition = Objects.requireNonNull(disposition, "disposition");
            summary = summary == null ? "" : summary;
            if (hypothesisCount < 0 || counterexampleCount < 0 || elapsedMillis < 0L) {
                throw new IllegalArgumentException("receipt counts must be non-negative");
            }
            requireText(counterexampleStatus, "counterexampleStatus");
            requireText(discoveryResultKind, "discoveryResultKind");
            executedResources = immutableResources(
                executedResources, "executedResources");
            skippedResources = immutableResources(
                skippedResources, "skippedResources");
            explanation = explanation == null ? "" : explanation;
            requireSha256(nextSnapshotHash, "nextSnapshotHash");
        }

        public long executed(ResourceKind resource) {
            return executedResources.getOrDefault(resource, 0L);
        }

        public long skipped(ResourceKind resource) {
            return skippedResources.getOrDefault(resource, 0L);
        }

        String logicalMaterial() {
            Map<ResourceKind, Long> logicalExecuted = new TreeMap<>(
                Comparator.comparing(Enum::name));
            logicalExecuted.putAll(executedResources);
            logicalExecuted.remove(ResourceKind.WALL_CLOCK_MILLIS);
            Map<ResourceKind, Long> logicalSkipped = new TreeMap<>(
                Comparator.comparing(Enum::name));
            logicalSkipped.putAll(skippedResources);
            logicalSkipped.remove(ResourceKind.WALL_CLOCK_MILLIS);
            return branchId + '|' + branchSnapshotHash + '|' + seedStableKey + '|'
                + stage.name() + '|' + disposition.name() + '|' + runnerSuccess + '|'
                + summary + '|' + hypothesisCount + '|' + counterexampleCount + '|'
                + counterexampleStatus + '|' + discoveryResultKind + '|'
                + logicalExecuted + '|' + logicalSkipped + '|' + explanation + '|'
                + nextSnapshotHash;
        }
    }

    public record CampaignExecutionReport(
        String schema,
        String briefHash,
        String initialLedgerHash,
        String planHash,
        CampaignBudgetLedger updatedLedger,
        List<ExecutionReceipt> receipts,
        List<BranchSnapshot> nextSnapshots,
        long runnerRuntimeMillis,
        boolean executionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String logicalContentHash,
        String runtimeTelemetryHash
    ) {
        public CampaignExecutionReport {
            if (!EXECUTION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported execution schema");
            }
            requireSha256(briefHash, "briefHash");
            requireSha256(initialLedgerHash, "initialLedgerHash");
            requireSha256(planHash, "planHash");
            updatedLedger = Objects.requireNonNull(updatedLedger, "updatedLedger");
            receipts = receipts == null
                ? List.of()
                : receipts.stream()
                    .sorted(Comparator.comparing(ExecutionReceipt::branchId))
                    .toList();
            nextSnapshots = nextSnapshots == null
                ? List.of()
                : nextSnapshots.stream()
                    .sorted(Comparator.comparing(BranchSnapshot::branchId))
                    .toList();
            if (runnerRuntimeMillis < 0L) {
                throw new IllegalArgumentException("runnerRuntimeMillis must be non-negative");
            }
            if (executionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "execution telemetry cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(logicalContentHash, "logicalContentHash");
            requireSha256(runtimeTelemetryHash, "runtimeTelemetryHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("initialLedgerHash", initialLedgerHash)
                .property("planHash", planHash)
                .property("updatedLedgerHash", updatedLedger.contentHash())
                .property("executionIsMathematicalEvidence",
                    executionIsMathematicalEvidence)
                .array("receipts", array -> receipts.forEach(receipt ->
                    array.objectValue(object -> writeReceipt(object, receipt))))
                .array("nextSnapshots", array -> nextSnapshots.forEach(snapshot ->
                    array.objectValue(object -> object
                        .property("branchId", snapshot.branchId())
                        .property("familyId", snapshot.familyId())
                        .property("status", snapshot.status().name())
                        .stringArray("mandatoryStages", snapshot.mandatoryStages()
                            .stream().map(Enum::name).sorted().toList())
                        .stringArray("completedStages", snapshot.completedStages()
                            .stream().map(Enum::name).sorted().toList())
                        .property("snapshotHash", snapshot.snapshotHash()))))
                .property("runnerRuntimeMillis", runnerRuntimeMillis)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("logicalContentHash", logicalContentHash)
                .property("runtimeTelemetryHash", runtimeTelemetryHash)
                .endObject()
                .toString();
        }

        public void write(Path directory) {
            try {
                Files.createDirectories(directory);
                Files.writeString(
                    directory.resolve("execution.json"),
                    toCanonicalJson(),
                    StandardCharsets.UTF_8);
                Files.writeString(
                    directory.resolve("updated-ledger.json"),
                    updatedLedger.toCanonicalJson(),
                    StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static void writeReceipt(
            JsonWriter object,
            ExecutionReceipt receipt
        ) {
            object.property("branchId", receipt.branchId())
                .property("branchSnapshotHash", receipt.branchSnapshotHash())
                .property("seedStableKey", receipt.seedStableKey())
                .property("stage", receipt.stage().name())
                .property("disposition", receipt.disposition().name())
                .property("runnerSuccess", receipt.runnerSuccess())
                .property("summary", receipt.summary())
                .property("hypothesisCount", receipt.hypothesisCount())
                .property("counterexampleCount", receipt.counterexampleCount())
                .property("counterexampleStatus", receipt.counterexampleStatus())
                .property("discoveryResultKind", receipt.discoveryResultKind())
                .array("executedResources", resources -> receipt.executedResources()
                    .entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> resources.objectValue(item -> item
                        .property("resource", entry.getKey().name())
                        .property("amount", entry.getValue()))))
                .array("skippedResources", resources -> receipt.skippedResources()
                    .entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> resources.objectValue(item -> item
                        .property("resource", entry.getKey().name())
                        .property("amount", entry.getValue()))))
                .property("elapsedMillis", receipt.elapsedMillis())
                .property("explanation", receipt.explanation())
                .property("nextSnapshotHash", receipt.nextSnapshotHash());
        }
    }

    public record CampaignRound(
        String schema,
        String briefHash,
        CampaignExecutionReport execution,
        AllocationPlan nextPlan,
        boolean roundDecisionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignRound {
            if (!ROUND_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported campaign round schema");
            }
            requireSha256(briefHash, "briefHash");
            execution = Objects.requireNonNull(execution, "execution");
            nextPlan = Objects.requireNonNull(nextPlan, "nextPlan");
            if (roundDecisionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "campaign round cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public void write(Path directory) {
            execution.write(directory);
            try {
                Files.writeString(
                    directory.resolve("next-plan.json"),
                    nextPlan.toCanonicalJson(),
                    StandardCharsets.UTF_8);
                Files.writeString(
                    directory.resolve("round.json"),
                    toCanonicalJson(),
                    StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("executionLogicalHash", execution.logicalContentHash())
                .property("executionRuntimeHash", execution.runtimeTelemetryHash())
                .property("nextPlanHash", nextPlan.contentHash())
                .property("roundDecisionIsMathematicalEvidence",
                    roundDecisionIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static Map<ResourceKind, Long> immutableResources(
        Map<ResourceKind, Long> values,
        String name
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        EnumMap<ResourceKind, Long> ordered = new EnumMap<>(ResourceKind.class);
        values.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, name + " resource");
            if (amount == null || amount < 0L) {
                throw new IllegalArgumentException(
                    name + " values must be non-negative");
            }
            if (amount > 0L) {
                ordered.put(resource, amount);
            }
        });
        return Collections.unmodifiableMap(ordered);
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
