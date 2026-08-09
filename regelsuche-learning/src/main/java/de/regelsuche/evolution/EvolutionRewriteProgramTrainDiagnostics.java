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
 * <p>This artifact is deliberately separate from {@code PopulationRun/v1} so
 * new observability cannot reinterpret historical evidence. It binds the
 * execution identity and completed run, preserves every observed mutation batch
 * as canonical JSON and derives only TRAIN-local structural/lineage facts.</p>
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
        requireHash(studyPlanHash, "studyPlanHash");
        requireHash(executionPlanHash, "executionPlanHash");
        requireHash(executionProtocolHash, "executionProtocolHash");
        requireHash(executionBoundRunHash, "executionBoundRunHash");
        requireHash(populationRunHash, "populationRunHash");
        mutationBatches = canonicalBatches(mutationBatches);
        generations = canonicalGenerations(generations);
        candidateStructures = canonicalCandidates(candidateStructures);
        if (!DATA_SCOPE.equals(dataScope)) {
            throw new IllegalArgumentException(
                "rewrite-program diagnostics must remain TRAIN_ONLY");
        }
        requireHash(contentHash, "contentHash");
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
            .sorted(batchComparator())
            .toList();
        Map<String, EvolutionRewriteProgramPlan> plans = plansByHash(
            seeds, observedBatches);
        Map<String, CandidateStructureFacts> structures = seedStructures(seeds);
        Map<String, Integer> lineageDepths = new LinkedHashMap<>();
        seeds.forEach(seed -> lineageDepths.put(seed.contentHash(), 0));

        List<GenerationDiagnostics> generationFacts = new ArrayList<>();
        for (GenerationReport report : populationRun.generationReports()) {
            List<ObservedBatch> observedForGeneration = observedBatches.stream()
                .filter(batch -> batch.generation() == report.generation())
                .toList();
            Map<EvolutionRewriteProgramMutationKind, Integer> eligible =
                emptyCounts();
            Map<EvolutionRewriteProgramMutationKind, Integer> budgetOnly =
                emptyCounts();
            for (ObservedBatch observed : observedForGeneration) {
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
            Set<String> survivingAlpha = new TreeSet<>();
            Set<String> selected = Set.copyOf(report.selectedCandidateHashes());
            int maxDepth = 0;
            for (LineageEdge edge : report.lineage()) {
                increment(accepted, edge.mutationKind());
                generatedAlpha.add(edge.childAlphaStructuralHash());
                if (selected.contains(edge.childCandidateHash())) {
                    survivingAlpha.add(edge.childAlphaStructuralHash());
                }
                int childDepth = childDepth(lineageDepths, edge);
                maxDepth = Math.max(maxDepth, childDepth);
                EvolutionRewriteProgramPlan plan = plans.get(edge.childPlanHash());
                if (plan == null) {
                    throw new IllegalArgumentException(
                        "lineage child plan is absent from observed batches: "
                            + edge.childPlanHash());
                }
                putCandidateFacts(
                    structures,
                    CandidateStructureFacts.create(
                        edge.childCandidateHash(),
                        edge.childAlphaStructuralHash(),
                        edge.childPlanHash(),
                        report.generation(),
                        childDepth,
                        plan));
            }
            generationFacts.add(new GenerationDiagnostics(
                report.generation(),
                eligible,
                accepted,
                budgetOnly,
                List.copyOf(generatedAlpha),
                List.copyOf(survivingAlpha),
                maxDepth));
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
            generationFacts,
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
            generationFacts,
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

    private static int childDepth(
        Map<String, Integer> lineageDepths,
        LineageEdge edge
    ) {
        Integer parentDepth = lineageDepths.get(edge.parentCandidateHash());
        if (parentDepth == null) {
            throw new IllegalArgumentException(
                "lineage parent is not reachable from a seed: "
                    + edge.parentCandidateHash());
        }
        int depth = Math.addExact(parentDepth, 1);
        lineageDepths.merge(edge.childCandidateHash(), depth, Math::min);
        return depth;
    }

    private static Map<String, EvolutionRewriteProgramPlan> plansByHash(
        List<EvolutionRewriteProgramCandidate> seeds,
        List<ObservedBatch> observedBatches
    ) {
        Map<String, EvolutionRewriteProgramPlan> result = new LinkedHashMap<>();
        seeds.forEach(seed -> putPlan(result, seed.plan()));
        observedBatches.forEach(observed -> observed.batch().acceptedPlans()
            .forEach(plan -> putPlan(result, plan)));
        return result;
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
            putCandidateFacts(
                result,
                CandidateStructureFacts.create(
                    seed.contentHash(),
                    seed.alphaStructuralHash(),
                    seed.plan().contentHash(),
                    0,
                    0,
                    seed.plan()));
        }
        return result;
    }

    private static void putCandidateFacts(
        Map<String, CandidateStructureFacts> structures,
        CandidateStructureFacts facts
    ) {
        structures.merge(
            facts.candidateHash(),
            facts,
            CandidateStructureFacts::mergeObservation);
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

    private static Comparator<ObservedMutationBatch> batchComparator() {
        return Comparator.comparingInt(ObservedMutationBatch::generation)
            .thenComparing(ObservedMutationBatch::parentCandidateHash)
            .thenComparing(ObservedMutationBatch::mutationBatchHash);
    }

    private static List<ObservedMutationBatch> canonicalBatches(
        List<ObservedMutationBatch> values
    ) {
        Objects.requireNonNull(values, "mutationBatches");
        List<ObservedMutationBatch> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "mutation batch"))
            .sorted(batchComparator())
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
                    .property("mutationBatchJsonHash", batch.mutationBatchJsonHash())
                    .property("mutationBatchJson", batch.mutationBatchJson()))))
            .array("generations", array -> generations.forEach(generation ->
                array.objectValue(object -> object
                    .property("generation", generation.generation())
                    .object("eligibleProposalCountsByMutationKind", counts ->
                        writeCounts(
                            counts,
                            generation.eligibleProposalCountsByMutationKind()))
                    .object("acceptedOffspringCountsByMutationKind", counts ->
                        writeCounts(
                            counts,
                            generation.acceptedOffspringCountsByMutationKind()))
                    .object("maxAcceptedOnlyRejectionCountsByMutationKind", counts ->
                        writeCounts(
                            counts,
                            generation
                                .maxAcceptedOnlyRejectionCountsByMutationKind()))
                    .stringArray(
                        "generatedAlphaStructuralHashes",
                        generation.generatedAlphaStructuralHashes())
                    .stringArray(
                        "survivingGeneratedAlphaStructuralHashes",
                        generation.survivingGeneratedAlphaStructuralHashes())
                    .property(
                        "maxLineageDepthFromSeed",
                        generation.maxLineageDepthFromSeed()))))
            .array("candidateStructures", array -> candidateStructures.forEach(facts ->
                array.objectValue(object -> object
                    .property("candidateHash", facts.candidateHash())
                    .property("alphaStructuralHash", facts.alphaStructuralHash())
                    .property("planHash", facts.planHash())
                    .property("firstSeenGeneration", facts.firstSeenGeneration())
                    .property("lineageDepthFromSeed", facts.lineageDepthFromSeed())
                    .property("nodeCount", facts.nodeCount())
                    .property(
                        "containsCompositionTopology",
                        facts.containsCompositionTopology())
                    .property(
                        "containsDecisionTopology",
                        facts.containsDecisionTopology())
                    .property(
                        "minimumStructuralPrimitivePathSteps",
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

    private static void requireHash(String value, String name) {
        EvolutionGenome.requireSha256(value, name);
    }

    public record ObservedMutationBatch(
        int generation,
        String parentCandidateHash,
        String mutationBatchHash,
        String mutationBatchJsonHash,
        String mutationBatchJson
    ) {
        public ObservedMutationBatch {
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            requireHash(parentCandidateHash, "parentCandidateHash");
            requireHash(mutationBatchHash, "mutationBatchHash");
            requireHash(mutationBatchJsonHash, "mutationBatchJsonHash");
            if (mutationBatchJson == null || mutationBatchJson.isBlank()) {
                throw new IllegalArgumentException(
                    "mutationBatchJson must not be blank");
            }
            if (!EvolutionGenome.hash(mutationBatchJson).equals(
                    mutationBatchJsonHash)) {
                throw new IllegalArgumentException(
                    "mutationBatchJsonHash does not match canonical batch bytes");
            }
        }

        private static ObservedMutationBatch from(ObservedBatch observed) {
            MutationBatch batch = observed.batch();
            String json = batch.toCanonicalJson();
            return new ObservedMutationBatch(
                observed.generation(),
                observed.parentCandidateHash(),
                batch.contentHash(),
                EvolutionGenome.hash(json),
                json);
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
                        "mutation-kind counts require every non-negative kind");
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
                    requireHash(value, "alphaStructuralHash");
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
            requireHash(candidateHash, "candidateHash");
            requireHash(alphaStructuralHash, "alphaStructuralHash");
            requireHash(planHash, "planHash");
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

        CandidateStructureFacts mergeObservation(
            CandidateStructureFacts observation
        ) {
            Objects.requireNonNull(observation, "candidate structure observation");
            if (!candidateHash.equals(observation.candidateHash())
                    || !alphaStructuralHash.equals(
                        observation.alphaStructuralHash())
                    || !planHash.equals(observation.planHash())
                    || nodeCount != observation.nodeCount()
                    || containsCompositionTopology
                        != observation.containsCompositionTopology()
                    || containsDecisionTopology
                        != observation.containsDecisionTopology()
                    || minimumStructuralPrimitivePathSteps
                        != observation.minimumStructuralPrimitivePathSteps()) {
                throw new IllegalArgumentException(
                    "candidate identity maps to different structural facts");
            }
            return new CandidateStructureFacts(
                candidateHash,
                alphaStructuralHash,
                planHash,
                Math.min(firstSeenGeneration, observation.firstSeenGeneration()),
                Math.min(lineageDepthFromSeed, observation.lineageDepthFromSeed()),
                nodeCount,
                containsCompositionTopology,
                containsDecisionTopology,
                minimumStructuralPrimitivePathSteps);
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
