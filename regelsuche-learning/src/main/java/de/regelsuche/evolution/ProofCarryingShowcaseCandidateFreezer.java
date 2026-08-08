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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically selects and freezes one learned TRAIN candidate.
 *
 * <p>The v1 public-showcase identity and plan hash are fixed constants. The
 * method accepts no public randomness, generated FINAL TEST case or later-stage
 * result. All terminal alternatives remain in selection evidence before the
 * complete selected program receives a 300-second future not-before boundary.</p>
 */
public final class ProofCarryingShowcaseCandidateFreezer {
    public static final String SHOWCASE_ID =
        "proof-carrying-self-improvement-2026-08/v1";
    public static final String PLAN_CONTENT_HASH =
        "sha256:3aaff50d7208c0339479926049ff8aa9729ae878ab5f9972a54c865ed84970d8";
    public static final long MINIMUM_RANDOMNESS_DELAY_SECONDS = 300L;
    public static final int MINIMUM_PRIMITIVE_PATH_STEPS = 3;

    public FreezeBundle freeze(
        RetainedEvolutionRewriteProgramPopulationRun retained,
        EvolutionRewriteProgramStudyPlan study,
        List<EvolutionRewriteProgramCandidate> seeds,
        String repositoryCommit,
        String primitiveInventoryHash,
        String workBudgetPolicyHash,
        long frozenAtUnixTime
    ) {
        Objects.requireNonNull(retained, "retained");
        Objects.requireNonNull(study, "study");
        Objects.requireNonNull(seeds, "seeds");
        requireBindings(retained, study, seeds);
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

        List<ProofCarryingShowcaseCandidateSelection.Alternative> alternatives =
            retained.finalCandidates().stream()
                .map(value -> alternative(
                    value,
                    evaluations.get(value.candidateHash()),
                    seedHashes,
                    seedAlphaHashes))
                .toList();
        ProofCarryingShowcaseCandidateSelection selection =
            ProofCarryingShowcaseCandidateSelection.create(
                SHOWCASE_ID,
                PLAN_CONTENT_HASH,
                retained,
                alternatives);
        var selected = retained.finalCandidates().stream()
            .filter(value -> value.candidateHash().equals(
                selection.selectedCandidateHash()))
            .findFirst()
            .orElseThrow();
        ProgramFacts facts = analyze(selected.candidate().plan().root());
        long randomnessNotBefore = Math.addExact(
            frozenAtUnixTime, MINIMUM_RANDOMNESS_DELAY_SECONDS);
        ProofCarryingShowcaseCandidateFreeze candidateFreeze =
            ProofCarryingShowcaseCandidateFreeze.create(
                SHOWCASE_ID,
                PLAN_CONTENT_HASH,
                repositoryCommit,
                retained.contentHash(),
                selection,
                selected,
                primitiveInventoryHash,
                workBudgetPolicyHash,
                study.trainEvaluationProtocolHash(),
                retained.populationRun().seedCandidateHashes(),
                facts,
                frozenAtUnixTime,
                randomnessNotBefore);
        return new FreezeBundle(selection, candidateFreeze, selected);
    }

    private static void requireBindings(
        RetainedEvolutionRewriteProgramPopulationRun retained,
        EvolutionRewriteProgramStudyPlan study,
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
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
    }

    private static ProofCarryingShowcaseCandidateSelection.Alternative
            alternative(
                RetainedEvolutionRewriteProgramPopulationRun.RetainedCandidate
                    retained,
                CandidateEvaluation evaluation,
                Set<String> seedHashes,
                Set<String> seedAlphaHashes
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
                < MINIMUM_PRIMITIVE_PATH_STEPS) {
            freezeBlockers.add("PRIMITIVE_PATH_DEPTH_BELOW_3");
        }
        List<String> canonicalFreezeBlockers = freezeBlockers.stream()
            .distinct()
            .sorted()
            .toList();
        return new ProofCarryingShowcaseCandidateSelection.Alternative(
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
        ProofCarryingShowcaseCandidateSelection selection,
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
