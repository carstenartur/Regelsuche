package de.regelsuche.docs;

import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.solver.ir.SolverObligationVerifier;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validates the independent evidence axes consumed by {@link OpenTargetPromotionGate}. */
final class OpenTargetPromotionEvidenceValidator {
    private static final SolverObligationVerifier SOLVER_IR =
        new SolverObligationVerifier();

    private OpenTargetPromotionEvidenceValidator() {
    }

    static List<String> validate(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        NoveltyReport novelty,
        ProofReport proof,
        HypothesisCandidate hypothesis
    ) {
        List<String> blockers = new ArrayList<>();
        addIdentityBlockers(blockers, conjecture, evaluation, novelty, proof, hypothesis);
        addConjectureBlockers(blockers, conjecture);
        addEvaluationBlockers(blockers, evaluation);
        addHypothesisBlockers(blockers, conjecture, hypothesis);
        addProofObligationBlockers(blockers, conjecture, proof);
        addNoveltyConsistencyBlockers(blockers, novelty);
        return List.copyOf(blockers);
    }

    private static void addIdentityBlockers(
        List<String> blockers,
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        NoveltyReport novelty,
        ProofReport proof,
        HypothesisCandidate hypothesis
    ) {
        String candidateId = conjecture.conjectureId();
        if (!candidateId.equals(evaluation.conjectureId())) {
            blockers.add("evaluation-provenance-mismatch");
        }
        if (!candidateId.equals(novelty.conjectureId())) {
            blockers.add("novelty-provenance-mismatch");
        }
        if (!candidateId.equals(proof.conjectureId())) {
            blockers.add("proof-provenance-mismatch");
        }
        if (!candidateId.equals(hypothesis.id())) {
            blockers.add("hypothesis-provenance-mismatch");
        }
    }

    private static void addConjectureBlockers(
        List<String> blockers,
        OpenTargetConjecture conjecture
    ) {
        boolean supportedStatus = "OBSERVED_CONJECTURE".equals(
            conjecture.candidateStatus());
        boolean convergenceStatus = "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
            conjecture.evidenceStatus());
        boolean supportCountsValid = conjecture.supportCount() >= 2
            && conjecture.distinctAlphaSupport() >= 2
            && conjecture.evidence().size() == conjecture.supportCount();
        if (!supportedStatus || !convergenceStatus || !supportCountsValid) {
            blockers.add("open-target-support-incomplete");
        }
        if (conjecture.evidence().stream()
                .anyMatch(item -> item.searchStatus() != GoalStatus.UNTARGETED)) {
            blockers.add("targeted-evidence-present");
        }
        if (!supportMetadataConsistent(conjecture)) {
            blockers.add("open-target-support-metadata-inconsistent");
        }
    }

    private static boolean supportMetadataConsistent(OpenTargetConjecture conjecture) {
        TreeSet<String> evidenceIds = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        TreeSet<String> declaredIds = new TreeSet<>(conjecture.supportingObservationIds());
        long alphaSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .distinct()
            .count();
        return evidenceIds.size() == conjecture.supportCount()
            && evidenceIds.equals(declaredIds)
            && alphaSupport == conjecture.distinctAlphaSupport();
    }

    private static void addEvaluationBlockers(
        List<String> blockers,
        EvaluationReport evaluation
    ) {
        boolean accepted = evaluation.status() == EvaluationStatus.ACCEPTED_FOR_PROOF
            && evaluation.acceptedForProof();
        boolean holdoutsComplete = evaluation.holdoutsComplete()
            && evaluation.allHoldoutsPassed()
            && evaluation.configuredPositiveHoldouts() >= 1
            && evaluation.configuredNegativeHoldouts() >= 1
            && evaluation.executedPositiveHoldouts() == evaluation.positiveResults().size()
            && evaluation.executedNegativeHoldouts() == evaluation.negativeResults().size();
        if (!accepted || !holdoutsComplete || !evaluation.blockers().isEmpty()) {
            blockers.add("candidate-evaluation-not-accepted");
        }
        boolean compiled = "COMPILED".equals(evaluation.compilationStatus())
            && !evaluation.dynamicRuleId().isBlank()
            && sha256(evaluation.provenanceHash());
        if (!compiled) {
            blockers.add("compiled-operator-provenance-missing");
        }
        if (!counterexampleCleared(evaluation)) {
            blockers.add("counterexample-evidence-not-cleared");
        }
    }

    private static boolean counterexampleCleared(EvaluationReport evaluation) {
        return CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND.name().equals(
                evaluation.counterexample().status())
            && !evaluation.counterexample().attemptedSources().isEmpty()
            && evaluation.counterexample().inferredAssumptions().isEmpty()
            && evaluation.counterexample().assignments().isEmpty();
    }

    private static void addHypothesisBlockers(
        List<String> blockers,
        OpenTargetConjecture conjecture,
        HypothesisCandidate hypothesis
    ) {
        boolean patternsMatch = conjecture.leftPattern().equals(hypothesis.leftPattern())
            && conjecture.rightPattern().equals(hypothesis.rightPattern());
        boolean lifecycleValidated = hypothesis.proofStatus().atLeast(
            CandidateProofStatus.VALIDATED_BY_EXAMPLES);
        boolean counterexampleCleared = hypothesis.counterexampleSearchStatus()
                == CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND
            && Boolean.FALSE.equals(hypothesis.counterexampleStatus());
        boolean supportPresent = !hypothesis.supportingPaths().isEmpty()
            && hypothesis.supportingExpressions().size() == conjecture.supportCount();
        boolean metadataPreserved = assumptions(conjecture).equals(hypothesis.assumptions())
            && conjecture.parameterRelations().equals(hypothesis.parameterRelations())
            && new TreeMap<>(conjecture.expressionPlaceholderValues()).equals(
                new TreeMap<>(hypothesis.expressionPlaceholders()));
        if (!patternsMatch
                || !lifecycleValidated
                || !counterexampleCleared
                || !supportPresent
                || !metadataPreserved) {
            blockers.add("hypothesis-lifecycle-evidence-incomplete");
        }
    }

    private static void addProofObligationBlockers(
        List<String> blockers,
        OpenTargetConjecture conjecture,
        ProofReport proof
    ) {
        boolean available = proof.eligibility() == EligibilityStatus.ELIGIBLE
            && proof.proofObligationEmitted()
            && proof.obligation() != null
            && proof.execution() != null
            && proof.result() != null
            && sha256(proof.evidenceHash());
        if (!available) {
            blockers.add("proof-obligation-ineligible");
            return;
        }
        boolean obligationMatches = SOLVER_IR.matchesEquality(
            proof.obligation(),
            conjecture.conjectureId(),
            conjecture.leftPattern(),
            conjecture.rightPattern(),
            assumptions(conjecture));
        boolean resultMatches = SOLVER_IR.resultBelongsTo(
            proof.obligation(), proof.result());
        boolean executionMatches = proof.execution().obligationHash().equals(
                proof.obligation().contentHash())
            && proof.execution().result().contentHash().equals(
                proof.result().contentHash())
            && proof.execution().translation().contentHash().equals(
                proof.translation().contentHash());
        if (!obligationMatches || !resultMatches || !executionMatches
                || !sha256(proof.obligation().contentHash())
                || !sha256(proof.translation().contentHash())
                || !sha256(proof.result().contentHash())
                || !sha256(proof.execution().contentHash())) {
            blockers.add("proof-obligation-provenance-mismatch");
        }
    }

    private static void addNoveltyConsistencyBlockers(
        List<String> blockers,
        NoveltyReport novelty
    ) {
        boolean claimsProjectNovelty = novelty.status()
            == de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus
                .NOVEL_WITHIN_PROJECT;
        boolean evidenceConsistent = novelty.matches().isEmpty()
            && sha256(novelty.exactSignatureHash())
            && sha256(novelty.alphaSignatureHash());
        if (claimsProjectNovelty && !evidenceConsistent) {
            blockers.add("novelty-evidence-inconsistent");
        }
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static List<String> assumptions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().replaceAll("\\s+", " "))
            .distinct()
            .sorted()
            .toList();
    }
}
