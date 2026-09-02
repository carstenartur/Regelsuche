package de.regelsuche.discovery.representation;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Validates a frozen target-free candidate against its own source
 * expression, independent of whether the candidate happens to match any
 * historical reference expression.
 *
 * <p>{@link TargetFreeHeldOutQualifier} only invokes the symbolic oracle
 * once a candidate has matched a known historical reference expression;
 * every non-reference candidate is left at {@code OBSERVED /
 * NOT_RUN_REFERENCE_MISS} without ever being checked for soundness. That
 * reference gate is correct for historical-target qualification, but it
 * makes the qualifier unable to validate a genuinely unknown candidate,
 * since an unknown candidate has no reference by definition.</p>
 *
 * <p>This validator first asks the available oracle the unconditional
 * question "is the candidate equivalent to the frozen source expression?".
 * An unconditional {@code AGREE} also establishes validity under any listed
 * assumptions. A non-agreement does not refute a candidate whose derivation
 * depends on assumptions, because {@link OracleValidator} has no
 * assumption-aware entry point. Such evidence is retained explicitly as
 * {@link ValidationScope#CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED} rather than
 * silently treating conditional validity as disproved. Both the stable
 * oracle status and its complete evidence string are retained. Historical
 * matching and later literature/novelty search remain separate steps.</p>
 */
public final class TargetFreeIntrinsicCandidateValidator {
    private TargetFreeIntrinsicCandidateValidator() {
    }

    /** Describes the logical scope actually established by the evidence. */
    public enum ValidationScope {
        /** The oracle result concerns unconditional source equivalence. */
        UNCONDITIONAL,
        /**
         * Listed assumptions may matter, but the available oracle did not
         * establish unconditional equivalence and cannot evaluate them.
         */
        CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
        /** No equivalence claim was made by the formation process. */
        NOT_APPLICABLE
    }

    /**
     * @param sourceExpression the frozen source expression the candidate was
     *     derived from
     * @param candidateExpression the frozen candidate expression to validate
     * @param assumptions the candidate's frozen assumptions; the current
     *     oracle can prove unconditional equivalence but cannot consume them
     * @param equivalencePreserving whether the candidate was formed only
     *     through equivalence-preserving formation rules; when {@code false}
     *     the oracle is intentionally not consulted because the candidate is
     *     not claimed to be equivalence-preserving by construction
     * @param oracle the symbolic oracle used to independently test
     *     unconditional equivalence between source and candidate
     * @return reference-independent validation evidence with its exact scope
     */
    public static IntrinsicValidation validate(
        String sourceExpression,
        String candidateExpression,
        List<String> assumptions,
        boolean equivalencePreserving,
        OracleValidator oracle
    ) {
        Objects.requireNonNull(sourceExpression, "sourceExpression");
        Objects.requireNonNull(candidateExpression, "candidateExpression");
        List<String> normalizedAssumptions = normalizeAssumptions(assumptions);
        Objects.requireNonNull(oracle, "oracle");
        if (!equivalencePreserving) {
            return new IntrinsicValidation(
                CandidateProofStatus.OBSERVED,
                "NOT_RUN_NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION",
                "The formation process did not claim source equivalence.",
                ValidationScope.NOT_APPLICABLE,
                normalizedAssumptions
            );
        }
        try {
            OracleValidator.OracleValidation validation =
                oracle.validateEquivalence(
                    sourceExpression, candidateExpression);
            if (validation.status()
                    == OracleValidator.OracleValidationStatus.AGREE) {
                return new IntrinsicValidation(
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED,
                    validation.status().name(),
                    validation.evidence(),
                    ValidationScope.UNCONDITIONAL,
                    normalizedAssumptions
                );
            }
            return new IntrinsicValidation(
                CandidateProofStatus.OBSERVED,
                validation.status().name(),
                validation.evidence(),
                unresolvedScope(normalizedAssumptions),
                normalizedAssumptions
            );
        } catch (RuntimeException exception) {
            return new IntrinsicValidation(
                CandidateProofStatus.OBSERVED,
                "VALIDATOR_ERROR_" + exception.getClass().getSimpleName(),
                errorEvidence(exception),
                unresolvedScope(normalizedAssumptions),
                normalizedAssumptions
            );
        }
    }

    private static String errorEvidence(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getName()
            : exception.getClass().getName() + ": " + message.trim();
    }

    private static ValidationScope unresolvedScope(List<String> assumptions) {
        return assumptions.isEmpty()
            ? ValidationScope.UNCONDITIONAL
            : ValidationScope.CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED;
    }

    private static List<String> normalizeAssumptions(
        List<String> assumptions
    ) {
        Objects.requireNonNull(assumptions, "assumptions");
        TreeSet<String> normalized = new TreeSet<>();
        for (String assumption : assumptions) {
            String value = Objects.requireNonNull(
                assumption, "assumption").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                    "assumption must not be blank");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /**
     * Outcome of an intrinsic, reference-independent candidate validation.
     */
    public record IntrinsicValidation(
        CandidateProofStatus status,
        String oracleStatus,
        String oracleEvidence,
        ValidationScope scope,
        List<String> assumptions
    ) {
        public IntrinsicValidation {
            Objects.requireNonNull(status, "status");
            if (oracleStatus == null || oracleStatus.isBlank()) {
                throw new IllegalArgumentException(
                    "oracleStatus must not be blank");
            }
            oracleEvidence = Objects.requireNonNull(
                oracleEvidence, "oracleEvidence");
            Objects.requireNonNull(scope, "scope");
            assumptions = normalizeAssumptions(assumptions);
            boolean verified = status.atLeast(
                CandidateProofStatus.SYMBOLICALLY_VERIFIED);
            if (scope
                    == ValidationScope.CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED
                    && (assumptions.isEmpty() || verified)) {
                throw new IllegalArgumentException(
                    "conditional unresolved evidence requires assumptions "
                        + "and must not be verified"
                );
            }
            if (scope == ValidationScope.UNCONDITIONAL
                    && !assumptions.isEmpty() && !verified) {
                throw new IllegalArgumentException(
                    "non-verified evidence with assumptions must preserve "
                        + "conditional uncertainty"
                );
            }
            if (scope == ValidationScope.NOT_APPLICABLE
                    && !oracleStatus.startsWith("NOT_RUN_")) {
                throw new IllegalArgumentException(
                    "not-applicable validation must not claim an oracle run"
                );
            }
            if (verified && scope != ValidationScope.UNCONDITIONAL) {
                throw new IllegalArgumentException(
                    "verified intrinsic evidence must be unconditional"
                );
            }
        }

        /**
         * @return whether unconditional source equivalence was confirmed,
         *     independent of historical reference matching
         */
        public boolean intrinsicallyVerified() {
            return status.atLeast(CandidateProofStatus.SYMBOLICALLY_VERIFIED);
        }

        /**
         * @return whether listed assumptions still require an assumption-aware
         *     evaluator before validity can be accepted or rejected
         */
        public boolean conditionalValidityUnresolved() {
            return scope
                == ValidationScope.CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED;
        }
    }
}
