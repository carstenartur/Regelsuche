package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.AllocationPolicy;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.ResourceKind;
import de.regelsuche.experiments.autopilot.CampaignBudgetLedger.BudgetLine;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Produces a deterministic, non-executing allocation plan for existing
 * discovery/evidence stages.
 */
public final class DeterministicCampaignPlanner {
    public static final String SCHEMA = "regelsuche.autonomous-campaign-plan/v1";

    public AllocationPlan plan(
        AutonomousResearchBrief brief,
        CampaignBudgetLedger ledger,
        List<BranchSnapshot> suppliedBranches
    ) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(ledger, "ledger");
        ledger.validateAgainst(brief);
        List<BranchSnapshot> branches = orderedBranches(brief, suppliedBranches);
        ensureUniqueBranchIds(branches);

        Map<LineKey, Long> remaining = remainingByLine(ledger);
        Map<LineKey, Long> planned = new TreeMap<>();
        List<AllocationDecision> decisions = new ArrayList<>();
        for (BranchSnapshot branch : branches) {
            decisions.add(decide(brief, branch, remaining, planned));
        }
        List<PlannedBudget> plannedBudget = planned.entrySet().stream()
            .filter(entry -> entry.getValue() > 0L)
            .map(entry -> new PlannedBudget(
                entry.getKey().stage(), entry.getKey().resource(), entry.getValue()))
            .toList();
        validatePlanWithinLedger(plannedBudget, ledger);
        String contentHash = AutonomousResearchBrief.hash(canonicalMaterial(
            brief, ledger, decisions, plannedBudget));
        return new AllocationPlan(
            SCHEMA,
            brief.contentHash(),
            ledger.contentHash(),
            brief.allocationPolicy(),
            decisions,
            plannedBudget,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static AllocationDecision decide(
        AutonomousResearchBrief brief,
        BranchSnapshot branch,
        Map<LineKey, Long> remaining,
        Map<LineKey, Long> planned
    ) {
        if (branch.status().hardStopped()) {
            return AllocationDecision.stop(
                branch,
                "hard-stop=" + branch.status().name(),
                List.of("no additional generation or evidence budget"));
        }
        if (branch.mandatoryEvidenceComplete()) {
            return AllocationDecision.noAction(
                branch,
                "mandatory evidence already complete");
        }
        Optional<EvidenceStage> nextStage = branch.nextMissingStage(brief.enabledStages());
        if (nextStage.isEmpty()) {
            return AllocationDecision.stop(
                branch,
                "mandatory-stage-not-enabled",
                List.of("brief and branch mandatory stages are inconsistent"));
        }
        EvidenceStage stage = nextStage.orElseThrow();
        List<BudgetDelta> requested = requestedBudget(stage);
        if (!canAllocate(requested, remaining, planned)) {
            return AllocationDecision.stop(
                branch,
                "budget-exhausted=" + stage.name(),
                requested.stream()
                    .map(delta -> delta.resource().name() + '=' + delta.amount())
                    .toList());
        }
        requested.forEach(delta -> planned.merge(
            new LineKey(stage, delta.resource()), delta.amount(), Math::addExact));
        return AllocationDecision.allocate(
            branch,
            stage,
            requested,
            allocationReason(brief, branch, stage));
    }

    private static String allocationReason(
        AutonomousResearchBrief brief,
        BranchSnapshot branch,
        EvidenceStage stage
    ) {
        String interestingness = branch.interestingnessPermille() < 0
            ? "NOT_EVALUATED"
            : Integer.toString(branch.interestingnessPermille());
        return "policy=" + brief.allocationPolicy().name()
            + ";missingStage=" + stage.name()
            + ";supportDiversity=" + branch.supportDiversity()
            + ";counterexampleRisk=" + branch.counterexampleRiskPermille()
            + ";interestingness=" + interestingness;
    }

    private static List<BudgetDelta> requestedBudget(EvidenceStage stage) {
        return switch (stage) {
            case GENERATION -> List.of(
                new BudgetDelta(ResourceKind.WALL_CLOCK_MILLIS, 1000L),
                new BudgetDelta(ResourceKind.GENERATED_STATES, 100L),
                new BudgetDelta(ResourceKind.EXPLORED_STATES, 50L));
            case CANDIDATE_FORMATION -> List.of(
                new BudgetDelta(ResourceKind.WALL_CLOCK_MILLIS, 250L),
                new BudgetDelta(ResourceKind.CANDIDATES, 1L));
            case VALIDATION -> List.of(
                new BudgetDelta(ResourceKind.WALL_CLOCK_MILLIS, 250L),
                new BudgetDelta(ResourceKind.VALIDATION_CHECKS, 1L));
            case COUNTEREXAMPLE_SEARCH -> List.of(
                new BudgetDelta(ResourceKind.WALL_CLOCK_MILLIS, 250L),
                new BudgetDelta(ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 1L));
            case PROOF -> List.of(
                new BudgetDelta(ResourceKind.WALL_CLOCK_MILLIS, 1000L),
                new BudgetDelta(ResourceKind.PROOF_ATTEMPTS, 1L));
        };
    }

    private static boolean canAllocate(
        List<BudgetDelta> requested,
        Map<LineKey, Long> remaining,
        Map<LineKey, Long> planned
    ) {
        EvidenceStage stage = requestedStage(requested, remaining);
        for (BudgetDelta delta : requested) {
            LineKey key = new LineKey(stage, delta.resource());
            long available = remaining.getOrDefault(key, 0L)
                - planned.getOrDefault(key, 0L);
            if (available < delta.amount()) {
                return false;
            }
        }
        return true;
    }

    private static EvidenceStage requestedStage(
        List<BudgetDelta> requested,
        Map<LineKey, Long> remaining
    ) {
        return remaining.keySet().stream()
            .map(LineKey::stage)
            .filter(stage -> requested.stream().allMatch(delta ->
                remaining.containsKey(new LineKey(stage, delta.resource()))))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "requested budget resources do not belong to one configured stage"));
    }

    private static Map<LineKey, Long> remainingByLine(CampaignBudgetLedger ledger) {
        Map<LineKey, Long> remaining = new TreeMap<>();
        ledger.lines().forEach(line -> remaining.put(
            new LineKey(line.stage(), line.resource()), line.remaining()));
        return remaining;
    }

    private static void validatePlanWithinLedger(
        List<PlannedBudget> planned,
        CampaignBudgetLedger ledger
    ) {
        for (PlannedBudget item : planned) {
            if (item.amount() > ledger.remaining(item.stage(), item.resource())) {
                throw new IllegalStateException(
                    "planned allocation exceeds factual ledger remaining budget");
            }
        }
    }

    private static List<BranchSnapshot> orderedBranches(
        AutonomousResearchBrief brief,
        List<BranchSnapshot> supplied
    ) {
        Objects.requireNonNull(supplied, "branches");
        if (supplied.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("branches must not contain null");
        }
        Comparator<BranchSnapshot> comparator = switch (brief.allocationPolicy()) {
            case ROUND_ROBIN -> Comparator
                .comparing((BranchSnapshot branch) -> seededKey(
                    brief.deterministicSeed(), branch.branchId()))
                .thenComparing(BranchSnapshot::branchId);
            case EVIDENCE_COMPLETION_FIRST -> Comparator
                .comparingInt(BranchSnapshot::missingMandatoryStageCount)
                .thenComparing(Comparator.comparingInt(
                    BranchSnapshot::supportDiversity).reversed())
                .thenComparing(BranchSnapshot::branchId);
            case COUNTEREXAMPLE_RISK_FIRST -> Comparator
                .comparingInt(BranchSnapshot::counterexampleRiskPermille)
                .reversed()
                .thenComparingInt(BranchSnapshot::missingMandatoryStageCount)
                .thenComparing(BranchSnapshot::branchId);
            case BALANCED -> Comparator
                .comparingInt(DeterministicCampaignPlanner::balancedPriority)
                .reversed()
                .thenComparing(BranchSnapshot::branchId);
        };
        return supplied.stream().sorted(comparator).toList();
    }

    private static int balancedPriority(BranchSnapshot branch) {
        int interestingness = Math.max(0, branch.interestingnessPermille());
        return branch.supportDiversity() * 200
            + branch.counterexampleRiskPermille() / 4
            + interestingness / 4
            - branch.missingMandatoryStageCount() * 150;
    }

    private static String seededKey(long seed, String branchId) {
        return AutonomousResearchBrief.hash(seed + "|" + branchId);
    }

    private static void ensureUniqueBranchIds(List<BranchSnapshot> branches) {
        long distinct = branches.stream().map(BranchSnapshot::branchId).distinct().count();
        if (distinct != branches.size()) {
            throw new IllegalArgumentException("branch IDs must be unique");
        }
    }

    private static String canonicalMaterial(
        AutonomousResearchBrief brief,
        CampaignBudgetLedger ledger,
        List<AllocationDecision> decisions,
        List<PlannedBudget> planned
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nbrief=").append(brief.contentHash())
            .append("\nledger=").append(ledger.contentHash())
            .append("\npolicy=").append(brief.allocationPolicy().name());
        decisions.forEach(decision -> material.append("\ndecision=")
            .append(decision.canonicalMaterial()));
        planned.forEach(item -> material.append("\nplanned=")
            .append(item.canonicalMaterial()));
        return material.toString();
    }

    public enum BranchStatus {
        NEW(false),
        ELIGIBLE_INCOMPLETE(false),
        COMPLETE_EVIDENCE(false),
        DUPLICATE(true),
        DISPROVED(true),
        UNSAFE(true),
        PERSISTENTLY_WEAK(true);

        private final boolean hardStopped;

        BranchStatus(boolean hardStopped) {
            this.hardStopped = hardStopped;
        }

        public boolean hardStopped() {
            return hardStopped;
        }
    }

    public enum DecisionKind {
        ALLOCATE,
        STOP,
        NO_ACTION
    }

    public record BranchSnapshot(
        String branchId,
        String familyId,
        BranchStatus status,
        Set<EvidenceStage> mandatoryStages,
        Set<EvidenceStage> completedStages,
        int supportDiversity,
        int counterexampleRiskPermille,
        int interestingnessPermille,
        String snapshotHash
    ) {
        public BranchSnapshot {
            requireText(branchId, "branchId");
            requireText(familyId, "familyId");
            status = Objects.requireNonNull(status, "status");
            mandatoryStages = immutableStages(mandatoryStages, "mandatoryStages");
            completedStages = immutableStages(completedStages, "completedStages");
            if (!mandatoryStages.containsAll(completedStages)) {
                throw new IllegalArgumentException(
                    "completed stages must be a subset of mandatory stages");
            }
            if (supportDiversity < 0) {
                throw new IllegalArgumentException("supportDiversity must be non-negative");
            }
            requirePermille(counterexampleRiskPermille, "counterexampleRiskPermille");
            if (interestingnessPermille < -1 || interestingnessPermille > 1000) {
                throw new IllegalArgumentException(
                    "interestingnessPermille must be -1 or in [0,1000]");
            }
            requireSha256(snapshotHash, "snapshotHash");
        }

        public static BranchSnapshot create(
            String branchId,
            String familyId,
            BranchStatus status,
            Set<EvidenceStage> mandatoryStages,
            Set<EvidenceStage> completedStages,
            int supportDiversity,
            int counterexampleRiskPermille,
            int interestingnessPermille
        ) {
            Set<EvidenceStage> mandatory = immutableStages(
                mandatoryStages, "mandatoryStages");
            Set<EvidenceStage> completed = immutableStages(
                completedStages, "completedStages");
            String hash = AutonomousResearchBrief.hash(
                branchId + '|' + familyId + '|' + status.name() + '|'
                    + mandatory.stream().map(Enum::name).sorted().toList() + '|'
                    + completed.stream().map(Enum::name).sorted().toList() + '|'
                    + supportDiversity + '|' + counterexampleRiskPermille + '|'
                    + interestingnessPermille);
            return new BranchSnapshot(
                branchId,
                familyId,
                status,
                mandatory,
                completed,
                supportDiversity,
                counterexampleRiskPermille,
                interestingnessPermille,
                hash);
        }

        boolean mandatoryEvidenceComplete() {
            return completedStages.containsAll(mandatoryStages);
        }

        int missingMandatoryStageCount() {
            return (int) mandatoryStages.stream()
                .filter(stage -> !completedStages.contains(stage))
                .count();
        }

        Optional<EvidenceStage> nextMissingStage(Set<EvidenceStage> enabledStages) {
            return mandatoryStages.stream()
                .filter(enabledStages::contains)
                .filter(stage -> !completedStages.contains(stage))
                .sorted()
                .findFirst();
        }
    }

    public record BudgetDelta(ResourceKind resource, long amount) {
        public BudgetDelta {
            Objects.requireNonNull(resource, "resource");
            if (amount <= 0L) {
                throw new IllegalArgumentException("planned budget amount must be positive");
            }
        }
    }

    public record AllocationDecision(
        String branchId,
        String branchSnapshotHash,
        DecisionKind kind,
        String stage,
        List<BudgetDelta> budgetDeltas,
        String reason,
        List<String> constraints
    ) {
        public AllocationDecision {
            requireText(branchId, "branchId");
            requireSha256(branchSnapshotHash, "branchSnapshotHash");
            kind = Objects.requireNonNull(kind, "kind");
            requireText(stage, "stage");
            budgetDeltas = budgetDeltas == null
                ? List.of()
                : budgetDeltas.stream()
                    .sorted(Comparator.comparing(BudgetDelta::resource))
                    .toList();
            requireText(reason, "reason");
            constraints = constraints == null
                ? List.of()
                : constraints.stream().filter(Objects::nonNull).distinct().sorted().toList();
            if (kind == DecisionKind.ALLOCATE && budgetDeltas.isEmpty()) {
                throw new IllegalArgumentException("allocation requires budget deltas");
            }
            if (kind != DecisionKind.ALLOCATE && !budgetDeltas.isEmpty()) {
                throw new IllegalArgumentException("non-allocation decision cannot consume budget");
            }
        }

        static AllocationDecision allocate(
            BranchSnapshot branch,
            EvidenceStage stage,
            List<BudgetDelta> deltas,
            String reason
        ) {
            return new AllocationDecision(
                branch.branchId(),
                branch.snapshotHash(),
                DecisionKind.ALLOCATE,
                stage.name(),
                deltas,
                reason,
                List.of("hard-eligibility-preserved", "no-target-input"));
        }

        static AllocationDecision stop(
            BranchSnapshot branch,
            String reason,
            List<String> constraints
        ) {
            return new AllocationDecision(
                branch.branchId(),
                branch.snapshotHash(),
                DecisionKind.STOP,
                "NOT_APPLICABLE",
                List.of(),
                reason,
                constraints);
        }

        static AllocationDecision noAction(BranchSnapshot branch, String reason) {
            return new AllocationDecision(
                branch.branchId(),
                branch.snapshotHash(),
                DecisionKind.NO_ACTION,
                "NOT_APPLICABLE",
                List.of(),
                reason,
                List.of("planner-does-not-accept-or-promote"));
        }

        String canonicalMaterial() {
            return branchId + '|' + branchSnapshotHash + '|' + kind.name() + '|'
                + stage + '|' + budgetDeltas + '|' + reason + '|' + constraints;
        }
    }

    public record PlannedBudget(
        EvidenceStage stage,
        ResourceKind resource,
        long amount
    ) {
        public PlannedBudget {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(resource, "resource");
            if (amount <= 0L) {
                throw new IllegalArgumentException("planned amount must be positive");
            }
        }

        String canonicalMaterial() {
            return stage.name() + '|' + resource.name() + '|' + amount;
        }
    }

    public record AllocationPlan(
        String schema,
        String briefHash,
        String ledgerHash,
        AllocationPolicy policy,
        List<AllocationDecision> decisions,
        List<PlannedBudget> plannedBudget,
        boolean plannerDecisionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public AllocationPlan {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported campaign plan schema");
            }
            requireSha256(briefHash, "briefHash");
            requireSha256(ledgerHash, "ledgerHash");
            policy = Objects.requireNonNull(policy, "policy");
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            plannedBudget = plannedBudget == null
                ? List.of()
                : plannedBudget.stream()
                    .sorted(Comparator.comparing(PlannedBudget::stage)
                        .thenComparing(PlannedBudget::resource))
                    .toList();
            if (plannerDecisionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "planner decisions cannot be mathematical evidence");
            }
            if (!"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "planner cannot perform promotion or public evidence");
            }
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("ledgerHash", ledgerHash)
                .property("policy", policy.name())
                .property("plannerDecisionIsMathematicalEvidence",
                    plannerDecisionIsMathematicalEvidence)
                .array("decisions", array -> decisions.forEach(decision ->
                    array.objectValue(object -> object
                        .property("branchId", decision.branchId())
                        .property("branchSnapshotHash", decision.branchSnapshotHash())
                        .property("kind", decision.kind().name())
                        .property("stage", decision.stage())
                        .array("budgetDeltas", deltas -> decision.budgetDeltas()
                            .forEach(delta -> deltas.objectValue(item -> item
                                .property("resource", delta.resource().name())
                                .property("amount", delta.amount()))))
                        .property("reason", decision.reason())
                        .stringArray("constraints", decision.constraints()))))
                .array("plannedBudget", array -> plannedBudget.forEach(item ->
                    array.objectValue(object -> object
                        .property("stage", item.stage().name())
                        .property("resource", item.resource().name())
                        .property("amount", item.amount()))))
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private record LineKey(EvidenceStage stage, ResourceKind resource)
            implements Comparable<LineKey> {
        LineKey {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(resource, "resource");
        }

        @Override
        public int compareTo(LineKey other) {
            int stageOrder = stage.compareTo(other.stage);
            return stageOrder != 0 ? stageOrder : resource.compareTo(other.resource);
        }
    }

    private static Set<EvidenceStage> immutableStages(
        Set<EvidenceStage> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not be empty or contain null");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
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

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }
}
