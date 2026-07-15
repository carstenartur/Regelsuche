package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousCampaignArtifactsV2.BranchLineageArtifact;
import de.regelsuche.experiments.autopilot.AutonomousCampaignArtifactsV2.CampaignExecution;
import de.regelsuche.experiments.autopilot.AutonomousCampaignArtifactsV2.CampaignPlan;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDecision;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateReceipt;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.EvidenceDagSnapshot;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GeneratedObservation;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GenerationRun;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.StateSnapshot;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvidence.CampaignContext;
import de.regelsuche.mining.OpenTargetConjectureEvidence.SeedProvenance;
import de.regelsuche.mining.OpenTargetConjectureMiner;
import de.regelsuche.mining.OpenTargetConjectureMiner.MiningReport;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetObservation;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Executes production candidate formation from immutable generation snapshots. */
public final class AutonomousProductionMiningRunner {
    public static final String FORMATION_RECEIPT_SCHEMA =
        "regelsuche.autonomous-candidate-formation-receipt/v2";
    public static final String MINING_RUN_SCHEMA =
        "regelsuche.autonomous-production-mining/v2";
    private static final String FULL_DECISION_ID = "production-mining-full-batch";
    private static final String REJECTION_DECISION_ID =
        "production-mining-alpha-rejection-batch";
    private static final String PRODUCER_VERSION =
        "regelsuche-autopilot-production-mining/v2";

    public MiningRun runPinned(int parallelism) {
        return run(new AutonomousProductionGenerationRunner().runPinned(parallelism));
    }

    public MiningRun run(GenerationRun generation) {
        Objects.requireNonNull(generation, "generation");
        var brief = generation.brief();
        long candidateCapacity = brief.budget(EvidenceStage.CANDIDATE_FORMATION)
            .configured(ResourceKind.CANDIDATES);
        long batchCapacity = brief.budget(EvidenceStage.CANDIDATE_FORMATION)
            .configured(ResourceKind.MINING_BATCHES);
        if (candidateCapacity < 2L || batchCapacity < 2L) {
            throw new IllegalArgumentException(
                "production mining requires two batches and candidate capacity for both");
        }

        List<GeneratedObservation> fullInputs = generation.observations().stream()
            .sorted(Comparator.comparing(item -> item.snapshot().observationId()))
            .toList();
        List<GeneratedObservation> rejectionInputs = rejectionInputs(fullInputs);
        AggregateDecision fullDecision = AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            FULL_DECISION_ID,
            fullInputs.stream().map(GeneratedObservation::branch).toList(),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, candidateCapacity - 1L),
            "mine all pinned cross-family production observations");
        AggregateDecision rejectionDecision = AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            REJECTION_DECISION_ID,
            rejectionInputs.stream().map(GeneratedObservation::branch).toList(),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            "execute the predeclared alpha-equivalent cross-family rejection batch");
        CampaignPlan plan = AutonomousCampaignArtifactsV2.plan(
            brief, List.of(fullDecision, rejectionDecision));

        MiningBatch fullBatch = mineBatch(brief, plan, fullDecision, fullInputs);
        MiningBatch rejectionBatch = mineBatch(
            brief, plan, rejectionDecision, rejectionInputs);
        validatePinnedOutcomes(fullBatch, rejectionBatch);

        CandidateFormationReceipt formationReceipt = formationReceipt(
            brief, List.of(fullBatch, rejectionBatch));
        EvidenceDagSnapshot dag = AutonomousEvidenceDagV2.snapshot(
            brief,
            generation.observationBranches(),
            List.of(fullDecision, rejectionDecision),
            List.of(fullBatch.binding().receipt(), rejectionBatch.binding().receipt()));
        String contentHash = AutonomousResearchBriefV2.hash(
            MINING_RUN_SCHEMA
                + "\ngeneration=" + generation.contentHash()
                + "\nplan=" + plan.contentHash()
                + "\nfull=" + fullBatch.canonicalMaterial()
                + "\nrejection=" + rejectionBatch.canonicalMaterial()
                + "\nformationReceipt=" + formationReceipt.contentHash()
                + "\ndag=" + dag.contentHash());
        return new MiningRun(
            MINING_RUN_SCHEMA,
            generation,
            plan,
            fullBatch,
            rejectionBatch,
            formationReceipt,
            dag,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public void write(Path outputDirectory, MiningRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            new AutonomousProductionGenerationRunner().write(
                outputDirectory, run.generation());
            write(outputDirectory.resolve("plan-v2.json"), run.plan().toCanonicalJson());
            writeBatch(outputDirectory, "full", run.fullBatch());
            writeBatch(outputDirectory, "rejection", run.rejectionBatch());
            write(outputDirectory.resolve("candidate-formation-receipt.json"),
                run.formationReceipt().toCanonicalJson());
            write(outputDirectory.resolve("evidence-dag.json"),
                run.dag().toCanonicalJson());
            write(outputDirectory.resolve("production-mining-run.json"),
                run.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write production mining evidence", exception);
        }
    }

    private static void writeBatch(
        Path outputDirectory,
        String name,
        MiningBatch batch
    ) throws IOException {
        write(outputDirectory.resolve(name + "-decision.json"),
            batch.decision().toCanonicalJson());
        write(outputDirectory.resolve(name + "-mining-evidence.json"),
            batch.evidence().toJson());
        write(outputDirectory.resolve(name + "-binding.json"),
            batch.binding().toCanonicalJson());
        write(outputDirectory.resolve(name + "-receipt.json"),
            batch.binding().receipt().toCanonicalJson());
        write(outputDirectory.resolve(name + "-execution-v2.json"),
            batch.execution().toCanonicalJson());
        write(outputDirectory.resolve(name + "-lineage-v2.json"),
            batch.lineage().toCanonicalJson());
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static MiningBatch mineBatch(
        AutonomousResearchBriefV2 brief,
        CampaignPlan plan,
        AggregateDecision decision,
        List<GeneratedObservation> inputs
    ) {
        OpenTargetConjectureMiner miner = new OpenTargetConjectureMiner();
        List<OpenTargetObservation> replayed = inputs.stream()
            .map(AutonomousProductionMiningRunner::replayObservation)
            .toList();
        MiningReport report = miner.mine(replayed);
        MiningReport liveReport = miner.mine(inputs.stream()
            .map(item -> OpenTargetObservation.from(
                item.snapshot().observationId(),
                item.branch().familyId(),
                item.seed().expression(),
                item.searchResult()))
            .toList());
        if (!report.equals(liveReport)) {
            throw new IllegalStateException(
                "immutable observation replay changed production mining evidence");
        }
        OpenTargetConjectureEvidence evidence = new OpenTargetConjectureEvidence(
            context(brief, inputs), report);
        long candidateCapacity = decision.plannedResources()
            .getOrDefault(ResourceKind.CANDIDATES, 0L);
        long produced = report.conjectures().size();
        if (produced > candidateCapacity) {
            throw new IllegalStateException(
                "production miner exceeded the planned candidate capacity");
        }
        Map<ResourceKind, Long> executed = produced == 0L
            ? Map.of(ResourceKind.MINING_BATCHES, 1L)
            : Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, produced);
        long skippedCandidates = candidateCapacity - produced;
        Map<ResourceKind, Long> skipped = skippedCandidates == 0L
            ? Map.of()
            : Map.of(ResourceKind.CANDIDATES, skippedCandidates);
        var binding = OpenTargetAutopilotV2Binding.completeCandidateFormation(
            brief, decision, evidence, executed, skipped);
        CampaignExecution execution = AutonomousCampaignArtifactsV2.execution(
            plan, binding.receipt());
        BranchLineageArtifact lineage = AutonomousCampaignArtifactsV2.lineage(
            binding.receipt());
        return new MiningBatch(
            decision, evidence, binding, execution, lineage);
    }

    private static CampaignContext context(
        AutonomousResearchBriefV2 brief,
        List<GeneratedObservation> inputs
    ) {
        List<SeedProvenance> seeds = inputs.stream()
            .map(item -> new SeedProvenance(
                item.snapshot().observationId(),
                item.snapshot().seedId(),
                item.snapshot().generator(),
                Map.of(
                    "domain", item.snapshot().domain(),
                    "seedExpressionHash", AutonomousResearchBriefV2.hash(
                        item.snapshot().seedExpression()),
                    "snapshotHash", item.snapshot().snapshotHash())))
            .toList();
        return new CampaignContext(
            PinnedAutonomousProductionCampaign.CAMPAIGN_ID,
            PRODUCER_VERSION,
            brief.modelHash(),
            brief.inventoryHash(),
            PinnedAutonomousProductionCampaign.searchHeuristic(),
            seeds);
    }

    private static OpenTargetObservation replayObservation(
        GeneratedObservation item
    ) {
        return new OpenTargetObservation(
            item.snapshot().observationId(),
            item.branch().familyId(),
            item.snapshot().seedExpression(),
            GoalStatus.valueOf(item.snapshot().searchStatus()),
            item.snapshot().states().stream()
                .map(AutonomousProductionMiningRunner::replayState)
                .toList());
    }

    private static SearchState replayState(StateSnapshot state) {
        return new SearchState(
            state.expression(),
            state.depth(),
            new ExpressionScore(state.scoreWeightedTotal(), 0, 0, 0, 0),
            state.path(),
            state.appliedRuleIds(),
            Set.of(),
            0,
            state.canonicalHash(),
            null,
            null,
            null,
            false,
            0,
            state.equivalencePreservingFlags().stream()
                .allMatch(Boolean::booleanValue),
            0,
            List.of(),
            state.equivalencePreservingFlags(),
            state.assumptions());
    }

    private static List<GeneratedObservation> rejectionInputs(
        List<GeneratedObservation> observations
    ) {
        Set<String> required = Set.copyOf(
            PinnedAutonomousProductionCampaign.alphaRejectionSeedIds());
        List<GeneratedObservation> result = observations.stream()
            .filter(item -> required.contains(item.seed().id()))
            .sorted(Comparator.comparing(item -> item.seed().id()))
            .toList();
        if (result.size() != required.size()
                || result.stream().map(item -> item.branch().familyId())
                    .distinct().count() != 2L) {
            throw new IllegalStateException(
                "pinned alpha-rejection batch must retain one seed from each family");
        }
        return result;
    }

    private static void validatePinnedOutcomes(
        MiningBatch full,
        MiningBatch rejection
    ) {
        if (full.evidence().report().conjectures().isEmpty()
                || full.binding().receipt().outputs().isEmpty()) {
            throw new IllegalStateException(
                "full production batch must retain at least one candidate");
        }
        boolean crossFamily = full.binding().receipt().outputs().stream()
            .anyMatch(output -> output.sources().stream()
                .map(AutonomousEvidenceDagV2.SourceLink::familyId)
                .distinct().count() >= 2L);
        if (!crossFamily) {
            throw new IllegalStateException(
                "production candidate must retain exact support from both configured families");
        }
        if (!rejection.evidence().report().conjectures().isEmpty()
                || !rejection.binding().receipt().outputs().isEmpty()
                || rejection.evidence().report().rejectedClusters().stream()
                    .noneMatch(cluster -> "alpha-distinct-support<2"
                        .equals(cluster.reason()))) {
            throw new IllegalStateException(
                "predeclared alpha-equivalent batch must terminate as rejected evidence");
        }
    }

    private static CandidateFormationReceipt formationReceipt(
        AutonomousResearchBriefV2 brief,
        List<MiningBatch> batches
    ) {
        Map<ResourceKind, Long> configured = brief.budget(
            EvidenceStage.CANDIDATE_FORMATION).resources();
        EnumMap<ResourceKind, Long> executed = new EnumMap<>(ResourceKind.class);
        EnumMap<ResourceKind, Long> skipped = new EnumMap<>(ResourceKind.class);
        batches.forEach(batch -> {
            add(executed, batch.binding().receipt().executedResources());
            add(skipped, batch.binding().receipt().skippedResources());
        });
        Map<ResourceKind, Long> remaining = remaining(configured, executed, skipped);
        List<String> decisionHashes = batches.stream()
            .map(batch -> batch.decision().contentHash()).sorted().toList();
        List<String> miningHashes = batches.stream()
            .map(batch -> batch.evidence().contentHash()).sorted().toList();
        List<String> receiptHashes = batches.stream()
            .map(batch -> batch.binding().receipt().contentHash()).sorted().toList();
        List<String> outputBranches = batches.stream()
            .flatMap(batch -> batch.binding().receipt().outputs().stream())
            .map(AutonomousEvidenceDagV2.CandidateOutput::outputBranchId)
            .sorted().toList();
        List<String> rejected = batches.stream()
            .flatMap(batch -> batch.binding().receipt().rejectedClusters().stream())
            .map(AutonomousEvidenceDagV2.RejectedCluster::contentHash)
            .sorted().toList();
        String contentHash = AutonomousResearchBriefV2.hash(
            FORMATION_RECEIPT_SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\nconfigured=" + configured
                + "\nexecuted=" + executed
                + "\nskipped=" + skipped
                + "\nremaining=" + remaining
                + "\ndecisions=" + decisionHashes
                + "\nmining=" + miningHashes
                + "\nreceipts=" + receiptHashes
                + "\noutputs=" + outputBranches
                + "\nrejected=" + rejected);
        return new CandidateFormationReceipt(
            FORMATION_RECEIPT_SCHEMA,
            brief.contentHash(),
            EvidenceStage.CANDIDATE_FORMATION,
            "COMPLETED",
            configured,
            executed,
            skipped,
            remaining,
            decisionHashes,
            miningHashes,
            receiptHashes,
            outputBranches,
            rejected,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static void add(
        EnumMap<ResourceKind, Long> target,
        Map<ResourceKind, Long> values
    ) {
        values.forEach((resource, amount) -> target.merge(
            resource, amount, Math::addExact));
    }

    private static Map<ResourceKind, Long> remaining(
        Map<ResourceKind, Long> configured,
        Map<ResourceKind, Long> executed,
        Map<ResourceKind, Long> skipped
    ) {
        EnumMap<ResourceKind, Long> result = new EnumMap<>(ResourceKind.class);
        for (Map.Entry<ResourceKind, Long> entry : configured.entrySet()) {
            long consumed = Math.addExact(
                executed.getOrDefault(entry.getKey(), 0L),
                skipped.getOrDefault(entry.getKey(), 0L));
            if (consumed > entry.getValue()) {
                throw new IllegalArgumentException(
                    "candidate formation exceeds configured resource " + entry.getKey());
            }
            long amount = entry.getValue() - consumed;
            if (amount > 0L) {
                result.put(entry.getKey(), amount);
            }
        }
        Set<ResourceKind> unexpected = new HashSet<>(executed.keySet());
        unexpected.addAll(skipped.keySet());
        unexpected.removeAll(configured.keySet());
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                "candidate formation reported unconfigured resources: " + unexpected);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<ResourceKind, Long> immutableResources(
        Map<ResourceKind, Long> supplied
    ) {
        EnumMap<ResourceKind, Long> result = new EnumMap<>(ResourceKind.class);
        supplied.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, "resource");
            if (amount == null || amount < 0L) {
                throw new IllegalArgumentException(
                    "resource amounts must be non-negative");
            }
            if (amount > 0L) {
                result.put(resource, amount);
            }
        });
        return Collections.unmodifiableMap(result);
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

    public record MiningBatch(
        AggregateDecision decision,
        OpenTargetConjectureEvidence evidence,
        OpenTargetAutopilotV2Binding.BindingResult binding,
        CampaignExecution execution,
        BranchLineageArtifact lineage
    ) {
        public MiningBatch {
            decision = Objects.requireNonNull(decision, "decision");
            evidence = Objects.requireNonNull(evidence, "evidence");
            binding = Objects.requireNonNull(binding, "binding");
            execution = Objects.requireNonNull(execution, "execution");
            lineage = Objects.requireNonNull(lineage, "lineage");
            if (!decision.contentHash().equals(binding.decisionHash())
                    || !evidence.contentHash().equals(binding.miningEvidenceHash())
                    || !binding.receipt().contentHash()
                        .equals(execution.aggregateReceiptHash())
                    || !binding.receipt().contentHash()
                        .equals(lineage.aggregateReceiptHash())) {
                throw new IllegalArgumentException(
                    "production mining batch artifacts are not hash-linked");
            }
        }

        String canonicalMaterial() {
            return decision.contentHash() + '|' + evidence.contentHash() + '|'
                + binding.contentHash() + '|' + binding.receipt().contentHash() + '|'
                + execution.contentHash() + '|' + lineage.contentHash();
        }
    }

    public record CandidateFormationReceipt(
        String schema,
        String briefHash,
        EvidenceStage stage,
        String disposition,
        Map<ResourceKind, Long> configuredResources,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources,
        Map<ResourceKind, Long> remainingResources,
        List<String> decisionHashes,
        List<String> miningEvidenceHashes,
        List<String> aggregateReceiptHashes,
        List<String> outputBranchIds,
        List<String> rejectedClusterHashes,
        boolean targetProvided,
        boolean receiptIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CandidateFormationReceipt {
            if (!FORMATION_RECEIPT_SCHEMA.equals(schema)
                    || stage != EvidenceStage.CANDIDATE_FORMATION
                    || !"COMPLETED".equals(disposition)) {
                throw new IllegalArgumentException(
                    "unsupported or incomplete candidate-formation receipt");
            }
            requireSha256(briefHash, "briefHash");
            configuredResources = immutableResources(configuredResources);
            executedResources = immutableResources(executedResources);
            skippedResources = immutableResources(skippedResources);
            remainingResources = immutableResources(remainingResources);
            decisionHashes = sortedHashes(decisionHashes, "decisionHashes");
            miningEvidenceHashes = sortedHashes(
                miningEvidenceHashes, "miningEvidenceHashes");
            aggregateReceiptHashes = sortedHashes(
                aggregateReceiptHashes, "aggregateReceiptHashes");
            outputBranchIds = sortedText(outputBranchIds, "outputBranchIds");
            rejectedClusterHashes = sortedHashes(
                rejectedClusterHashes, "rejectedClusterHashes");
            validateBalance(
                configuredResources,
                executedResources,
                skippedResources,
                remainingResources);
            if (decisionHashes.size() < 2
                    || miningEvidenceHashes.size() != decisionHashes.size()
                    || aggregateReceiptHashes.size() != decisionHashes.size()
                    || executedResources.getOrDefault(
                        ResourceKind.MINING_BATCHES, 0L) != decisionHashes.size()
                    || executedResources.getOrDefault(
                        ResourceKind.CANDIDATES, 0L) != outputBranchIds.size()
                    || outputBranchIds.isEmpty()
                    || rejectedClusterHashes.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate formation must retain factual batches, outputs and rejection evidence");
            }
            if (targetProvided || receiptIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "candidate-formation receipt must remain target-free and non-mathematical");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public long executed(ResourceKind resource) {
            return executedResources.getOrDefault(resource, 0L);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("stage", stage.name())
                .property("disposition", disposition)
                .array("resources", array -> configuredResources.keySet().stream()
                    .sorted()
                    .forEach(resource -> array.objectValue(object -> object
                        .property("resource", resource.name())
                        .property("configured",
                            configuredResources.getOrDefault(resource, 0L))
                        .property("executed",
                            executedResources.getOrDefault(resource, 0L))
                        .property("skipped",
                            skippedResources.getOrDefault(resource, 0L))
                        .property("remaining",
                            remainingResources.getOrDefault(resource, 0L)))))
                .stringArray("decisionHashes", decisionHashes)
                .stringArray("miningEvidenceHashes", miningEvidenceHashes)
                .stringArray("aggregateReceiptHashes", aggregateReceiptHashes)
                .stringArray("outputBranchIds", outputBranchIds)
                .stringArray("rejectedClusterHashes", rejectedClusterHashes)
                .property("targetProvided", targetProvided)
                .property("receiptIsMathematicalEvidence",
                    receiptIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        private static void validateBalance(
            Map<ResourceKind, Long> configured,
            Map<ResourceKind, Long> executed,
            Map<ResourceKind, Long> skipped,
            Map<ResourceKind, Long> remaining
        ) {
            Set<ResourceKind> resources = new TreeSet<>();
            resources.addAll(configured.keySet());
            resources.addAll(executed.keySet());
            resources.addAll(skipped.keySet());
            resources.addAll(remaining.keySet());
            for (ResourceKind resource : resources) {
                long accounted = Math.addExact(
                    Math.addExact(
                        executed.getOrDefault(resource, 0L),
                        skipped.getOrDefault(resource, 0L)),
                    remaining.getOrDefault(resource, 0L));
                if (configured.getOrDefault(resource, 0L) != accounted) {
                    throw new IllegalArgumentException(
                        "unbalanced candidate-formation resource " + resource);
                }
            }
        }
    }

    public record MiningRun(
        String schema,
        GenerationRun generation,
        CampaignPlan plan,
        MiningBatch fullBatch,
        MiningBatch rejectionBatch,
        CandidateFormationReceipt formationReceipt,
        EvidenceDagSnapshot dag,
        boolean targetProvided,
        boolean miningRunIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public MiningRun {
            if (!MINING_RUN_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production mining schema");
            }
            generation = Objects.requireNonNull(generation, "generation");
            plan = Objects.requireNonNull(plan, "plan");
            fullBatch = Objects.requireNonNull(fullBatch, "fullBatch");
            rejectionBatch = Objects.requireNonNull(
                rejectionBatch, "rejectionBatch");
            formationReceipt = Objects.requireNonNull(
                formationReceipt, "formationReceipt");
            dag = Objects.requireNonNull(dag, "dag");
            List<MiningBatch> batches = List.of(fullBatch, rejectionBatch);
            Set<String> decisionHashes = batches.stream()
                .map(batch -> batch.decision().contentHash())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<String> miningHashes = batches.stream()
                .map(batch -> batch.evidence().contentHash())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<String> receiptHashes = batches.stream()
                .map(batch -> batch.binding().receipt().contentHash())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<String> outputBranchIds = batches.stream()
                .flatMap(batch -> batch.binding().receipt().outputs().stream())
                .map(AutonomousEvidenceDagV2.CandidateOutput::outputBranchId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<String> rejectedHashes = batches.stream()
                .flatMap(batch -> batch.binding().receipt().rejectedClusters().stream())
                .map(AutonomousEvidenceDagV2.RejectedCluster::contentHash)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<String> dagDecisionHashes = dag.decisions().stream()
                .map(AggregateDecision::contentHash)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<String> dagReceiptHashes = dag.receipts().stream()
                .map(AggregateReceipt::contentHash)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (!generation.brief().contentHash().equals(plan.briefHash())
                    || !generation.brief().contentHash().equals(dag.briefHash())
                    || !generation.brief().contentHash()
                        .equals(formationReceipt.briefHash())
                    || !plan.contentHash().equals(fullBatch.execution().planHash())
                    || !plan.contentHash().equals(rejectionBatch.execution().planHash())
                    || !decisionHashes.equals(new TreeSet<>(
                        formationReceipt.decisionHashes()))
                    || !miningHashes.equals(new TreeSet<>(
                        formationReceipt.miningEvidenceHashes()))
                    || !receiptHashes.equals(new TreeSet<>(
                        formationReceipt.aggregateReceiptHashes()))
                    || !outputBranchIds.equals(new TreeSet<>(
                        formationReceipt.outputBranchIds()))
                    || !rejectedHashes.equals(new TreeSet<>(
                        formationReceipt.rejectedClusterHashes()))
                    || !decisionHashes.equals(dagDecisionHashes)
                    || !receiptHashes.equals(dagReceiptHashes)
                    || targetProvided
                    || miningRunIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "production mining manifest is inconsistent or not target-free");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public int candidateCount() {
            return fullBatch.binding().receipt().outputs().size()
                + rejectionBatch.binding().receipt().outputs().size();
        }

        public int rejectedClusterCount() {
            return fullBatch.binding().receipt().rejectedClusters().size()
                + rejectionBatch.binding().receipt().rejectedClusters().size();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", generation.brief().contentHash())
                .property("generationRunHash", generation.contentHash())
                .property("planHash", plan.contentHash())
                .property("fullDecisionHash", fullBatch.decision().contentHash())
                .property("fullMiningEvidenceHash", fullBatch.evidence().contentHash())
                .property("fullBindingHash", fullBatch.binding().contentHash())
                .property("fullReceiptHash",
                    fullBatch.binding().receipt().contentHash())
                .property("fullExecutionHash", fullBatch.execution().contentHash())
                .property("fullLineageHash", fullBatch.lineage().contentHash())
                .property("rejectionDecisionHash",
                    rejectionBatch.decision().contentHash())
                .property("rejectionMiningEvidenceHash",
                    rejectionBatch.evidence().contentHash())
                .property("rejectionBindingHash",
                    rejectionBatch.binding().contentHash())
                .property("rejectionReceiptHash",
                    rejectionBatch.binding().receipt().contentHash())
                .property("rejectionExecutionHash",
                    rejectionBatch.execution().contentHash())
                .property("rejectionLineageHash",
                    rejectionBatch.lineage().contentHash())
                .property("candidateFormationReceiptHash",
                    formationReceipt.contentHash())
                .property("evidenceDagHash", dag.contentHash())
                .property("candidateCount", candidateCount())
                .property("rejectedClusterCount", rejectedClusterCount())
                .property("targetProvided", targetProvided)
                .property("miningRunIsMathematicalEvidence",
                    miningRunIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static List<String> sortedHashes(List<String> values, String name) {
        List<String> result = values == null ? List.of() : values.stream().sorted().toList();
        result.forEach(value -> requireSha256(value, name));
        if (new TreeSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return result;
    }

    private static List<String> sortedText(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return values.stream().distinct().sorted().toList();
    }
}
