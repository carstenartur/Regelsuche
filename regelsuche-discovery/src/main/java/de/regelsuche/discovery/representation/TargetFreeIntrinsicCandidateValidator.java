package de.regelsuche.discovery.representation;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
import java.util.Objects;

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
 * <p>This validator performs the intrinsic soundness check &mdash; "is the
 * candidate still equivalent to the frozen source expression?" &mdash;
 * that any candidate, known or unknown, must satisfy before its intrinsic
 * or downstream salience can be assessed. Reference matching against a
 * historical corpus, and any later literature/novelty search, remain
 * separate, later steps and are intentionally not performed here.</p>
 */
public final class TargetFreeIntrinsicCandidateValidator {
    private TargetFreeIntrinsicCandidateValidator() {
    }

    /**
     * @param sourceExpression the frozen source expression the candidate was
     *     derived from
     * @param candidateExpression the frozen candidate expression to validate
     * @param equivalencePreserving whether the candidate was formed only
     *     through equivalence-preserving formation rules; when {@code false}
     *     the oracle is intentionally not consulted because the candidate is
     *     not claimed to be equivalence-preserving by construction
     * @param oracle the symbolic oracle used to independently confirm
     *     equivalence between source and candidate
     * @return the intrinsic validation outcome, computed without regard to
     *     any historical reference expression
     */
    public static IntrinsicValidation validate(
        String sourceExpression,
        String candidateExpression,
        boolean equivalencePreserving,
        OracleValidator oracle
    ) {
        Objects.requireNonNull(sourceExpression, "sourceExpression");
        Objects.requireNonNull(candidateExpression, "candidateExpression");
        Objects.requireNonNull(oracle, "oracle");
        if (!equivalencePreserving) {
            return new IntrinsicValidation(
                CandidateProofStatus.OBSERVED,
                "NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION");
        }
        try {
            OracleValidator.OracleValidation validation =
                oracle.validateEquivalence(
                    sourceExpression, candidateExpression);
            return new IntrinsicValidation(
                validation.status()
                    == OracleValidator.OracleValidationStatus.AGREE
                    ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                    : CandidateProofStatus.OBSERVED,
                validation.status().name());
        } catch (RuntimeException exception) {
            return new IntrinsicValidation(
                CandidateProofStatus.OBSERVED,
                "VALIDATOR_ERROR_" + exception.getClass().getSimpleName());
        }
    }

    /**
     * Outcome of an intrinsic, reference-independent candidate validation.
     */
    public record IntrinsicValidation(
        CandidateProofStatus status,
        String oracleStatus
    ) {
        public IntrinsicValidation {
            Objects.requireNonNull(status, "status");
            if (oracleStatus == null || oracleStatus.isBlank()) {
                throw new IllegalArgumentException(
                    "oracleStatus must not be blank");
            }
        }

        /**
         * @return whether the candidate was confirmed equivalent to its
         *     source expression, independent of historical reference
         *     matching
         */
        public boolean intrinsicallyVerified() {
            return status.atLeast(CandidateProofStatus.SYMBOLICALLY_VERIFIED);
        }
    }
}
