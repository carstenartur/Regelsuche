package de.regelsuche.mining;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.validation.CandidateProofStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A mined hypothesis waiting to be validated, refuted, or promoted to a
 * reusable macro rule.
 *
 * <p>Lifecycle:</p>
 * <pre>
 * Path-Mining
 *   → Generalisation (PatternGeneralizer / anti-unification)
 *   → HypothesisCandidate  ← created here
 *   → Counterexample Search  (CounterexampleSearchService)
 *   → optional symbolic Proof (EquivalenceService)
 *   → Promotion → ReusableRule (MacroRuleLearningService)
 * </pre>
 *
 * <p>A hypothesis that accumulates a counterexample transitions to
 * {@link CandidateProofStatus#REJECTED}. A hypothesis confirmed by
 * independent fresh examples transitions to
 * {@link CandidateProofStatus#VALIDATED_BY_EXAMPLES} (or higher).</p>
 *
 * @param id                       stable, unique identifier (e.g. canonical hash)
 * @param leftPattern              generalised left-hand side of the rule
 * @param rightPattern             generalised right-hand side of the rule
 * @param supportingPaths          ids of discovered paths that support this hypothesis
 * @param supportingExpressions    concrete (left, right) expression pairs as witnesses
 * @param assumptions              assumptions under which the hypothesis holds
 * @param noveltyScore             0.0–1.0 score reflecting how different this hypothesis
 *                                 is from already-known rules (1.0 = completely new)
 * @param proofStatus              current validation state
 * @param counterexampleStatus     {@code null} if no search was performed, {@code true}
 *                                 if a counterexample was found, {@code false} otherwise
 * @param parameterRelations       human-readable algebraic relations between placeholders
 * @param expressionPlaceholders   expression-level placeholders introduced by anti-unification
 * @param createdAt                when the hypothesis was first created
 */
public record HypothesisCandidate(
    String id,
    String leftPattern,
    String rightPattern,
    List<String> supportingPaths,
    List<ExpressionPair> supportingExpressions,
    List<String> assumptions,
    double noveltyScore,
    CandidateProofStatus proofStatus,
    Boolean counterexampleStatus,
    List<String> parameterRelations,
    java.util.Map<String, List<String>> expressionPlaceholders,
    Instant createdAt
) {
    /** A concrete (before, after) expression pair that supports this hypothesis. */
    public record ExpressionPair(String left, String right) {}

    public HypothesisCandidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        supportingPaths = supportingPaths == null ? List.of() : List.copyOf(supportingPaths);
        supportingExpressions = supportingExpressions == null ? List.of() : List.copyOf(supportingExpressions);
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        if (noveltyScore < 0.0 || noveltyScore > 1.0) {
            noveltyScore = Math.max(0.0, Math.min(1.0, noveltyScore));
        }
        proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED : proofStatus;
        parameterRelations = parameterRelations == null ? List.of() : List.copyOf(parameterRelations);
        expressionPlaceholders = expressionPlaceholders == null
            ? java.util.Map.of()
            : expressionPlaceholders.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                    java.util.Map.Entry::getKey,
                    e -> List.copyOf(e.getValue())
                ));
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** Returns a copy with the updated proof status. */
    public HypothesisCandidate withProofStatus(CandidateProofStatus newStatus) {
        return new HypothesisCandidate(id, leftPattern, rightPattern,
            supportingPaths, supportingExpressions, assumptions,
            noveltyScore, newStatus, counterexampleStatus,
            parameterRelations, expressionPlaceholders, createdAt);
    }

    /** Returns a copy with a counterexample verdict (true = found, false = not found). */
    public HypothesisCandidate withCounterexampleStatus(boolean found) {
        return new HypothesisCandidate(id, leftPattern, rightPattern,
            supportingPaths, supportingExpressions, assumptions,
            noveltyScore, proofStatus, found,
            parameterRelations, expressionPlaceholders, createdAt);
    }

    /** Returns a copy with normalized assumptions. */
    public HypothesisCandidate withAssumptions(List<String> newAssumptions) {
        return new HypothesisCandidate(id, leftPattern, rightPattern,
            supportingPaths, supportingExpressions, newAssumptions,
            noveltyScore, proofStatus, counterexampleStatus,
            parameterRelations, expressionPlaceholders, createdAt);
    }

    /** Returns a copy with an updated ranking/novelty score. */
    public HypothesisCandidate withNoveltyScore(double newNoveltyScore) {
        return new HypothesisCandidate(id, leftPattern, rightPattern,
            supportingPaths, supportingExpressions, assumptions,
            newNoveltyScore, proofStatus, counterexampleStatus,
            parameterRelations, expressionPlaceholders, createdAt);
    }

    /** Creates a {@link HypothesisCandidate} from a {@link RuleCandidate}. */
    public static HypothesisCandidate from(RuleCandidate candidate, double noveltyScore) {
        return from(candidate, noveltyScore, List.of());
    }

    /** Creates a {@link HypothesisCandidate} and populates concrete witnesses from mined paths. */
    public static HypothesisCandidate from(
        RuleCandidate candidate,
        double noveltyScore,
        List<SuccessfulTransformationPath> paths
    ) {
        Set<String> supportingIds = Set.copyOf(candidate.supportingTransformationIds());
        List<ExpressionPair> witnesses = paths == null
            ? List.of()
            : paths.stream()
                .filter(path -> supportingIds.contains(path.id()))
                .map(path -> new ExpressionPair(path.originalExpression(), path.targetExpression()))
                .toList();
        return new HypothesisCandidate(
            candidate.canonicalHash(),
            candidate.leftPattern(),
            candidate.rightPattern(),
            candidate.supportingTransformationIds(),
            witnesses,
            List.of(),
            noveltyScore,
            candidate.proofStatus(),
            null,
            candidate.parameterRelations(),
            java.util.Map.of(),
            Instant.now()
        );
    }
}
