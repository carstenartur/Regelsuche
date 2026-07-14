package de.regelsuche.mining;

import de.regelsuche.mining.HypothesisCandidate.ExpressionPair;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Adapts a fully evaluated open-target conjecture into the existing hypothesis lifecycle.
 *
 * <p>The adapter is deliberately conservative: it assigns only
 * {@link CandidateProofStatus#VALIDATED_BY_EXAMPLES}, starts with no inferred novelty
 * score, and never inserts or promotes the candidate.</p>
 */
public final class OpenTargetHypothesisCandidateAdapter {
    public HypothesisCandidate adapt(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        Instant createdAt
    ) {
        validate(conjecture, evaluation, createdAt);
        return new HypothesisCandidate(
            conjecture.conjectureId(),
            conjecture.leftPattern(),
            conjecture.rightPattern(),
            supportingPathIds(conjecture),
            supportingExpressions(conjecture),
            assumptions(conjecture),
            0.0,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            evaluation.counterexample().attemptedSources().stream().distinct().sorted().toList(),
            evaluation.counterexample().explanation(),
            conjecture.parameterRelations(),
            conjecture.expressionPlaceholderValues(),
            createdAt);
    }

    private static void validate(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        Instant createdAt
    ) {
        Objects.requireNonNull(conjecture, "conjecture");
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!conjecture.conjectureId().equals(evaluation.conjectureId())) {
            throw new IllegalArgumentException("conjecture and evaluation IDs differ");
        }
        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())
                || !"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                    conjecture.evidenceStatus())) {
            throw new IllegalArgumentException("candidate lacks open-target convergence evidence");
        }
        if (conjecture.supportCount() < 2
                || conjecture.distinctAlphaSupport() < 2
                || conjecture.evidence().size() != conjecture.supportCount()) {
            throw new IllegalArgumentException(
                "candidate requires two independently recorded observations");
        }
        Set<String> evidenceIds = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> declaredIds = new TreeSet<>(conjecture.supportingObservationIds());
        long alphaSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .distinct()
            .count();
        if (evidenceIds.size() != conjecture.supportCount()
                || declaredIds.size() != conjecture.supportCount()
                || !declaredIds.equals(evidenceIds)
                || alphaSupport != conjecture.distinctAlphaSupport()) {
            throw new IllegalArgumentException("candidate support metadata is inconsistent");
        }
        if (evaluation.status() != EvaluationStatus.ACCEPTED_FOR_PROOF
                || !evaluation.acceptedForProof()
                || !evaluation.holdoutsComplete()
                || !evaluation.allHoldoutsPassed()) {
            throw new IllegalArgumentException("candidate has not passed complete evaluation");
        }
        if (evaluation.configuredPositiveHoldouts() < 1
                || evaluation.configuredNegativeHoldouts() < 1
                || evaluation.executedPositiveHoldouts() != evaluation.positiveResults().size()
                || evaluation.executedNegativeHoldouts() != evaluation.negativeResults().size()) {
            throw new IllegalArgumentException("evaluation suite is empty or inconsistently counted");
        }
        if (!evaluation.blockers().isEmpty()) {
            throw new IllegalArgumentException("evaluation still contains blockers");
        }
        if (!"COMPILED".equals(evaluation.compilationStatus())
                || evaluation.dynamicRuleId().isBlank()
                || evaluation.provenanceHash().isBlank()) {
            throw new IllegalArgumentException("candidate is not an executable compiled operator");
        }
        if (!CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND.name().equals(
                evaluation.counterexample().status())
                || evaluation.counterexample().attemptedSources().isEmpty()
                || !evaluation.counterexample().inferredAssumptions().isEmpty()
                || !evaluation.counterexample().assignments().isEmpty()) {
            throw new IllegalArgumentException(
                "counterexample evidence is absent, inconclusive, or contradictory");
        }
        if (supportingPathIds(conjecture).isEmpty()
                || supportingExpressions(conjecture).size() != conjecture.supportCount()) {
            throw new IllegalArgumentException("supporting path evidence is incomplete");
        }
    }

    private static List<String> supportingPathIds(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .map(PathEvidence::pathId)
            .filter(pathId -> pathId != null && !pathId.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    private static List<ExpressionPair> supportingExpressions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .sorted(Comparator.comparing(ConvergenceEvidence::observationId))
            .map(evidence -> new ExpressionPair(
                evidence.inputExpression(), evidence.outputExpression()))
            .distinct()
            .sorted(Comparator.comparing(ExpressionPair::left).thenComparing(ExpressionPair::right))
            .toList();
    }

    private static List<String> assumptions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
    }
}
