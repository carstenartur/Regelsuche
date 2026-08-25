package de.regelsuche.transform;

import de.regelsuche.polynomial.FactorizationCandidate;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Expression adapter result around the domain-neutral factorization core. */
public record ExpressionFactorizationReport(
    Status status,
    String detailCode,
    PolynomialSemanticView.Status semanticStatus,
    String sourcePolynomialMaterial,
    long arithmeticSteps,
    List<RenderedFactorization> candidates
) {
    public ExpressionFactorizationReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(semanticStatus, "semanticStatus");
        if (detailCode == null
                || detailCode.isBlank()
                || sourcePolynomialMaterial == null
                || arithmeticSteps < 0) {
            throw new IllegalArgumentException(
                "expression factorization report is invalid");
        }
        candidates = List.copyOf(
            Objects.requireNonNull(candidates, "candidates"));
        if ((status == Status.GENERATED) == candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "expression factorization status/candidate mismatch");
        }
    }

    public boolean generated() {
        return status == Status.GENERATED;
    }

    public static ExpressionFactorizationReport failure(
        Status status,
        String detailCode,
        PolynomialSemanticView.Status semanticStatus,
        String sourcePolynomialMaterial,
        long arithmeticSteps
    ) {
        return new ExpressionFactorizationReport(
            status,
            detailCode,
            semanticStatus,
            sourcePolynomialMaterial,
            arithmeticSteps,
            List.of());
    }

    public enum Status {
        GENERATED,
        PARSE_ERROR,
        UNSUPPORTED_SEMANTIC_VIEW,
        UNSUPPORTED_FACTORIZATION_REQUEST,
        NO_FACTORIZATION_FOUND,
        IRREDUCIBLE,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    public record RenderedFactorization(
        String transformedExpression,
        FactorizationCandidate<BigInteger> factorization,
        String applicationKey
    ) {
        public RenderedFactorization {
            if (transformedExpression == null
                    || transformedExpression.isBlank()
                    || factorization == null
                    || applicationKey == null
                    || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                    "rendered factorization is invalid");
            }
        }
    }
}
