package de.regelsuche.transform;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalPolynomial;
import de.regelsuche.scalar.ExactRationalPolynomialContentEvidence;
import de.regelsuche.scalar.ExactRationalPolynomialContentNormalizer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Synthesizes bounded exact rational factorizations of univariate quartics.
 *
 * <p>The operator preserves parser-issued exact literal evidence, extracts one
 * exact rational polynomial, normalizes it into an exact scalar and a primitive
 * integer polynomial, invokes the typed integer decomposition boundary, and
 * verifies both coefficient reassembly and the rendered expression. It is
 * deliberately not registered in a default discovery profile.</p>
 */
public final class ExactRationalPolynomialDecompositionSynthesisOperator
        implements HypothesisOperator {
    public static final String RULE_ID =
        "hypothesis_exact_rational_polynomial_decomposition_synthesis";
    public static final String METHOD_ID =
        "regelsuche.exact-rational-univariate-quartic-decomposition/v1";

    private static final String PACK_ID =
        "experimental-rational-polynomial-synthesis";
    private static final String LICENSE = "PROJECT";
    private static final int DEFAULT_MAX_CANDIDATES = 6;

    private final ExpressionParser parser;
    private final ExactRationalUnivariatePolynomialView rationalView;
    private final ExactRationalPolynomialContentNormalizer contentNormalizer;
    private final PolynomialDecompositionSynthesisOperator integerSynthesizer;
    private final int maxCandidates;

    public ExactRationalPolynomialDecompositionSynthesisOperator() {
        this(
            new ExpressionParser(),
            new ExactRationalUnivariatePolynomialView(),
            new ExactRationalPolynomialContentNormalizer(),
            new PolynomialDecompositionSynthesisOperator(),
            DEFAULT_MAX_CANDIDATES);
    }

    ExactRationalPolynomialDecompositionSynthesisOperator(
        ExpressionParser parser,
        ExactRationalUnivariatePolynomialView rationalView,
        ExactRationalPolynomialContentNormalizer contentNormalizer,
        PolynomialDecompositionSynthesisOperator integerSynthesizer,
        int maxCandidates
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.rationalView = Objects.requireNonNull(
            rationalView,
            "rationalView");
        this.contentNormalizer = Objects.requireNonNull(
            contentNormalizer,
            "contentNormalizer");
        this.integerSynthesizer = Objects.requireNonNull(
            integerSynthesizer,
            "integerSynthesizer");
        this.maxCandidates = Math.max(0, maxCandidates);
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        SynthesisReport report = synthesize(expression);
        if (!report.generated()) {
            return List.of();
        }
        return report.candidates().stream()
            .limit(maxCandidates)
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

    public SynthesisReport synthesize(String expression) {
        if (maxCandidates == 0) {
            return SynthesisReport.failure(
                Status.INTEGER_SYNTHESIS_FAILED,
                "MAX_CANDIDATES_IS_ZERO",
                "",
                "",
                0);
        }
        if (expression == null || expression.isBlank()) {
            return SynthesisReport.failure(
                Status.PARSE_ERROR,
                "EXPRESSION_BLANK",
                "",
                "",
                0);
        }

        ExactParsedTerm parsed;
        try {
            parsed = parser.parseExactTerm(expression);
        } catch (IllegalArgumentException exception) {
            return SynthesisReport.failure(
                Status.PARSE_ERROR,
                safeMessage(exception),
                "",
                "",
                0);
        }

        ExactRationalUnivariatePolynomialView.Analysis view =
            rationalView.analyze(parsed);
        if (!view.supported()) {
            return SynthesisReport.failure(
                view.status()
                    == ExactRationalUnivariatePolynomialView.Status
                        .BUDGET_EXCEEDED
                    ? Status.BUDGET_EXCEEDED
                    : Status.UNSUPPORTED_EXACT_POLYNOMIAL,
                view.detailCode(),
                "",
                "",
                0);
        }

        ExactRationalPolynomial sourcePolynomial =
            view.polynomial().orElseThrow();
        String sourceMaterial =
            sourcePolynomial.canonicalCoefficientText();
        if (sourcePolynomial.degree() != 4
                || view.variable().isBlank()) {
            return SynthesisReport.failure(
                Status.NOT_UNIVARIATE_QUARTIC,
                "REQUIRES_ONE_EXACT_UNIVARIATE_QUARTIC",
                sourceMaterial,
                "",
                0);
        }

        ExactRationalPolynomialContentEvidence content =
            contentNormalizer.normalize(sourcePolynomial);
        if (!content.normalized()) {
            return SynthesisReport.failure(
                contentLimitExceeded(content.status())
                    ? Status.BUDGET_EXCEEDED
                    : Status.CONTENT_NORMALIZATION_FAILED,
                content.detailCode(),
                sourceMaterial,
                content.certificateHash(),
                0);
        }

        ExactRationalPolynomialContentEvidence.Normalization normalization =
            content.normalization().orElseThrow();
        List<BigInteger> primitive =
            normalization.primitiveCoefficientsAscending();
        if (primitive.size() != 5
                || primitive.getLast().signum() == 0) {
            return SynthesisReport.failure(
                Status.NOT_UNIVARIATE_QUARTIC,
                "PRIMITIVE_INTEGER_POLYNOMIAL_MUST_HAVE_DEGREE_FOUR",
                sourceMaterial,
                content.certificateHash(),
                0);
        }

        PolynomialSemanticView.Polynomial typedPrimitive =
            typedPrimitivePolynomial(
                view.variable(),
                primitive,
                view.work().visitedNodes());
        PolynomialDecompositionSynthesisOperator.SynthesisReport integer =
            integerSynthesizer.synthesize(typedPrimitive);
        if (!integer.generated()) {
            return SynthesisReport.failure(
                integer.status()
                    == PolynomialDecompositionSynthesisOperator.Status
                        .BUDGET_EXCEEDED
                    ? Status.BUDGET_EXCEEDED
                    : Status.INTEGER_SYNTHESIS_FAILED,
                integer.detailCode(),
                sourceMaterial,
                content.certificateHash(),
                integer.consideredConfigurations());
        }

        List<Candidate> candidates = new ArrayList<>();
        for (PolynomialDecompositionSynthesisOperator.Candidate integerCandidate
                : integer.candidates()) {
            if (!reassemblesPrimitive(integerCandidate, primitive)
                    || !reassemblesSource(
                        integerCandidate,
                        normalization.scalar(),
                        sourcePolynomial)) {
                return SynthesisReport.failure(
                    Status.REASSEMBLY_FAILED,
                    "TYPED_INTEGER_FACTOR_REASSEMBLY_FAILED",
                    sourceMaterial,
                    content.certificateHash(),
                    integer.consideredConfigurations());
            }

            String transformed = render(
                normalization.scalar(),
                integerCandidate,
                view.variable());
            Verification verification = verifyRenderedCandidate(
                transformed,
                view.variable(),
                sourcePolynomial);
            if (verification.status() != VerificationStatus.VERIFIED) {
                return SynthesisReport.failure(
                    verification.status()
                        == VerificationStatus.NOT_REPRESENTABLE
                        ? Status.OUTPUT_NOT_REPRESENTABLE
                        : Status.REASSEMBLY_FAILED,
                    verification.detailCode(),
                    sourceMaterial,
                    content.certificateHash(),
                    integer.consideredConfigurations());
            }

            String certificateHash = certificate(
                view.canonicalMaterial(),
                content.certificateHash(),
                integerCandidate,
                normalization.scalar(),
                transformed,
                sourcePolynomial);
            String applicationKey = RULE_ID
                + "|method=" + METHOD_ID
                + "|certificate=" + certificateHash
                + "|integer=" + integerCandidate.certificateHash();
            candidates.add(new Candidate(
                transformed,
                normalization.scalar().canonicalText(),
                integerCandidate.leftCoefficients(),
                integerCandidate.rightCoefficients(),
                content.certificateHash(),
                integerCandidate.certificateHash(),
                certificateHash,
                applicationKey));
            if (candidates.size() >= maxCandidates) {
                break;
            }
        }

        if (candidates.isEmpty()) {
            return SynthesisReport.failure(
                Status.INTEGER_SYNTHESIS_FAILED,
                "NO_RATIONAL_CANDIDATE_WITHIN_OUTPUT_BOUNDS",
                sourceMaterial,
                content.certificateHash(),
                integer.consideredConfigurations());
        }
        return SynthesisReport.generated(
            sourceMaterial,
            content.certificateHash(),
            integer.consideredConfigurations(),
            candidates);
    }

    private Verification verifyRenderedCandidate(
        String transformed,
        String variable,
        ExactRationalPolynomial sourcePolynomial
    ) {
        ExactParsedTerm replay;
        try {
            replay = parser.parseExactTerm(transformed);
        } catch (IllegalArgumentException exception) {
            return new Verification(
                VerificationStatus.NOT_REPRESENTABLE,
                "RENDERED_EXACT_CANDIDATE_NOT_REPRESENTABLE:"
                    + safeMessage(exception));
        }
        ExactRationalUnivariatePolynomialView.Analysis analysis =
            rationalView.analyze(replay);
        if (!analysis.supported()) {
            return new Verification(
                VerificationStatus.NOT_REPRESENTABLE,
                "RENDERED_EXACT_CANDIDATE_UNSUPPORTED:"
                    + analysis.detailCode());
        }
        if (!variable.equals(analysis.variable())
                || !sourcePolynomial.equals(
                    analysis.polynomial().orElseThrow())) {
            return new Verification(
                VerificationStatus.MISMATCH,
                "RENDERED_EXACT_CANDIDATE_REASSEMBLY_FAILED");
        }
        return new Verification(
            VerificationStatus.VERIFIED,
            "RENDERED_EXACT_CANDIDATE_VERIFIED");
    }

    private static PolynomialSemanticView.Polynomial typedPrimitivePolynomial(
        String variable,
        List<BigInteger> coefficientsAscending,
        int visitedNodes
    ) {
        TreeMap<PolynomialSemanticView.Monomial, BigInteger> coefficients =
            new TreeMap<>();
        for (int exponent = 0;
                exponent < coefficientsAscending.size();
                exponent++) {
            BigInteger coefficient = coefficientsAscending.get(exponent);
            if (coefficient.signum() != 0) {
                coefficients.put(
                    new PolynomialSemanticView.Monomial(List.of(exponent)),
                    coefficient);
            }
        }
        return new PolynomialSemanticView.Polynomial(
            PolynomialSemanticView.VIEW_ID,
            List.of(new PolynomialSemanticView.Atom(
                "exact-source-variable:" + variable,
                variable,
                new VariableExpr(variable))),
            coefficients,
            coefficientsAscending.size() - 1,
            coefficients.size() <= 1,
            visitedNodes);
    }

    private static boolean reassemblesPrimitive(
        PolynomialDecompositionSynthesisOperator.Candidate candidate,
        List<BigInteger> primitive
    ) {
        return multiply(candidate).equals(primitive);
    }

    private static boolean reassemblesSource(
        PolynomialDecompositionSynthesisOperator.Candidate candidate,
        ExactRational scalar,
        ExactRationalPolynomial source
    ) {
        List<BigInteger> product = multiply(candidate);
        for (int exponent = 0; exponent < product.size(); exponent++) {
            ExactRational reconstructed = ExactRational.integer(
                product.get(exponent)).multiply(scalar);
            if (!reconstructed.equals(source.coefficient(exponent))) {
                return false;
            }
        }
        return true;
    }

    private static List<BigInteger> multiply(
        PolynomialDecompositionSynthesisOperator.Candidate candidate
    ) {
        List<BigInteger> left = candidate.leftCoefficients();
        List<BigInteger> right = candidate.rightCoefficients();
        BigInteger a = left.get(0);
        BigInteger b = left.get(1);
        BigInteger c = left.get(2);
        BigInteger d = right.get(0);
        BigInteger e = right.get(1);
        BigInteger f = right.get(2);
        return List.of(
            c.multiply(f),
            b.multiply(f).add(c.multiply(e)),
            a.multiply(f).add(b.multiply(e)).add(c.multiply(d)),
            a.multiply(e).add(b.multiply(d)),
            a.multiply(d));
    }

    private static String render(
        ExactRational scalar,
        PolynomialDecompositionSynthesisOperator.Candidate candidate,
        String variable
    ) {
        String product = "(" + renderQuadratic(
            candidate.leftCoefficients(),
            variable) + ") * (" + renderQuadratic(
                candidate.rightCoefficients(),
                variable) + ")";
        if (scalar.isOne()) {
            return product;
        }
        if (scalar.isNegativeOne()) {
            return "0 - (" + product + ")";
        }
        return "(" + renderScalar(scalar) + ") * (" + product + ")";
    }

    private static String renderScalar(ExactRational scalar) {
        BigInteger numerator = scalar.numerator().abs();
        String positive = scalar.denominator().equals(BigInteger.ONE)
            ? numerator.toString()
            : numerator + " / " + scalar.denominator();
        return scalar.signum() < 0
            ? "0 - " + positive
            : positive;
    }

    private static String renderQuadratic(
        List<BigInteger> coefficients,
        String variable
    ) {
        StringBuilder result = new StringBuilder();
        appendTerm(result, coefficients.get(0), variable + " ^ 2");
        appendTerm(result, coefficients.get(1), variable);
        appendTerm(result, coefficients.get(2), "");
        if (result.isEmpty()) {
            throw new IllegalStateException(
                "integer synthesis emitted a zero factor");
        }
        return result.toString();
    }

    private static void appendTerm(
        StringBuilder target,
        BigInteger coefficient,
        String monomial
    ) {
        if (coefficient.signum() == 0) {
            return;
        }
        BigInteger absolute = coefficient.abs();
        String term;
        if (monomial.isEmpty()) {
            term = absolute.toString();
        } else if (absolute.equals(BigInteger.ONE)) {
            term = monomial;
        } else {
            term = absolute + " * " + monomial;
        }

        if (target.isEmpty()) {
            if (coefficient.signum() < 0) {
                target.append("0 - ");
            }
            target.append(term);
            return;
        }
        target.append(coefficient.signum() < 0 ? " - " : " + ")
            .append(term);
    }

    private static boolean contentLimitExceeded(
        ExactRationalPolynomialContentNormalizer.Status status
    ) {
        return switch (status) {
            case DEGREE_LIMIT_EXCEEDED,
                COEFFICIENT_LIMIT_EXCEEDED,
                INTERMEDIATE_LIMIT_EXCEEDED,
                WORK_LIMIT_EXCEEDED -> true;
            case NORMALIZED, ZERO_POLYNOMIAL -> false;
        };
    }

    private static String certificate(
        String viewMaterial,
        String contentCertificate,
        PolynomialDecompositionSynthesisOperator.Candidate integerCandidate,
        ExactRational scalar,
        String transformed,
        ExactRationalPolynomial sourcePolynomial
    ) {
        StringBuilder material = new StringBuilder();
        append(material, METHOD_ID);
        append(material, viewMaterial);
        append(material, contentCertificate);
        append(material, integerCandidate.certificateHash());
        append(material, integerCandidate.applicationKey());
        append(material, scalar.canonicalText());
        append(material, transformed);
        append(material, sourcePolynomial.canonicalCoefficientText());
        integerCandidate.leftCoefficients().forEach(value ->
            append(material, value.toString()));
        integerCandidate.rightCoefficients().forEach(value ->
            append(material, value.toString()));
        return sha256(material.toString());
    }

    private static void append(StringBuilder target, String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        target.append(length).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
    }

    public enum Status {
        GENERATED,
        PARSE_ERROR,
        UNSUPPORTED_EXACT_POLYNOMIAL,
        BUDGET_EXCEEDED,
        CONTENT_NORMALIZATION_FAILED,
        NOT_UNIVARIATE_QUARTIC,
        INTEGER_SYNTHESIS_FAILED,
        REASSEMBLY_FAILED,
        OUTPUT_NOT_REPRESENTABLE
    }

    public record SynthesisReport(
        Status status,
        String detailCode,
        String sourcePolynomialMaterial,
        String contentCertificateHash,
        int consideredConfigurations,
        List<Candidate> candidates
    ) {
        public SynthesisReport {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()
                    || sourcePolynomialMaterial == null
                    || contentCertificateHash == null
                    || consideredConfigurations < 0) {
                throw new IllegalArgumentException(
                    "exact rational synthesis report is invalid");
            }
            candidates = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
            if (status == Status.GENERATED && candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "generated rational report requires candidates");
            }
            if (status != Status.GENERATED && !candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "failed rational report must not expose candidates");
            }
        }

        private static SynthesisReport failure(
            Status status,
            String detailCode,
            String sourcePolynomialMaterial,
            String contentCertificateHash,
            int consideredConfigurations
        ) {
            return new SynthesisReport(
                status,
                detailCode,
                sourcePolynomialMaterial,
                contentCertificateHash,
                consideredConfigurations,
                List.of());
        }

        private static SynthesisReport generated(
            String sourcePolynomialMaterial,
            String contentCertificateHash,
            int consideredConfigurations,
            List<Candidate> candidates
        ) {
            return new SynthesisReport(
                Status.GENERATED,
                "EXACT_RATIONAL_QUARTIC_DECOMPOSITION_VERIFIED",
                sourcePolynomialMaterial,
                contentCertificateHash,
                consideredConfigurations,
                candidates);
        }

        public boolean generated() {
            return status == Status.GENERATED;
        }
    }

    public record Candidate(
        String transformedExpression,
        String scalar,
        List<BigInteger> leftCoefficients,
        List<BigInteger> rightCoefficients,
        String contentCertificateHash,
        String integerCertificateHash,
        String certificateHash,
        String applicationKey
    ) {
        public Candidate {
            if (transformedExpression == null
                    || transformedExpression.isBlank()
                    || scalar == null
                    || scalar.isBlank()
                    || contentCertificateHash == null
                    || !contentCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || integerCertificateHash == null
                    || !integerCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || certificateHash == null
                    || !certificateHash.matches("sha256:[0-9a-f]{64}")
                    || applicationKey == null
                    || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                    "exact rational decomposition candidate is invalid");
            }
            leftCoefficients = List.copyOf(
                Objects.requireNonNull(
                    leftCoefficients,
                    "leftCoefficients"));
            rightCoefficients = List.copyOf(
                Objects.requireNonNull(
                    rightCoefficients,
                    "rightCoefficients"));
            if (leftCoefficients.size() != 3
                    || rightCoefficients.size() != 3) {
                throw new IllegalArgumentException(
                    "rational quadratic factors require three coefficients");
            }
        }
    }

    private enum VerificationStatus {
        VERIFIED,
        NOT_REPRESENTABLE,
        MISMATCH
    }

    private record Verification(
        VerificationStatus status,
        String detailCode
    ) {
    }
}
