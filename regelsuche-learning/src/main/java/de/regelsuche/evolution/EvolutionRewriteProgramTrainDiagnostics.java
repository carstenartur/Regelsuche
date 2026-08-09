package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationAttempt;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationBatch;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationStatus;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.GenerationReport;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.LineageEdge;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationRun;
import de.regelsuche.evolution.EvolutionRewriteProgramStructureAnalyzer.ProgramFacts;
import de.regelsuche.evolution.StratifiedMutationKindRewriteProgramMutator.ObservedBatch;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content-addressed TRAIN-only diagnostics for rewrite-program population runs.
 *
 * <p>The artifact is intentionally separate from {@code PopulationRun/v1}. It
 * therefore adds observability for a new execution protocol without changing
 * the bytes or meaning of historical population evidence. The artifact binds
 * the execution plan, execution protocol and completed execution-bound run and
 * embeds every observed mutation batch as canonical JSON.</p>
 */
public record EvolutionRewriteProgramTrainDiagnostics(
    String schema,
    String studyPlanHash,
    String executionPlanHash,
    String executionProtocolHash,
    String executionBoundRunHash,
    String populationRunHash,
    List<ObservedMutationBatch> mutationBatches,
    List<GenerationDiagnostics> generations,
    List<CandidateStructureFacts> candidateStructures,
    String dataScope,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-train-diagnostics/v1";
    public static final String DATA_SCOPE = "TRAIN_ONLY";
    private static final String MAX_ACCEPTED_BLOCKER =
        "ACCEPTED_BUDGET_EXHAUSTED:maxAccepted";

    public EvolutionRewriteProgramTrainDiagnostics {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program TRAIN diagnostics schema");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(executionPlanHash, "executionPlanHash");
        EvolutionGenome.requireSha256(
            executionProtocolHash, "executionProtocolHash");
        EvolutionGenome.requireSha256(
            executionBoundRunHash, "executionBoundRunHash");
        EvolutionGenome.requireSha256(populationRunHash, "populationRunHash");
        mutationBatches = canonicalBatches(mutationBatches);
        generations = canonicalGenerations(generations);
        candidateStructures = canonicalCandidates(candidateStructures);
        if (!DATA_SCOPE.equals(dataScope)) {
            throw new IllegalArgumentException(
                "rewrite-program diagnostics must remain TRAIN_ONLY");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyPlanHash,
            executionPlanHash,
            executionProtocolHash,
            executionBoundRunHash,
            populationRunHash,
            mutationBatches,
            generations,
            candidateStructures,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "rewrite-program TRAIN diagnostics contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramTrainDiagnostics create(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol protocol,
        ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun boundRun,
        List<EvolutionRewriteProgramCandidate> seeds,
        List<ObservedBatch> observedBatches
    ) {
        Objects.requireNonNull(executionPlan, "executionPlan");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(boundRun, "boundRun");
        Objects.requireNonNull(seeds, "seeds");
        Objects.requireNonNull(observedBatches, "observedBatches");
        boundRun.requireCompatible(executionPlan, protocol);
        PopulationRun populationRun = boundRun.retainedRun()
            .retainedPopulation().populationRun();
        if (!executionPlan.studyPlanHash().equals(populationRun.studyPlanHash())) {
            throw new IllegalArgumentException(
                "population run study differs from execution plan");
        }

        List<ObservedMutationBatch> batches = observedBatches.stream()
            .map(ObservedMutationBatch::from)
            .sorted(Comparator
                .comparingInt(ObservedMutationBatch::generation)
                .thenComparing(ObservedMutationBatch::parentCandidateHash)
                .thenComparing(ObservedMutationBatch::mutationBatchHash))
            .toList();
        Map<String, EvolutionRewriteProgramPlan> plansByHash = plansByHash(
            seeds, observedBatches);
        Map<String, CandidateStructureFacts> structures = seedStructures(seeds);
        Map<String, Integer> lineageDepths = new LinkedHashMap<>();
        seeds.forEach(seed -> lineageDepths.put(seed.contentHash(), 0));

        List<GenerationDiagnostics> generationDiagnostics = new ArrayList<>();
        for (GenerationReport report : populationRun.generationReports()) {
            List<ObservedBatch> generationBatches = observedBatches.stream()
                .filter(batch -> batch.generation() == report.generation())
                .toList();
            Map<EvolutionRewriteProgramMutationKind, Integer> eligible =
                emptyCounts();
            Map<EvolutionRewriteProgramMutationKind, Integer> budgetOnly =
                emptyCounts();
            for (ObservedBatch observed : generationBatches) {
                for (MutationAttempt attempt : observed.batch().attempts()) {
                    if (isEligibleProposal(attempt)) {
                        increment(eligible, attempt.kind());
                    }
                    if (isMaxAcceptedOnlyRejection(attempt)) {
                        increment(budgetOnly, attempt.kind());
                    }
                }
            }

            Map<EvolutionRewriteProgramMutationKind, Integer> accepted =
                emptyCounts();
            Set<String> generatedAlpha = new TreeSet<>();
            Set<String> survivingGeneratedAlpha = new TreeSet<>();
            Set<String> selected = Set.copyOf(report.selectedCandidateHashes());
            int generationMaxDepth = 0;
            for (LineageEdge edge : report.lineage()) {
                increment(accepted, edge.mutationKind());
                generatedAlpha.add(edge.childAlphaStructuralHash());
                if (selected.contains(edge.childCandidateHash())) {
                    survivingGeneratedAlpha.add(edge.childAlphaStructuralHash());
                }
                Integer parentDepth = lineageDepths.get(edge.parentCandidateHash());
                if (parentDepth == null) {
                    throw new IllegalArgumentException(
                        "lineage parent is not reachable from a seed: "
                            + edge.parentCandidateHash());
                }
                int childDepth = Math.addExact(parentDepth, 1);
                lineageDepths.merge(
                    edge.childCandidateHash(), childDepth, Math::min);
                generationMaxDepth = Math.max(generationMaxDepth, childDepth);
                EvolutionRewriteProgramPlan plan = plansByHash.get(
                    edge.childPlanHash());
                if (plan == null) {
                    throw new IllegalArgumentException(
                        "lineage child plan is absent from observed mutation batches: "
                            + edge.childPlanHash());
                }
                CandidateStructureFacts facts = CandidateStructureFacts.create(
                    edge.childCandidateHash(),
                    edge.childAlphaStructuralHash(),
                    edge.childPlanHash(),
                    report.generation(),
                    childDepth,
                    plan);
                CandidateStructureFacts previous = structures.putIfAbsent(
                    facts.candidateHash(), facts);
                if (previous != null && !previous.equals(facts)) {
                    throw new IllegalArgumentException(
                        "candidate identity maps to different structure facts");
                }
            }
            generationDiagnostics.add(GenerationDiagnostics.create(
                report.generation(),
                eligible,
                accepted,
                budgetOnly,
                List.copyOf(generatedAlpha),
                List.copyOf(survivingGeneratedAlpha),
                generationMaxDepth));
        }

        List<CandidateStructureFacts> candidateFacts = structures.values().stream()
            .sorted(Comparator.comparing(CandidateStructureFacts::candidateHash))
            .toList();
        String hash = EvolutionGenome.hash(render(
            populationRun.studyPlanHash(),
            executionPlan.contentHash(),
            protocol.contentHash(),
            boundRun.contentHash(),
            populationRun.contentHash(),
            batches,
            generationDiagnostics,
            candidateFacts,
            null));
        return new EvolutionRewriteProgramTrainDiagnostics(
            SCHEMA,
            populationRun.studyPlanHash(),
            executionPlan.contentHash(),
            protocol.contentHash(),
            boundRun.contentHash(),
            populationRun.contentHash(),
            batches,
            generationDiagnostics,
            candidateFacts,
            DATA_SCOPE,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            studyPlanHash,
            executionPlanHash,
            executionProtocolHash,
            executionBoundRunHash,
            populationRunHash,
            mutationBatches,
            generations,
            candidateStructures,
            contentHash);
    }

    private static Map<String, EvolutionRewriteProgramPlan> plansByHash(
        List<EvolutionRewriteProgramCandidate> seeds,
        List<ObservedBatch> observedBatches
    ) {
        Map<String, EvolutionRewriteProgramPlan> plans = new LinkedHashMap<>();
        for (EvolutionRewriteProgramCandidate seed : seeds) {
            putPlan(plans, seed.plan());
        }
        for (ObservedBatch observed : observedBatches) {
            observed.batch().acceptedPlans().forEach(plan -> putPlan(plans, plan));
        }
        return plans;
    }

    private static void putPlan(
        Map<String, EvolutionRewriteProgramPlan> plans,
        EvolutionRewriteProgramPlan plan
    ) {
        EvolutionRewriteProgramPlan previous = plans.putIfAbsent(
            plan.contentHash(), plan);
        if (previous != null && !previous.equals(plan)) {
            throw new IllegalArgumentException(
                "plan hash identifies different payloads");
        }
    }

    private static Map<String, CandidateStructureFacts> seedStructures(
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
        Map<String, CandidateStructureFacts> result = new LinkedHashMap<>();
        for (EvolutionRewriteProgramCandidate seed : seeds) {
            CandidateStructureFacts facts = CandidateStructureFacts.create(
                seed.contentHash(),
                seed.alphaStructuralHash(),
                seed.plan().contentHash(),
                0,
                0,
                seed.plan());
            CandidateStructureFacts previous = result.putIfAbsent(
                facts.candidateHash(), facts);
            if (previous != null && !previous.equals(facts)) {
                throw new IllegalArgumentException(
                    "seed candidate identity maps to different structure facts");
            }
        }
        return result;
    }

    private static boolean isEligibleProposal(MutationAttempt attempt) {
        return attempt.status() == MutationStatus.ACCEPTED
            || isMaxAcceptedOnlyRejection(attempt);
    }

    private static boolean isMaxAcceptedOnlyRejection(MutationAttempt attempt) {
        return attempt.status() == MutationStatus.REJECTED
            && attempt.blockers().equals(List.of(MAX_ACCEPTED_BLOCKER));
    }

    private static Map<EvolutionRewriteProgramMutationKind, Integer> emptyCounts() {
        EnumMap<EvolutionRewriteProgramMutationKind, Integer> result =
            new EnumMap<>(EvolutionRewriteProgramMutationKind.class);
        for (EvolutionRewriteProgramMutationKind kind
                : EvolutionRewriteProgramMutationKind.values()) {
            result.put(kind, 0);
        }
        return result;
    }

    private static void increment(
        Map<EvolutionRewriteProgramMutationKind, Integer> counts,
        EvolutionRewriteProgramMutationKind kind
    ) {
        counts.compute(kind, (ignored, value) -> Math.addExact(value, 1));
    }

    private static List<ObservedMutationBatch> canonicalBatches(
        List<ObservedMutationBatch> values
    ) {
        Objects.requireNonNull(values, "mutationBatches");
        List<ObservedMutationBatch> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "mutation batch"))
            .sorted(Comparator
                .comparingInt(ObservedMutationBatch::generation)
                .thenComparing(ObservedMutationBatch::parentCandidateHash)
                .thenComparing(ObservedMutationBatch::mutationBatchHash))
            .toList();
        if (result.stream().map(ObservedMutationBatch::identityKey).distinct().count()
                != result.size()) {
            throw new IllegalArgumentException(
                "duplicate observed mutation batch identity");
        }
        return result;
    }

    private static List<GenerationDiagnostics> canonicalGenerations(
        List<GenerationDiagnostics> values
    ) {
        Objects.requireNonNull(values, "generations");
        List<GenerationDiagnostics> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "generation diagnostics"))
            .sorted(Comparator.comparingInt(GenerationDiagnostics::generation))
            .toList();
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).generation() != index + 1) {
                throw new IllegalArgumentException(
                    "generation diagnostics are incomplete or reordered");
            }
        }
        return result;
    }

    private static List<CandidateStructureFacts> canonicalCandidates(
        List<CandidateStructureFacts> values
    ) {
        Objects.requireNonNull(values, "candidateStructures");
        List<CandidateStructureFacts> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "candidate structure"))
            .sorted(Comparator.comparing(CandidateStructureFacts::candidateHash))
            .toList();
        if (result.stream().map(CandidateStructureFacts::candidateHash)
                .distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "duplicate candidate structure identity");
        }
        return result;
    }

    private static String render(
        String studyPlanHash,
        String executionPlanHash,
        String executionProtocolHash,
        String executionBoundRunHash,
        String populationRunHash,
        List<ObservedMutationBatch> mutationBatches,
        List<GenerationDiagnostics> generations,
        List<CandidateStructureFacts> candidateStructures,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyPlanHash", studyPlanHash)
            .property("executionPlanHash", executionPlanHash)
            .property("executionProtocolHash", executionProtocolHash)
            .property("executionBoundRunHash", executionBoundRunHash)
            .property("populationRunHash", populationRunHash)
            .array("mutationBatches", array -> mutationBatches.forEach(batch ->
                array.objectValue(object -> object
                    .property("generation", batch.generation())
                    .property("parentCandidateHash", batch.parentCandidateHash())
                    .property("mutationBatchHash", batch.mutationBatchHash())
                    .property("mutationBatchJson", batch.mutationBatchJson()))))
            .array("generations", array -> generations.forEach(generation ->
                array.objectValue(object -> object
                    .property("generation", generation.generation())
                    .object("eligibleProposalCountsByMutationKind", counts ->
                        writeCounts(counts, generation.eligibleProposalCountsByMutationKind()))
                    .object("acceptedOffspringCountsByMutationKind", counts ->
                        writeCounts(counts, generation.acceptedOffspringCountsByMutationKind()))
                    .object("maxAcceptedOnlyRejectionCountsByMutationKind", counts ->
                        writeCounts(counts, generation.maxAcceptedOnlyRejectionCountsByMutationKind()))
                    .stringArray("generatedAlphaStructuralHashes",
                        generation.generatedAlphaStructuralHashes())
                    .stringArray("survivingGeneratedAlphaStructuralHashes",
                        generation.survivingGeneratedAlphaStructuralHashes())
                    .property("maxLineageDepthFromSeed",
                        generation.maxLineageDepthFromSeed()))))
            .array("candidateStructures", array -> candidateStructures.forEach(facts ->
                array.objectValue(object -> object
                    .property("candidateHash", facts.candidateHash())
                    .property("alphaStructuralHash", facts.alphaStructuralHash())
                    .property("planHash", facts.planHash())
                    .property("firstSeenGeneration", facts.firstSeenGeneration())
                    .property("lineageDepthFromSeed", facts.lineageDepthFromSeed())
                    .property("nodeCount", facts.nodeCount())
                    .property("containsCompositionTopology",
                        facts.containsCompositionTopology())
                    .property("containsDecisionTopology",
                        facts.containsDecisionTopology())
                    .property("minimumStructuralPrimitivePathSteps",
                        facts.minimumStructuralPrimitivePathSteps()))))
            .property("dataScope", DATA_SCOPE);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeCounts(
        JsonWriter object,
        Map<EvolutionRewriteProgramMutationKind, Integer> counts
    ) {
        for (EvolutionRewriteProgramMutationKind kind
                : EvolutionRewriteProgramMutationKind.values()) {
            object.property(kind.name(), counts.get(kind));
        }
    }

    public record ObservedMutationBatch(
        int generation,
        String parentCandidateHash,
        String mutationBatchHash,
        String mutationBatchJson
    ) {
        public ObservedMutationBatch {
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            EvolutionGenome.requireSha256(
                parentCandidateHash, "parentCandidateHash");
            EvolutionGenome.requireSha256(
                mutationBatchHash, "mutationBatchHash");
            if (mutationBatchJson == null || mutationBatchJson.isBlank()) {
                throw new IllegalArgumentException(
                    "mutationBatchJson must not be blank");
            }
        }

        private static ObservedMutationBatch from(ObservedBatch observed) {
            MutationBatch batch = observed.batch();
            return new ObservedMutationBatch(
                observed.generation(),
                observed.parentCandidateHash(),
                batch.contentHash(),
                batch.toCanonicalJson());
        }

        private String identityKey() {
            return generation + "\n" + parentCandidateHash + "\n"
                + mutationBatchHash;
        }
    }

    public record GenerationDiagnostics(
        int generation,
        Map<EvolutionRewriteProgramMutationKind, Integer>
            eligibleProposalCountsByMutationKind,
        Map<EvolutionRewriteProgramMutationKind, Integer>
            acceptedOffspringCountsByMutationKind,
        Map<EvolutionRewriteProgramMutationKind, Integer>
            maxAcceptedOnlyRejectionCountsByMutationKind,
        List<String> generatedAlphaStructuralHashes,
        List<String> survivingGeneratedAlphaStructuralHashes,
        int maxLineageDepthFromSeed
    ) {
        public GenerationDiagnostics {
            if (generation < 1 || maxLineageDepthFromSeed < 0) {
                throw new IllegalArgumentException(
                    "invalid generation diagnostic counters");
            }
            eligibleProposalCountsByMutationKind = canonicalCounts(
                eligibleProposalCountsByMutationKind);
            acceptedOffspringCountsByMutationKind = canonicalCounts(
                acceptedOffspringCountsByMutationKind);
            maxAcceptedOnlyRejectionCountsByMutationKind = canonicalCounts(
                maxAcceptedOnlyRejectionCountsByMutationKind);
            generatedAlphaStructuralHashes = canonicalHashes(
                generatedAlphaStructuralHashes);
            survivingGeneratedAlphaStructuralHashes = canonicalHashes(
                survivingGeneratedAlphaStructuralHashes);
            if (!generatedAlphaStructuralHashes.containsAll(
                    survivingGeneratedAlphaStructuralHashes)) {
                throw new IllegalArgumentException(
                    "surviving generated alpha structures were not generated");
            }
        }

        private static GenerationDiagnostics create(
            int generation,
            Map<EvolutionRewriteProgramMutationKind, Integer> eligible,
            Map<EvolutionRewriteProgramMutationKind, Integer> accepted,
            Map<EvolutionRewriteProgramMutationKind, Integer> budgetOnly,
            List<String> generatedAlpha,
            List<String> survivingAlpha,
            int maxDepth
        ) {
            return new GenerationDiagnostics(
                generation,
                eligible,
                accepted,
                budgetOnly,
                generatedAlpha,
                survivingAlpha,
                maxDepth);
        }

        private static Map<EvolutionRewriteProgramMutationKind, Integer>
                canonicalCounts(
                    Map<EvolutionRewriteProgramMutationKind, Integer> values
                ) {
            Objects.requireNonNull(values, "mutation-kind counts");
            EnumMap<EvolutionRewriteProgramMutationKind, Integer> result =
                new EnumMap<>(EvolutionRewriteProgramMutationKind.class);
            for (EvolutionRewriteProgramMutationKind kind
                    : EvolutionRewriteProgramMutationKind.values()) {
                Integer count = values.get(kind);
                if (count == null || count < 0) {
                    throw new IllegalArgumentException(
                        "mutation-kind counts must contain non-negative values for every kind");
                }
                result.put(kind, count);
            }
            if (values.size() != result.size()) {
                throw new IllegalArgumentException(
                    "mutation-kind counts contain unknown entries");
            }
            return Map.copyOf(result);
        }

        private static List<String> canonicalHashes(List<String> values) {
            Objects.requireNonNull(values, "alpha structural hashes");
            return values.stream()
                .map(value -> {
                    EvolutionGenome.requireSha256(
                        value, "alphaStructuralHash");
                    return value;
                })
                .distinct()
                .sorted()
                .toList();
        }
    }

    public record CandidateStructureFacts(
        String candidateHash,
        String alphaStructuralHash,
        String planHash,
        int firstSeenGeneration,
        int lineageDepthFromSeed,
        int nodeCount,
        boolean containsCompositionTopology,
        boolean containsDecisionTopology,
        int minimumStructuralPrimitivePathSteps
    ) {
        public CandidateStructureFacts {
            EvolutionGenome.requireSha256(candidateHash, "candidateHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            EvolutionGenome.requireSha256(planHash, "planHash");
            if (firstSeenGeneration < 0 || lineageDepthFromSeed < 0
                    || nodeCount < 1
                    || minimumStructuralPrimitivePathSteps < 1) {
                throw new IllegalArgumentException(
                    "invalid candidate structure counters");
            }
            if (firstSeenGeneration == 0 && lineageDepthFromSeed != 0) {
                throw new IllegalArgumentException(
                    "seed candidate must have zero lineage depth");
            }
        }

        private static CandidateStructureFacts create(
            String candidateHash,
            String alphaStructuralHash,
            String planHash,
            int firstSeenGeneration,
            int lineageDepth,
            EvolutionRewriteProgramPlan plan
        ) {
            ProgramFacts facts = EvolutionRewriteProgramStructureAnalyzer.analyze(
                plan);
            return new CandidateStructureFacts(
                candidateHash,
                alphaStructuralHash,
                planHash,
                firstSeenGeneration,
                lineageDepth,
                facts.nodeCount(),
                facts.containsCompositionTopology(),
                facts.containsDecisionTopology(),
                facts.minimumStructuralPrimitivePathSteps());
        }
    }
}
