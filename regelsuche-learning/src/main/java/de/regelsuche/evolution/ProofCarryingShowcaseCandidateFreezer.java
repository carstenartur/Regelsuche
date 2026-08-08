package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Node;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.TerminalOutcome;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProofCarryingShowcaseCandidateFreezer {
    public FreezeBundle freeze(
        ProofCarryingShowcasePlan showcasePlan,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            protocolBoundRun,
        EvolutionRewriteProgramStudyPlan study,
        List<EvolutionRewriteProgramCandidate> seeds,
        String repositoryCommit,
        String primitiveInventoryHash,
        String workBudgetPolicyHash,
        long frozenAtUnixTime
    ) {
        Objects.requireNonNull(showcasePlan, "showcasePlan");
        Objects.requireNonNull(protocolBoundRun, "protocolBoundRun");
        RetainedEvolutionRewriteProgramPopulationRun retained =
            protocolBoundRun.retainedPopulation();
        Objects.requireNonNull(study, "study");
        Objects.requireNonNull(seeds, "seeds");
        requireBindings(
            showcasePlan,
            protocolBoundRun,
            retained,
            study,
            seeds);
        ProofCarryingShowcaseJsonSupport.requireCommit(
            repositoryCommit, "repositoryCommit");
        EvolutionGenome.requireSha256(
            primitiveInventoryHash, "primitiveInventoryHash");
        EvolutionGenome.requireSha256(
            workBudgetPolicyHash, "workBudgetPolicyHash");
        if (frozenAtUnixTime < 1) {
            throw new IllegalArgumentException(
                "frozenAtUnixTime must be positive");
        }

        Set<String> seedHashes = seeds.stream()
            .map(EvolutionRewriteProgramCandidate::contentHash)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> seedAlphaHashes = seeds.stream()
            .map(EvolutionRewriteProgramCandidate::alphaStructuralHash)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, CandidateEvaluation> evaluations = new HashMap<>();
        retained.finalEvaluations().forEach(evaluation ->
            evaluations.put(evaluation.candidateHash(), evaluation));

        List<CandidateSelection.Alternative> alternatives =
            retained.finalCandidates().stream()
                .map(value -> alternative(
                    value,
                    evaluations.get(value.candidateHash()),
                    seedHashes,
                    seedAlphaHashes,
                    showcasePlan.candidateFormation()
                        .requiredCandidateProperties()
                        .minimumPrimitiveStepsOnSuccessfulPath()))
                .toList();
        CandidateSelection selection = CandidateSelection.create(
            showcasePlan.showcaseId(),
            showcasePlan.contentHash(),
            retained,
            alternatives);
        var selected = retained.finalCandidates().stream()
            .filter(value -> value.candidateHash().equals(
                selection.selectedCandidateHash()))
            .findFirst()
            .orElseThrow();
        ProgramFacts facts = analyze(selected.candidate().plan().root());
        ProofCarryingShowcaseCandidateFreeze candidateFreeze =
            ProofCarryingShowcaseCandidateFreeze.create(
                showcasePlan,
                repositoryCommit,
                protocolBoundRun.contentHash(),
                selection.contentHash(),
                selected.candidateHash(),
                selected.alphaStructuralHash(),
                selected.humanReadableProgramHash(),
                primitiveInventoryHash,
                workBudgetPolicyHash,
                protocolBoundRun.evaluationProtocolHash(),
                retained.populationRun().seedCandidateHashes(),
                facts.nodeCount(),
                facts.containsCompositionTopology(),
                facts.containsDecisionTopology(),
                facts.minimumStructuralPrimitivePathSteps(),
                frozenAtUnixTime);
        return new FreezeBundle(selection, candidateFreeze, selected);
    }

    private static void requireBindings(
        ProofCarryingShowcasePlan showcasePlan,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            protocolBoundRun,
        RetainedEvolutionRewriteProgramPopulationRun retained,
        EvolutionRewriteProgramStudyPlan study,
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
        if (!ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun.STATUS
                .equals(protocolBoundRun.status())) {
            throw new IllegalArgumentException(
                "candidate freeze requires protocol-bound TRAIN authority");
        }
        if (!protocolBoundRun.evaluationProtocolHash().equals(
                study.trainEvaluationProtocolHash())) {
            throw new IllegalArgumentException(
                "retained evaluator protocol differs from TRAIN study");
        }
        if (!retained.populationRun().studyPlanHash().equals(
                study.contentHash())) {
            throw new IllegalArgumentException(
                "retained population differs from showcase TRAIN study");
        }
        List<String> expectedSeeds = seeds.stream()
            .map(EvolutionRewriteProgramCandidate::contentHash)
            .distinct()
            .sorted()
            .toList();
        if (expectedSeeds.size() != seeds.size()
                || !expectedSeeds.equals(
                retained.populationRun().seedCandidateHashes())) {
            throw new IllegalArgumentException(
                "retained population differs from frozen seed candidates");
        }
        if (!"NOT_EVALUATED".equals(retained.validationStatus())
                || !"NOT_EVALUATED".equals(retained.finalTestStatus())) {
            throw new IllegalArgumentException(
                "candidate freeze cannot consume later-stage outcomes");
        }
        if (retained.finalCandidates().isEmpty()) {
            throw new IllegalStateException(
                "terminal TRAIN population contains no candidate");
        }
        if (!showcasePlan.status().equals(
                ProofCarryingShowcasePlan.STATUS)) {
            throw new IllegalArgumentException(
                "candidate freeze requires the frozen unexecuted showcase plan");
        }
    }

    private static CandidateSelection.Alternative alternative(
        RetainedEvolutionRewriteProgramPopulationRun.RetainedCandidate retained,
        CandidateEvaluation evaluation,
        Set<String> seedHashes,
        Set<String> seedAlphaHashes,
        int minimumPrimitivePathSteps
    ) {
        if (evaluation == null) {
            throw new IllegalArgumentException(
                "terminal candidate lacks retained TRAIN evaluation");
        }
        EvolutionRewriteProgramCandidate candidate = retained.candidate();
        ProgramFacts facts = analyze(candidate.plan().root());
        boolean seedExact = seedHashes.contains(candidate.contentHash());
        boolean seedAlpha = seedAlphaHashes.contains(
            candidate.alphaStructuralHash());
        List<String> freezeBlockers = new ArrayList<>();
        if (seedExact) {
            freezeBlockers.add("SEED_EXACT_EQUIVALENT");
        }
        if (seedAlpha) {
            freezeBlockers.add("SEED_ALPHA_EQUIVALENT");
        }
        if (!facts.containsCompositionTopology()) {
            freezeBlockers.add("MISSING_COMPOSITION_TOPOLOGY");
        }
        if (!facts.containsDecisionTopology()) {
            freezeBlockers.add("MISSING_DECISION_TOPOLOGY");
        }
        if (facts.minimumStructuralPrimitivePathSteps()
                < minimumPrimitivePathSteps) {
            freezeBlockers.add("PRIMITIVE_PATH_DEPTH_BELOW_"
                + minimumPrimitivePathSteps);
        }
        List<String> canonicalFreezeBlockers = freezeBlockers.stream()
            .distinct()
            .sorted()
            .toList();
        return new CandidateSelection.Alternative(
            candidate.contentHash(),
            candidate.alphaStructuralHash(),
            candidate.genome().contentHash(),
            candidate.plan().contentHash(),
            evaluation.contentHash(),
            evaluation.scalarFitness(),
            evaluation.rawComponents(),
            evaluation.blockers(),
            seedExact,
            seedAlpha,
            facts.nodeCount(),
            facts.containsCompositionTopology(),
            facts.containsDecisionTopology(),
            facts.minimumStructuralPrimitivePathSteps(),
            canonicalFreezeBlockers,
            evaluation.blockers().isEmpty()
                && canonicalFreezeBlockers.isEmpty());
    }

    static ProgramFacts analyze(Node node) {
        Objects.requireNonNull(node, "node");
        if (node instanceof Source) {
            return new ProgramFacts(1, false, false, 1);
        }
        if (node instanceof Sequence sequence) {
            List<ProgramFacts> children = sequence.steps().stream()
                .map(ProofCarryingShowcaseCandidateFreezer::analyze)
                .toList();
            return new ProgramFacts(
                onePlusNodeCounts(children),
                true,
                children.stream().anyMatch(
                    ProgramFacts::containsDecisionTopology),
                children.stream()
                    .mapToInt(
                        ProgramFacts::minimumStructuralPrimitivePathSteps)
                    .reduce(0, ProofCarryingShowcaseCandidateFreezer::safeAdd));
        }
        if (node instanceof Repeat repeat) {
            ProgramFacts child = analyze(repeat.body());
            return new ProgramFacts(
                safeAdd(1, child.nodeCount()),
                true,
                child.containsDecisionTopology(),
                safeMultiply(
                    repeat.minIterations(),
                    child.minimumStructuralPrimitivePathSteps()));
        }
        if (node instanceof Choice choice) {
            return decisionAlternatives(choice.alternatives());
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return decisionAlternatives(firstApplicable.alternatives());
        }
        if (node instanceof Require require) {
            return decisionWrapper(analyze(require.body()));
        }
        if (node instanceof Prioritize prioritize) {
            return decisionWrapper(analyze(prioritize.body()));
        }
        if (node instanceof Prune prune) {
            return decisionWrapper(analyze(prune.body()));
        }
        throw new IllegalArgumentException(
            "unsupported rewrite-program node: " + node.getClass().getName());
    }

    private static ProgramFacts decisionAlternatives(List<Node> alternatives) {
        List<ProgramFacts> children = alternatives.stream()
            .map(ProofCarryingShowcaseCandidateFreezer::analyze)
            .toList();
        return new ProgramFacts(
            onePlusNodeCounts(children),
            children.stream().anyMatch(
                ProgramFacts::containsCompositionTopology),
            true,
            children.stream()
                .mapToInt(
                    ProgramFacts::minimumStructuralPrimitivePathSteps)
                .min()
                .orElseThrow());
    }

    private static ProgramFacts decisionWrapper(ProgramFacts child) {
        return new ProgramFacts(
            safeAdd(1, child.nodeCount()),
            child.containsCompositionTopology(),
            true,
            child.minimumStructuralPrimitivePathSteps());
    }

    private static int onePlusNodeCounts(List<ProgramFacts> children) {
        int result = 1;
        for (ProgramFacts child : children) {
            result = safeAdd(result, child.nodeCount());
        }
        return result;
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : Math.toIntExact(result);
    }

    private static int safeMultiply(int left, int right) {
        long result = (long) left * right;
        return result > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : Math.toIntExact(result);
    }

    public static record CandidateSelection(
        String schema,
        String showcaseId,
        String planContentHash,
        String retainedPopulationRunHash,
        String populationRunHash,
        String finalGenerationReportHash,
        TerminalOutcome populationTerminalOutcome,
        String selectionPolicy,
        List<Alternative> alternatives,
        String selectedCandidateHash,
        String selectedCandidateAlphaStructuralHash,
        String selectedPlanHash,
        String status,
        String contentHash
    ) {
        public static final String SCHEMA =
            "regelsuche.proof-carrying-showcase-candidate-selection/v1";
        public static final String STATUS =
            "TRAIN_SELECTION_FROZEN_FINAL_TEST_UNSEEN";
        public static final String SELECTION_POLICY =
            "MAX_TRAIN_SCALAR_THEN_MIN_PROGRAM_NODES_THEN_CANDIDATE_HASH_"
                + "AMONG_NON_SEED_COMPOSITE_DECISION_CANDIDATES";

        public CandidateSelection {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported showcase candidate-selection schema");
            }
            ProofCarryingShowcaseJsonSupport.requireText(
                showcaseId, "showcaseId");
            EvolutionGenome.requireSha256(
                planContentHash, "planContentHash");
            EvolutionGenome.requireSha256(
                retainedPopulationRunHash, "retainedPopulationRunHash");
            EvolutionGenome.requireSha256(
                populationRunHash, "populationRunHash");
            EvolutionGenome.requireSha256(
                finalGenerationReportHash, "finalGenerationReportHash");
            Objects.requireNonNull(
                populationTerminalOutcome, "populationTerminalOutcome");
            if (populationTerminalOutcome == TerminalOutcome.EXTINCT) {
                throw new IllegalArgumentException(
                    "an extinct TRAIN population cannot freeze a candidate");
            }
            if (!SELECTION_POLICY.equals(selectionPolicy)) {
                throw new IllegalArgumentException(
                    "unsupported showcase candidate-selection policy");
            }
            alternatives = canonicalAlternatives(alternatives);
            EvolutionGenome.requireSha256(
                selectedCandidateHash, "selectedCandidateHash");
            EvolutionGenome.requireSha256(
                selectedCandidateAlphaStructuralHash,
                "selectedCandidateAlphaStructuralHash");
            EvolutionGenome.requireSha256(
                selectedPlanHash, "selectedPlanHash");
            if (!STATUS.equals(status)) {
                throw new IllegalArgumentException(
                    "candidate selection must keep FINAL TEST unseen");
            }
            Alternative selected = selected(alternatives);
            if (!selected.candidateHash().equals(selectedCandidateHash)
                    || !selected.alphaStructuralHash().equals(
                        selectedCandidateAlphaStructuralHash)
                    || !selected.planHash().equals(selectedPlanHash)) {
                throw new IllegalArgumentException(
                    "selected candidate differs from frozen deterministic ranking");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
                payload(
                    showcaseId,
                    planContentHash,
                    retainedPopulationRunHash,
                    populationRunHash,
                    finalGenerationReportHash,
                    populationTerminalOutcome,
                    alternatives,
                    selectedCandidateHash,
                    selectedCandidateAlphaStructuralHash,
                    selectedPlanHash));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "showcase candidate-selection contentHash mismatch");
            }
        }

        static CandidateSelection create(
            String showcaseId,
            String planContentHash,
            RetainedEvolutionRewriteProgramPopulationRun retained,
            List<Alternative> alternatives
        ) {
            Objects.requireNonNull(retained, "retained");
            List<Alternative> canonical = canonicalAlternatives(alternatives);
            Alternative selected = selected(canonical);
            var run = retained.populationRun();
            String finalGenerationHash = run.generationReports().getLast()
                .contentHash();
            String hash = ProofCarryingShowcaseJsonSupport.hashPayload(payload(
                showcaseId,
                planContentHash,
                retained.contentHash(),
                run.contentHash(),
                finalGenerationHash,
                run.terminalOutcome(),
                canonical,
                selected.candidateHash(),
                selected.alphaStructuralHash(),
                selected.planHash()));
            return new CandidateSelection(
                SCHEMA,
                showcaseId,
                planContentHash,
                retained.contentHash(),
                run.contentHash(),
                finalGenerationHash,
                run.terminalOutcome(),
                SELECTION_POLICY,
                canonical,
                selected.candidateHash(),
                selected.alphaStructuralHash(),
                selected.planHash(),
                STATUS,
                hash);
        }

        public String toCanonicalJson() {
            return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
        }

        private static Alternative selected(List<Alternative> alternatives) {
            return alternatives.stream()
                .filter(Alternative::eligibleForFreeze)
                .sorted(ranking())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "candidate selection requires an eligible TRAIN alternative"));
        }

        private static Comparator<Alternative> ranking() {
            return Comparator
                .comparingInt(Alternative::scalarFitness)
                .reversed()
                .thenComparingInt(Alternative::programNodeCount)
                .thenComparing(Alternative::candidateHash);
        }

        private static List<Alternative> canonicalAlternatives(
            List<Alternative> values
        ) {
            Objects.requireNonNull(values, "alternatives");
            List<Alternative> result = values.stream()
                .map(value -> Objects.requireNonNull(
                    value, "candidate alternative"))
                .sorted(Comparator.comparing(Alternative::candidateHash))
                .toList();
            if (result.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate selection requires terminal alternatives");
            }
            if (new HashSet<>(result.stream()
                    .map(Alternative::candidateHash).toList()).size()
                    != result.size()) {
                throw new IllegalArgumentException(
                    "candidate alternatives require unique identities");
            }
            return result;
        }

        private static Map<String, Object> payload(
            String showcaseId,
            String planContentHash,
            String retainedPopulationRunHash,
            String populationRunHash,
            String finalGenerationReportHash,
            TerminalOutcome terminalOutcome,
            List<Alternative> alternatives,
            String selectedCandidateHash,
            String selectedCandidateAlphaStructuralHash,
            String selectedPlanHash
        ) {
            return ProofCarryingShowcaseJsonSupport.payload(
                "schema", SCHEMA,
                "showcaseId", showcaseId,
                "planContentHash", planContentHash,
                "retainedPopulationRunHash", retainedPopulationRunHash,
                "populationRunHash", populationRunHash,
                "finalGenerationReportHash", finalGenerationReportHash,
                "populationTerminalOutcome", terminalOutcome,
                "selectionPolicy", SELECTION_POLICY,
                "alternatives", alternatives,
                "selectedCandidateHash", selectedCandidateHash,
                "selectedCandidateAlphaStructuralHash",
                    selectedCandidateAlphaStructuralHash,
                "selectedPlanHash", selectedPlanHash,
                "status", STATUS);
        }

        public static record Alternative(
            String candidateHash,
            String alphaStructuralHash,
            String genomeHash,
            String planHash,
            String evaluationHash,
            int scalarFitness,
            Map<FitnessComponent, Integer> rawComponents,
            List<String> trainBlockers,
            boolean seedExactEquivalent,
            boolean seedAlphaEquivalent,
            int programNodeCount,
            boolean containsCompositionTopology,
            boolean containsDecisionTopology,
            int minimumStructuralPrimitivePathSteps,
            List<String> freezeBlockers,
            boolean eligibleForFreeze
        ) {
            public Alternative {
                EvolutionGenome.requireSha256(candidateHash, "candidateHash");
                EvolutionGenome.requireSha256(
                    alphaStructuralHash, "alphaStructuralHash");
                EvolutionGenome.requireSha256(genomeHash, "genomeHash");
                EvolutionGenome.requireSha256(planHash, "planHash");
                EvolutionGenome.requireSha256(
                    evaluationHash, "evaluationHash");
                if (scalarFitness < -1000 || scalarFitness > 1000) {
                    throw new IllegalArgumentException(
                        "scalarFitness must be in [-1000,1000]");
                }
                rawComponents = canonicalComponents(rawComponents);
                trainBlockers = canonicalBlockers(
                    trainBlockers, "trainBlockers");
                if (programNodeCount < 1
                        || minimumStructuralPrimitivePathSteps < 1) {
                    throw new IllegalArgumentException(
                        "candidate structural counters must be positive");
                }
                freezeBlockers = canonicalBlockers(
                    freezeBlockers, "freezeBlockers");
                boolean expectedEligible = trainBlockers.isEmpty()
                    && freezeBlockers.isEmpty();
                if (eligibleForFreeze != expectedEligible) {
                    throw new IllegalArgumentException(
                        "eligibleForFreeze differs from retained blockers");
                }
            }

            private static Map<FitnessComponent, Integer> canonicalComponents(
                Map<FitnessComponent, Integer> values
            ) {
                EnumMap<FitnessComponent, Integer> result =
                    new EnumMap<>(FitnessComponent.class);
                if (values != null) {
                    values.forEach((component, value) -> {
                        Objects.requireNonNull(
                            component, "fitness component");
                        Objects.requireNonNull(value, "fitness value");
                        if (value < -1000 || value > 1000) {
                            throw new IllegalArgumentException(
                                "fitness value must be in [-1000,1000]");
                        }
                        result.put(component, value);
                    });
                }
                return Collections.unmodifiableMap(result);
            }

            private static List<String> canonicalBlockers(
                List<String> values,
                String name
            ) {
                return ProofCarryingShowcaseJsonSupport.immutableStrings(
                    values == null ? List.of() : values,
                    name,
                    true,
                    false);
            }
        }
    }

    public record ProgramFacts(
        int nodeCount,
        boolean containsCompositionTopology,
        boolean containsDecisionTopology,
        int minimumStructuralPrimitivePathSteps
    ) {
        public ProgramFacts {
            if (nodeCount < 1 || minimumStructuralPrimitivePathSteps < 1) {
                throw new IllegalArgumentException(
                    "program facts require positive structural counters");
            }
        }
    }

    public record FreezeBundle(
        CandidateSelection selection,
        ProofCarryingShowcaseCandidateFreeze candidateFreeze,
        RetainedEvolutionRewriteProgramPopulationRun.RetainedCandidate
            selectedCandidate
    ) {
        public FreezeBundle {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(candidateFreeze, "candidateFreeze");
            Objects.requireNonNull(selectedCandidate, "selectedCandidate");
            if (!selection.selectedCandidateHash().equals(
                    selectedCandidate.candidateHash())
                    || !candidateFreeze.candidateContentHash().equals(
                    selectedCandidate.candidateHash())
                    || !candidateFreeze.selectionEvidenceHash().equals(
                    selection.contentHash())) {
                throw new IllegalArgumentException(
                    "showcase freeze bundle identities differ");
            }
        }
    }
}
