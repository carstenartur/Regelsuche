package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.TerminalOutcome;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Content-addressed terminal TRAIN selection evidence that remains representable
 * when no candidate satisfies the preregistered freeze policy.
 *
 * <p>This artifact is deliberately earlier than a candidate freeze. A null
 * selection retains every terminal alternative and its blockers while creating
 * no randomness boundary and authorizing no later-stage material.</p>
 */
public record ProofCarryingShowcaseTrainSelectionEvidence(
    String schema,
    String showcaseId,
    String planContentHash,
    String retainedPopulationRunHash,
    String populationRunHash,
    String finalGenerationReportHash,
    TerminalOutcome populationTerminalOutcome,
    String selectionPolicy,
    List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
        alternatives,
    String selectedCandidateHash,
    String selectedCandidateAlphaStructuralHash,
    String selectedPlanHash,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-train-selection-evidence/v1";
    public static final String ELIGIBLE =
        "ELIGIBLE_CANDIDATE_AVAILABLE_FINAL_TEST_UNSEEN";
    public static final String NONE =
        "NO_ELIGIBLE_TRAIN_ALTERNATIVE_FINAL_TEST_UNSEEN";
    public static final String SELECTION_POLICY =
        ProofCarryingShowcaseCandidateFreezer.CandidateSelection.SELECTION_POLICY;

    public ProofCarryingShowcaseTrainSelectionEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported showcase TRAIN selection-evidence schema");
        }
        showcaseId = ProofCarryingShowcaseJsonSupport.requireText(
            showcaseId, "showcaseId");
        EvolutionGenome.requireSha256(planContentHash, "planContentHash");
        EvolutionGenome.requireSha256(
            retainedPopulationRunHash, "retainedPopulationRunHash");
        EvolutionGenome.requireSha256(populationRunHash, "populationRunHash");
        EvolutionGenome.requireSha256(
            finalGenerationReportHash, "finalGenerationReportHash");
        Objects.requireNonNull(
            populationTerminalOutcome, "populationTerminalOutcome");
        if (!SELECTION_POLICY.equals(selectionPolicy)) {
            throw new IllegalArgumentException(
                "unsupported showcase TRAIN selection policy");
        }
        alternatives = canonicalAlternatives(alternatives);
        var selected = select(alternatives);
        String expectedStatus = selected == null ? NONE : ELIGIBLE;
        if (!expectedStatus.equals(status)) {
            throw new IllegalArgumentException(
                "TRAIN selection-evidence status differs from alternatives");
        }
        if (selected == null) {
            if (selectedCandidateHash != null
                    || selectedCandidateAlphaStructuralHash != null
                    || selectedPlanHash != null) {
                throw new IllegalArgumentException(
                    "null TRAIN selection must not retain selected identities");
            }
        } else {
            EvolutionGenome.requireSha256(
                selectedCandidateHash, "selectedCandidateHash");
            EvolutionGenome.requireSha256(
                selectedCandidateAlphaStructuralHash,
                "selectedCandidateAlphaStructuralHash");
            EvolutionGenome.requireSha256(selectedPlanHash, "selectedPlanHash");
            if (!selected.candidateHash().equals(selectedCandidateHash)
                    || !selected.alphaStructuralHash().equals(
                        selectedCandidateAlphaStructuralHash)
                    || !selected.planHash().equals(selectedPlanHash)) {
                throw new IllegalArgumentException(
                    "selected TRAIN candidate differs from deterministic ranking");
            }
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expectedHash = ProofCarryingShowcaseJsonSupport.hashPayload(
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
                selectedPlanHash,
                status));
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException(
                "showcase TRAIN selection-evidence contentHash mismatch");
        }
    }

    public static ProofCarryingShowcaseTrainSelectionEvidence create(
        ProofCarryingShowcasePlan showcasePlan,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun protocolBoundRun,
        EvolutionRewriteProgramStudyPlan study,
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
        Objects.requireNonNull(showcasePlan, "showcasePlan");
        Objects.requireNonNull(protocolBoundRun, "protocolBoundRun");
        Objects.requireNonNull(study, "study");
        Objects.requireNonNull(seeds, "seeds");
        RetainedEvolutionRewriteProgramPopulationRun retained =
            protocolBoundRun.retainedPopulation();
        requireBindings(protocolBoundRun, retained, study, seeds);

        Set<String> seedHashes = seeds.stream()
            .map(EvolutionRewriteProgramCandidate::contentHash)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> seedAlphaHashes = seeds.stream()
            .map(EvolutionRewriteProgramCandidate::alphaStructuralHash)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, CandidateEvaluation> evaluations = new HashMap<>();
        retained.finalEvaluations().forEach(evaluation ->
            evaluations.put(evaluation.candidateHash(), evaluation));
        int minimumDepth = showcasePlan.candidateFormation()
            .requiredCandidateProperties()
            .minimumPrimitiveStepsOnSuccessfulPath();
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            alternatives = retained.finalCandidates().stream()
                .map(value -> alternative(
                    value,
                    Objects.requireNonNull(
                        evaluations.get(value.candidateHash()),
                        "terminal candidate evaluation"),
                    seedHashes,
                    seedAlphaHashes,
                    minimumDepth))
                .toList();
        var run = retained.populationRun();
        return createFromAlternatives(
            showcasePlan.showcaseId(),
            showcasePlan.contentHash(),
            retained.contentHash(),
            run.contentHash(),
            run.generationReports().getLast().contentHash(),
            run.terminalOutcome(),
            alternatives);
    }

    static ProofCarryingShowcaseTrainSelectionEvidence createFromAlternatives(
        String showcaseId,
        String planContentHash,
        String retainedPopulationRunHash,
        String populationRunHash,
        String finalGenerationReportHash,
        TerminalOutcome terminalOutcome,
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            alternatives
    ) {
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            canonical = canonicalAlternatives(alternatives);
        var selected = select(canonical);
        String selectedHash = selected == null ? null : selected.candidateHash();
        String selectedAlpha = selected == null
            ? null : selected.alphaStructuralHash();
        String selectedPlan = selected == null ? null : selected.planHash();
        String status = selected == null ? NONE : ELIGIBLE;
        String hash = ProofCarryingShowcaseJsonSupport.hashPayload(payload(
            showcaseId,
            planContentHash,
            retainedPopulationRunHash,
            populationRunHash,
            finalGenerationReportHash,
            terminalOutcome,
            canonical,
            selectedHash,
            selectedAlpha,
            selectedPlan,
            status));
        return new ProofCarryingShowcaseTrainSelectionEvidence(
            SCHEMA,
            showcaseId,
            planContentHash,
            retainedPopulationRunHash,
            populationRunHash,
            finalGenerationReportHash,
            terminalOutcome,
            SELECTION_POLICY,
            canonical,
            selectedHash,
            selectedAlpha,
            selectedPlan,
            status,
            hash);
    }

    public boolean eligibleCandidateAvailable() {
        return ELIGIBLE.equals(status);
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public static ProofCarryingShowcaseTrainSelectionEvidence fromCanonicalJson(
        String json
    ) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseTrainSelectionEvidence.class,
            "showcase TRAIN selection evidence");
    }

    private static void requireBindings(
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun protocolBoundRun,
        RetainedEvolutionRewriteProgramPopulationRun retained,
        EvolutionRewriteProgramStudyPlan study,
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
        if (!ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun.STATUS
                .equals(protocolBoundRun.status())) {
            throw new IllegalArgumentException(
                "TRAIN selection evidence requires protocol-bound authority");
        }
        if (!protocolBoundRun.evaluationProtocolHash().equals(
                study.trainEvaluationProtocolHash())
                || !retained.populationRun().studyPlanHash().equals(
                    study.contentHash())) {
            throw new IllegalArgumentException(
                "retained TRAIN population differs from study/protocol");
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
                "retained TRAIN population differs from seed candidates");
        }
        if (!"NOT_EVALUATED".equals(retained.validationStatus())
                || !"NOT_EVALUATED".equals(retained.finalTestStatus())) {
            throw new IllegalArgumentException(
                "TRAIN selection evidence cannot consume later-stage outcomes");
        }
    }

    private static ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
            alternative(
        RetainedEvolutionRewriteProgramPopulationRun.RetainedCandidate retained,
        CandidateEvaluation evaluation,
        Set<String> seedHashes,
        Set<String> seedAlphaHashes,
        int minimumPrimitivePathSteps
    ) {
        EvolutionRewriteProgramCandidate candidate = retained.candidate();
        ProofCarryingShowcaseCandidateFreezer.ProgramFacts facts =
            ProofCarryingShowcaseCandidateFreezer.analyze(candidate.plan().root());
        boolean seedExact = seedHashes.contains(candidate.contentHash());
        boolean seedAlpha = seedAlphaHashes.contains(candidate.alphaStructuralHash());
        java.util.ArrayList<String> freezeBlockers = new java.util.ArrayList<>();
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
        return new ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative(
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

    private static List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            canonicalAlternatives(
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative> values
    ) {
        Objects.requireNonNull(values, "alternatives");
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            result = values.stream()
                .map(value -> Objects.requireNonNull(value, "candidate alternative"))
                .sorted(Comparator.comparing(
                    ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
                        ::candidateHash))
                .toList();
        if (new HashSet<>(result.stream().map(
                ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
                    ::candidateHash).toList()).size() != result.size()) {
            throw new IllegalArgumentException(
                "candidate alternatives require unique identities");
        }
        return List.copyOf(result);
    }

    private static ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
            select(
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            alternatives
    ) {
        return alternatives.stream()
            .filter(ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
                ::eligibleForFreeze)
            .sorted(Comparator
                .comparingInt(
                    ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
                        ::scalarFitness)
                .reversed()
                .thenComparingInt(
                    ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
                        ::programNodeCount)
                .thenComparing(
                    ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
                        ::candidateHash))
            .findFirst()
            .orElse(null);
    }

    private static Map<String, Object> payload(
        String showcaseId,
        String planContentHash,
        String retainedPopulationRunHash,
        String populationRunHash,
        String finalGenerationReportHash,
        TerminalOutcome terminalOutcome,
        List<ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative>
            alternatives,
        String selectedCandidateHash,
        String selectedCandidateAlphaStructuralHash,
        String selectedPlanHash,
        String status
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
            "status", status);
    }
}