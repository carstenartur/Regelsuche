package de.regelsuche.transform;

import de.regelsuche.polynomial.BinaryQuarticFactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Expression adapter for one factorization engine.
 *
 * <p>The domain-aware polynomial and factorization contracts are authoritative;
 * this class only maps source syntax to the core verifier and renders its
 * issuer-owned candidates back into transformation strings.</p>
 */
public final class PolynomialDecompositionSynthesisOperator
        implements HypothesisOperator {
    public static final String RULE_ID =
        "hypothesis_polynomial_decomposition_synthesis";
    public static final String METHOD_ID =
        BinaryQuarticFactorizationEngine.ENGINE_ID;

    private static final String PACK_ID = "core-polynomial-synthesis";
    private static final String LICENSE = "PROJECT";
    private static final int DEFAULT_MAX_CANDIDATES = 6;
    private static final long DEFAULT_MAX_WORK_UNITS = 4_096;
    private static final FactorizationRequest.StructuralLimits
        DEFAULT_STRUCTURAL_LIMITS =
            new FactorizationRequest.StructuralLimits(
                2,
                4,
                16,
                4_096);

    private final PolynomialSemanticView semanticView;
    private final BinaryQuarticFactorizationEngine engine;
    private final int maxCandidates;
    private final long maxWorkUnits;

    public PolynomialDecompositionSynthesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public PolynomialDecompositionSynthesisOperator(int maxCandidates) {
        this(
            new PolynomialSemanticView(
                new PolynomialSemanticView.Budget(2, 4, 16, 256)),
            new BinaryQuarticFactorizationEngine(),
            maxCandidates,
            DEFAULT_MAX_WORK_UNITS);
    }

    PolynomialDecompositionSynthesisOperator(
        PolynomialSemanticView semanticView,
        BinaryQuarticFactorizationEngine engine,
        int maxCandidates,
        long maxWorkUnits
    ) {
        this.semanticView = Objects.requireNonNull(
            semanticView,
            "semanticView");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.maxCandidates = Math.max(0, maxCandidates);
        if (maxWorkUnits < 1) {
            throw new IllegalArgumentException(
                "factorization work budget must be positive");
        }
        this.maxWorkUnits = maxWorkUnits;
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        ExpressionFactorizationReport report = factorExpression(expression);
        if (!report.generated()) {
            return List.of();
        }
        return report.candidates().stream()
            .map(candidate -> new Transformation(
                RULE_ID,
                candidate.transformedExpression(),
                RewriteKind.FACTOR,
                true,
                -2,
                true,
                candidate.applicationKey(),
                List.of(),
                PACK_ID,
                LICENSE))
            .toList();
    }

    public ExpressionFactorizationReport factorExpression(
        String expression
    ) {
        PolynomialSemanticView.Analysis analysis =
            semanticView.analyze(expression);
        if (!analysis.supported()) {
            return ExpressionFactorizationReport.semanticFailure(
                statusFor(analysis.status()),
                analysis.detailCode(),
                analysis.status());
        }

        PolynomialSemanticView.PolynomialView view = analysis.view();
        if (view.polynomial().isZero()) {
            return ExpressionFactorizationReport.semanticFailure(
                ExpressionFactorizationReport.Status.NO_FACTORIZATION_FOUND,
                "ZERO_POLYNOMIAL_HAS_NO_FINITE_FACTORIZATION",
                analysis.status());
        }
        if (view.polynomial().ring().variableCount() == 1
                && view.polynomial().totalDegree() <= 4) {
            view = view.homogenizeWithUnitAtom(4);
        }

        FactorizationVerifier.Report<BigInteger> factorization =
            FactorizationVerifier.execute(
                engine,
                FactorizationRequest.verifiedDecomposition(
                    view.polynomial(),
                    DEFAULT_STRUCTURAL_LIMITS,
                    maxCandidates,
                    maxWorkUnits));
        if (!factorization.successful()) {
            return ExpressionFactorizationReport.coreFailure(
                statusFor(factorization.status()),
                analysis.status(),
                view.canonicalMaterial(),
                factorization);
        }

        PolynomialSemanticView.PolynomialView renderedView = view;
        FactorizationVerifier.Report<BigInteger> verifiedReport =
            factorization;
        List<ExpressionFactorizationReport.RenderedFactorization> candidates =
            factorization.candidates().stream()
                .map(candidate -> render(
                    candidate,
                    renderedView,
                    verifiedReport))
                .toList();
        return new ExpressionFactorizationReport(
            ExpressionFactorizationReport.Status.GENERATED,
            factorization.detailCode(),
            analysis.status(),
            view.canonicalMaterial(),
            factorization.work(),
            factorization.claimStrength(),
            factorization.verificationHash(),
            candidates);
    }

    private ExpressionFactorizationReport.RenderedFactorization render(
        FactorizationVerifier.VerifiedCandidate<BigInteger> candidate,
        PolynomialSemanticView.PolynomialView view,
        FactorizationVerifier.Report<BigInteger> report
    ) {
        List<String> multiplicands = new ArrayList<>();
        if (!candidate.unit().equals(BigInteger.ONE)) {
            multiplicands.add(candidate.unit().toString());
        }
        for (PolynomialFactor<BigInteger> factor : candidate.factors()) {
            String rendered = parenthesize(
                renderPolynomial(factor.polynomial(), view));
            multiplicands.add(factor.multiplicity() == 1
                ? rendered
                : rendered + " ^ " + factor.multiplicity());
        }
        if (!candidate.unresolvedRemainder().isOne()) {
            multiplicands.add(parenthesize(renderPolynomial(
                candidate.unresolvedRemainder(),
                view)));
        }
        if (multiplicands.isEmpty()) {
            throw new IllegalStateException(
                "factorization candidate rendered no expression");
        }
        String transformed = String.join(" * ", multiplicands);
        String applicationKey = RULE_ID
            + "|method=" + METHOD_ID
            + "|engineCertificate="
            + candidate.engineCertificateHash()
            + "|verificationCertificate="
            + candidate.verificationCertificateHash()
            + "|report=" + report.verificationHash()
            + "|work=" + report.work().totalWorkUnits();
        return new ExpressionFactorizationReport.RenderedFactorization(
            transformed,
            candidate,
            applicationKey);
    }

    private String renderPolynomial(
        SparsePolynomial<BigInteger> polynomial,
        PolynomialSemanticView.PolynomialView view
    ) {
        if (!polynomial.ring().equals(view.polynomial().ring())) {
            throw new IllegalArgumentException(
                "rendered polynomial ring does not match structural atoms");
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<Monomial, BigInteger> term
                : polynomial.terms().entrySet()) {
            BigInteger coefficient = term.getValue();
            String unsigned = renderUnsignedTerm(
                coefficient.abs(),
                term.getKey(),
                view);
            if (result.isEmpty()) {
                if (coefficient.signum() < 0) {
                    result.append('-');
                }
                result.append(unsigned);
            } else {
                result.append(coefficient.signum() < 0
                    ? " - "
                    : " + ");
                result.append(unsigned);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "zero polynomial cannot be rendered as a factor");
        }
        return result.toString();
    }

    private String renderUnsignedTerm(
        BigInteger coefficient,
        Monomial monomial,
        PolynomialSemanticView.PolynomialView view
    ) {
        String renderedMonomial = renderMonomial(monomial, view);
        if ("1".equals(renderedMonomial)) {
            return coefficient.toString();
        }
        return coefficient.equals(BigInteger.ONE)
            ? renderedMonomial
            : coefficient + " * " + renderedMonomial;
    }

    private String renderMonomial(
        Monomial monomial,
        PolynomialSemanticView.PolynomialView view
    ) {
        List<String> factors = new ArrayList<>();
        for (int index = 0; index < monomial.arity(); index++) {
            int exponent = monomial.exponent(index);
            if (exponent == 0) {
                continue;
            }
            PolynomialSemanticView.StructuralAtom atom = view.atom(index);
            if (atom.structuralUnit()) {
                continue;
            }
            String factor = parenthesize(atom.display());
            factors.add(exponent == 1
                ? factor
                : factor + " ^ " + exponent);
        }
        return factors.isEmpty()
            ? "1"
            : String.join(" * ", factors);
    }

    private static String parenthesize(String expression) {
        return "(" + expression + ")";
    }

    private static ExpressionFactorizationReport.Status statusFor(
        PolynomialSemanticView.Status status
    ) {
        return switch (status) {
            case PARSE_ERROR ->
                ExpressionFactorizationReport.Status.PARSE_ERROR;
            case UNSUPPORTED ->
                ExpressionFactorizationReport.Status
                    .UNSUPPORTED_SEMANTIC_VIEW;
            case BUDGET_EXCEEDED ->
                ExpressionFactorizationReport.Status.BUDGET_INCONCLUSIVE;
            case SUPPORTED -> throw new IllegalArgumentException(
                "supported semantic view has no failure status");
        };
    }

    private static ExpressionFactorizationReport.Status statusFor(
        FactorizationVerifier.Status status
    ) {
        return switch (status) {
            case COMPLETE_FACTORIZATION,
                PARTIAL_FACTORIZATION ->
                    ExpressionFactorizationReport.Status.GENERATED;
            case IRREDUCIBLE ->
                ExpressionFactorizationReport.Status.IRREDUCIBLE;
            case NO_FACTORIZATION_FOUND ->
                ExpressionFactorizationReport.Status.NO_FACTORIZATION_FOUND;
            case UNSUPPORTED_DOMAIN,
                UNSUPPORTED_REQUEST ->
                    ExpressionFactorizationReport.Status
                        .UNSUPPORTED_FACTORIZATION_REQUEST;
            case BUDGET_INCONCLUSIVE ->
                ExpressionFactorizationReport.Status.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                ExpressionFactorizationReport.Status.TECHNICAL_FAILURE;
        };
    }
}
