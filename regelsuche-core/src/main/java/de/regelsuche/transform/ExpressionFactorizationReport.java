package de.regelsuche.transform;

import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Expression adapter result around independently verified factorization data. */
public record ExpressionFactorizationReport(
    Status status,
    String detailCode,
    PolynomialSemanticView.Status semanticStatus,
    String sourcePolynomialMaterial,
    PolynomialWorkLedger work,
    FactorizationVerifier.ClaimStrength claimStrength,
    String verificationHash,
    List<RenderedFactorization> candidates
) {
    public ExpressionFactorizationReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(semanticStatus, "semanticStatus");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(claimStrength, "claimStrength");
        if (detailCode == null
                || detailCode.isBlank()
                || sourcePolynomialMaterial == null
                || verificationHash == null
                || (!verificationHash.isEmpty()
                    && !verificationHash.matches(
                        "sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                "expression factorization report is invalid");
        }
        candidates = List.copyOf(
            Objects.requireNonNull(candidates, "candidates"));
        if ((status == Status.GENERATED) == candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "expression factorization status/candidate mismatch");
        }
        if (status == Status.GENERATED
                && verificationHash.isEmpty()) {
            throw new IllegalArgumentException(
                "generated expression factorization requires verifier evidence");
        }
    }

    public boolean generated() {
        return status == Status.GENERATED;
    }

    public long totalWorkUnits() {
        return work.totalWorkUnits();
    }

    public static ExpressionFactorizationReport semanticFailure(
        Status status,
        String detailCode,
        PolynomialSemanticView.Status semanticStatus
    ) {
        return new ExpressionFactorizationReport(
            status,
            detailCode,
            semanticStatus,
            "",
            PolynomialWorkLedger.empty(),
            FactorizationVerifier.ClaimStrength.NONE,
            "",
            List.of());
    }

    public static ExpressionFactorizationReport coreFailure(
        Status status,
        PolynomialSemanticView.Status semanticStatus,
        String sourcePolynomialMaterial,
        FactorizationVerifier.Report<BigInteger> report
    ) {
        Objects.requireNonNull(report, "report");
        return new ExpressionFactorizationReport(
            status,
            report.detailCode(),
            semanticStatus,
            sourcePolynomialMaterial,
            report.work(),
            report.claimStrength(),
            report.verificationHash(),
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
        FactorizationVerifier.VerifiedCandidate<BigInteger> factorization,
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
