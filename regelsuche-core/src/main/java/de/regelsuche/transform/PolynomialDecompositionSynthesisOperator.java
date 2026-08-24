package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synthesizes exact quadratic-by-quadratic decompositions of bounded binary
 * homogeneous quartics by solving their coefficient constraints.
 *
 * <p>This operator does not store Sophie-Germain or any other concrete
 * identity. It first obtains a semantic polynomial over two arbitrary AST atoms
 * and then solves the general ansatz</p>
 *
 * <pre>
 * (a A^2 + b A B + c B^2) (d A^2 + e A B + f B^2).
 * </pre>
 *
 * <p>The resulting coefficient equations cover infinitely many concrete
 * substitutions for {@code A} and {@code B}. Every emitted candidate carries a
 * content-addressed certificate over the semantic source and the solved exact
 * integer coefficients.</p>
 */
public final class PolynomialDecompositionSynthesisOperator
        implements HypothesisOperator {
    public static final String RULE_ID =
        "hypothesis_polynomial_decomposition_synthesis";
    public static final String METHOD_ID =
        "regelsuche.binary-quartic-quadratic-decomposition/v1";

    private static final String PACK_ID = "core-polynomial-synthesis";
    private static final String LICENSE = "PROJECT";
    private static final int DEFAULT_MAX_CANDIDATES = 6;
    private static final int DEFAULT_MAX_COEFFICIENT_ABS = 32;
    private static final int DEFAULT_MAX_FACTOR_CONFIGURATIONS = 4_096;

    private final PolynomialSemanticView semanticView;
    private final int maxCandidates;
    private final int maxCoefficientAbs;
    private final int maxFactorConfigurations;

    public PolynomialDecompositionSynthesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public PolynomialDecompositionSynthesisOperator(int maxCandidates) {
        this(
            new PolynomialSemanticView(
                new PolynomialSemanticView.Budget(2, 4, 16, 256)),
            maxCandidates,
            DEFAULT_MAX_COEFFICIENT_ABS,
            DEFAULT_MAX_FACTOR_CONFIGURATIONS);
    }

    PolynomialDecompositionSynthesisOperator(
        PolynomialSemanticView semanticView,
        int maxCandidates,
        int maxCoefficientAbs,
        int maxFactorConfigurations
    ) {
        this.semanticView = Objects.requireNonNull(
            semanticView,
            "semanticView");
        this.maxCandidates = Math.max(0, maxCandidates);
        if (maxCoefficientAbs < 1 || maxFactorConfigurations < 1) {
            throw new IllegalArgumentException(
                "decomposition synthesis budget is invalid");
        }
        this.maxCoefficientAbs = maxCoefficientAbs;
        this.maxFactorConfigurations = maxFactorConfigurations;
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        SynthesisReport report = synthesize(expression);
        if (report.status() != Status.GENERATED) {
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
        PolynomialSemanticView.Analysis analysis =
            semanticView.analyze(expression);
        if (!analysis.supported()) {
            return SynthesisReport.failure(
                statusFor(analysis.status()),
                analysis.detailCode(),
                analysis.status(),
                0);
        }
        return synthesize(
            analysis.polynomial(),
            analysis.status());
    }

    /**
     * Synthesizes directly from an already validated exact integer polynomial.
     *
     * <p>This is the typed integration boundary for upstream semantic views.
     * It never renders the polynomial to text and never reparses coefficients
     * through {@code double}. The ordinary string entry point is only a parser
     * adapter in front of this method.</p>
     */
    public SynthesisReport synthesize(
        PolynomialSemanticView.Polynomial polynomial
    ) {
        return synthesize(
            Objects.requireNonNull(polynomial, "polynomial"),
            PolynomialSemanticView.Status.SUPPORTED);
    }

    private SynthesisReport synthesize(
        PolynomialSemanticView.Polynomial sourcePolynomial,
        PolynomialSemanticView.Status semanticStatus
    ) {
        PolynomialSemanticView.Polynomial polynomial = sourcePolynomial;
        if (polynomial.atoms().size() == 1 && polynomial.degree() <= 4) {
            polynomial = polynomial.homogenizeWithUnitAtom(4);
        }
        if (polynomial.atoms().size() != 2
                || !polynomial.isHomogeneousOfDegree(4)
                || polynomial.coefficient(4, 0).signum() == 0
                || polynomial.coefficient(0, 4).signum() == 0) {
            return SynthesisReport.failure(
                Status.NOT_BINARY_HOMOGENEOUS_QUARTIC,
                "REQUIRES_TWO_ATOMS_AND_NONZERO_EXTREME_QUARTIC_TERMS",
                semanticStatus,
                0);
        }
        if (maxCandidates == 0) {
            return SynthesisReport.failure(
                Status.CANDIDATE_BUDGET_ZERO,
                "MAX_CANDIDATES_IS_ZERO",
                semanticStatus,
                0);
        }

        Coefficients target = new Coefficients(
            polynomial.coefficient(4, 0),
            polynomial.coefficient(3, 1),
            polynomial.coefficient(2, 2),
            polynomial.coefficient(1, 3),
            polynomial.coefficient(0, 4));
        Work work = new Work();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        try {
            for (BigInteger a : divisors(target.c40())) {
                BigInteger d = target.c40().divide(a);
                if (!withinBound(d)) {
                    continue;
                }
                for (BigInteger c : divisors(target.c04())) {
                    work.consider(maxFactorConfigurations);
                    BigInteger f = target.c04().divide(c);
                    if (!withinBound(f)) {
                        continue;
                    }
                    for (MiddlePair middle : solveMiddle(
                            a,
                            d,
                            c,
                            f,
                            target)) {
                        if (!withinBound(middle.b())
                                || !withinBound(middle.e())) {
                            continue;
                        }
                        BigInteger reconstructedMiddle = a.multiply(f)
                            .add(middle.b().multiply(middle.e()))
                            .add(c.multiply(d));
                        if (!reconstructedMiddle.equals(target.c22())) {
                            continue;
                        }
                        FactorPair pair = FactorPair.canonical(
                            new Quadratic(a, middle.b(), c),
                            new Quadratic(d, middle.e(), f));
                        Candidate candidate = candidate(
                            polynomial,
                            target,
                            pair,
                            work.consideredConfigurations());
                        candidates.putIfAbsent(
                            candidate.certificateHash(),
                            candidate);
                    }
                }
            }
        } catch (BudgetExceeded exception) {
            return SynthesisReport.failure(
                Status.BUDGET_EXCEEDED,
                exception.getMessage(),
                semanticStatus,
                work.consideredConfigurations());
        }

        List<Candidate> ordered = candidates.values().stream()
            .sorted(Comparator
                .comparing(Candidate::factorMaterial)
                .thenComparing(Candidate::transformedExpression))
            .limit(maxCandidates)
            .toList();
        if (ordered.isEmpty()) {
            return SynthesisReport.failure(
                Status.NO_INTEGER_QUADRATIC_FACTORIZATION,
                "NO_BOUNDED_INTEGER_COEFFICIENT_SOLUTION",
                semanticStatus,
                work.consideredConfigurations());
        }
        return new SynthesisReport(
            Status.GENERATED,
            "EXACT_COEFFICIENT_CONSTRAINTS_SOLVED",
            semanticStatus,
            polynomial.canonicalMaterial(),
            work.consideredConfigurations(),
            ordered);
    }

    private List<MiddlePair> solveMiddle(
        BigInteger a,
        BigInteger d,
        BigInteger c,
        BigInteger f,
        Coefficients target
    ) {
        BigInteger determinant = d.multiply(c).subtract(a.multiply(f));
        if (determinant.signum() != 0) {
            BigInteger bNumerator = target.c31().multiply(c)
                .subtract(a.multiply(target.c13()));
            BigInteger eNumerator = d.multiply(target.c13())
                .subtract(f.multiply(target.c31()));
            if (!isDivisible(bNumerator, determinant)
                    || !isDivisible(eNumerator, determinant)) {
                return List.of();
            }
            return List.of(new MiddlePair(
                bNumerator.divide(determinant),
                eNumerator.divide(determinant)));
        }

        List<MiddlePair> result = new ArrayList<>();
        for (int value = -maxCoefficientAbs;
                value <= maxCoefficientAbs;
                value++) {
            BigInteger b = BigInteger.valueOf(value);
            BigInteger eNumerator =
                target.c31().subtract(d.multiply(b));
            if (!isDivisible(eNumerator, a)) {
                continue;
            }
            BigInteger e = eNumerator.divide(a);
            if (f.multiply(b).add(c.multiply(e)).equals(target.c13())) {
                result.add(new MiddlePair(b, e));
            }
        }
        return List.copyOf(result);
    }

    private Candidate candidate(
        PolynomialSemanticView.Polynomial polynomial,
        Coefficients target,
        FactorPair pair,
        int consideredConfigurations
    ) {
        PolynomialSemanticView.Atom first = polynomial.atoms().get(0);
        PolynomialSemanticView.Atom second = polynomial.atoms().get(1);
        Expr left = quadraticExpression(pair.left(), first, second);
        Expr right = quadraticExpression(pair.right(), first, second);
        String transformed = ExpressionFormatter.format(
            new BinaryExpr(left, BinaryOperator.MUL, right));
        String factorMaterial = pair.canonicalMaterial();
        String certificateHash = sha256(
            certificateMaterial(
                polynomial,
                target,
                pair,
                transformed));
        String applicationKey = RULE_ID
            + "|method=" + METHOD_ID
            + "|certificate=" + certificateHash
            + "|work=" + consideredConfigurations;
        return new Candidate(
            transformed,
            pair.left().coefficients(),
            pair.right().coefficients(),
            factorMaterial,
            certificateHash,
            applicationKey);
    }

    private Expr quadraticExpression(
        Quadratic quadratic,
        PolynomialSemanticView.Atom first,
        PolynomialSemanticView.Atom second
    ) {
        List<SignedTerm> terms = List.of(
            new SignedTerm(
                quadratic.a(),
                squaredAtom(first)),
            new SignedTerm(
                quadratic.b(),
                multipliedAtoms(first, second)),
            new SignedTerm(
                quadratic.c(),
                squaredAtom(second)));
        Expr result = null;
        for (SignedTerm term : terms) {
            if (term.coefficient().signum() == 0) {
                continue;
            }
            if (result == null) {
                result = scaled(
                    term.coefficient(),
                    term.expression());
                continue;
            }
            if (term.coefficient().signum() > 0) {
                result = new BinaryExpr(
                    result,
                    BinaryOperator.ADD,
                    scaled(term.coefficient(), term.expression()));
            } else {
                result = new BinaryExpr(
                    result,
                    BinaryOperator.SUB,
                    scaled(term.coefficient().abs(), term.expression()));
            }
        }
        if (result == null) {
            throw new IllegalStateException(
                "quadratic factor must not be zero");
        }
        return result;
    }

    private Expr squaredAtom(PolynomialSemanticView.Atom atom) {
        if (isStructuralUnit(atom)) {
            return new NumberExpr(1);
        }
        return new BinaryExpr(
            atom.expression(),
            BinaryOperator.POW,
            new NumberExpr(2));
    }

    private Expr multipliedAtoms(
        PolynomialSemanticView.Atom first,
        PolynomialSemanticView.Atom second
    ) {
        if (isStructuralUnit(first)) {
            return second.expression();
        }
        if (isStructuralUnit(second)) {
            return first.expression();
        }
        return new BinaryExpr(
            first.expression(),
            BinaryOperator.MUL,
            second.expression());
    }

    private boolean isStructuralUnit(PolynomialSemanticView.Atom atom) {
        return atom.key().equals("structural-unit:1");
    }

    private Expr scaled(BigInteger coefficient, Expr expression) {
        if (coefficient.equals(BigInteger.ONE)) {
            return expression;
        }
        if (coefficient.equals(BigInteger.ONE.negate())) {
            return new BinaryExpr(
                new NumberExpr(-1),
                BinaryOperator.MUL,
                expression);
        }
        return new BinaryExpr(
            new NumberExpr(coefficient.intValueExact()),
            BinaryOperator.MUL,
            expression);
    }

    private List<BigInteger> divisors(BigInteger value) {
        BigInteger absolute = value.abs();
        List<BigInteger> result = new ArrayList<>();
        for (int divisor = 1;
                divisor <= maxCoefficientAbs
                    && BigInteger.valueOf(divisor)
                        .compareTo(absolute) <= 0;
                divisor++) {
            BigInteger candidate = BigInteger.valueOf(divisor);
            if (absolute.mod(candidate).signum() == 0) {
                result.add(candidate);
                result.add(candidate.negate());
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private boolean withinBound(BigInteger value) {
        return value.abs().compareTo(
            BigInteger.valueOf(maxCoefficientAbs)) <= 0;
    }

    private static boolean isDivisible(
        BigInteger numerator,
        BigInteger denominator
    ) {
        return denominator.signum() != 0
            && numerator.remainder(denominator).signum() == 0;
    }

    private static Status statusFor(
        PolynomialSemanticView.Status status
    ) {
        return switch (status) {
            case PARSE_ERROR -> Status.PARSE_ERROR;
            case UNSUPPORTED -> Status.UNSUPPORTED_SEMANTIC_VIEW;
            case BUDGET_EXCEEDED -> Status.BUDGET_EXCEEDED;
            case SUPPORTED -> throw new IllegalArgumentException(
                "supported semantic view has no failure status");
        };
    }

    private static String certificateMaterial(
        PolynomialSemanticView.Polynomial polynomial,
        Coefficients target,
        FactorPair pair,
        String transformed
    ) {
        return new JsonWriter().beginObject()
            .property("schema", METHOD_ID)
            .property("semanticView", polynomial.viewId())
            .property("source", polynomial.canonicalMaterial())
            .stringArray("targetCoefficients", target.values().stream()
                .map(BigInteger::toString)
                .toList())
            .stringArray("leftFactor", pair.left().coefficients().stream()
                .map(BigInteger::toString)
                .toList())
            .stringArray("rightFactor", pair.right().coefficients().stream()
                .map(BigInteger::toString)
                .toList())
            .property("transformedExpression", transformed)
            .endObject()
            .toString();
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

    public enum Status {
        GENERATED,
        PARSE_ERROR,
        UNSUPPORTED_SEMANTIC_VIEW,
        NOT_BINARY_HOMOGENEOUS_QUARTIC,
        NO_INTEGER_QUADRATIC_FACTORIZATION,
        CANDIDATE_BUDGET_ZERO,
        BUDGET_EXCEEDED
    }

    public record SynthesisReport(
        Status status,
        String detailCode,
        PolynomialSemanticView.Status semanticStatus,
        String sourcePolynomialMaterial,
        int consideredConfigurations,
        List<Candidate> candidates
    ) {
        public SynthesisReport {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()
                    || semanticStatus == null
                    || sourcePolynomialMaterial == null
                    || consideredConfigurations < 0) {
                throw new IllegalArgumentException(
                    "decomposition synthesis report is invalid");
            }
            candidates = List.copyOf(candidates);
            if (status == Status.GENERATED && candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "generated report requires candidates");
            }
            if (status != Status.GENERATED && !candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "failed report must not expose candidates");
            }
        }

        static SynthesisReport failure(
            Status status,
            String detailCode,
            PolynomialSemanticView.Status semanticStatus,
            int consideredConfigurations
        ) {
            return new SynthesisReport(
                status,
                detailCode,
                semanticStatus,
                "",
                consideredConfigurations,
                List.of());
        }

        public boolean generated() {
            return status == Status.GENERATED;
        }
    }

    public record Candidate(
        String transformedExpression,
        List<BigInteger> leftCoefficients,
        List<BigInteger> rightCoefficients,
        String factorMaterial,
        String certificateHash,
        String applicationKey
    ) {
        public Candidate {
            if (transformedExpression == null
                    || transformedExpression.isBlank()
                    || factorMaterial == null
                    || factorMaterial.isBlank()
                    || certificateHash == null
                    || !certificateHash.matches("sha256:[0-9a-f]{64}")
                    || applicationKey == null
                    || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                    "decomposition candidate is invalid");
            }
            leftCoefficients = List.copyOf(leftCoefficients);
            rightCoefficients = List.copyOf(rightCoefficients);
            if (leftCoefficients.size() != 3
                    || rightCoefficients.size() != 3) {
                throw new IllegalArgumentException(
                    "quadratic factors require three coefficients");
            }
        }
    }

    private record Coefficients(
        BigInteger c40,
        BigInteger c31,
        BigInteger c22,
        BigInteger c13,
        BigInteger c04
    ) {
        private List<BigInteger> values() {
            return List.of(c40, c31, c22, c13, c04);
        }
    }

    private record MiddlePair(BigInteger b, BigInteger e) {
    }

    private record SignedTerm(
        BigInteger coefficient,
        Expr expression
    ) {
    }

    private record Quadratic(
        BigInteger a,
        BigInteger b,
        BigInteger c
    ) {
        private Quadratic {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            Objects.requireNonNull(c, "c");
        }

        private List<BigInteger> coefficients() {
            return List.of(a, b, c);
        }

        private Quadratic negate() {
            return new Quadratic(a.negate(), b.negate(), c.negate());
        }

        private int firstNonzeroSign() {
            for (BigInteger value : coefficients()) {
                if (value.signum() != 0) {
                    return value.signum();
                }
            }
            return 0;
        }

        private String canonicalMaterial() {
            return coefficients().stream()
                .map(BigInteger::toString)
                .collect(java.util.stream.Collectors.joining(","));
        }
    }

    private record FactorPair(Quadratic left, Quadratic right) {
        private static FactorPair canonical(
            Quadratic left,
            Quadratic right
        ) {
            FactorPair normalized = normalizeSign(
                new FactorPair(left, right));
            if (normalized.left().canonicalMaterial().compareTo(
                    normalized.right().canonicalMaterial()) > 0) {
                normalized = new FactorPair(
                    normalized.right(),
                    normalized.left());
                normalized = normalizeSign(normalized);
            }
            return normalized;
        }

        private static FactorPair normalizeSign(FactorPair pair) {
            return pair.left().firstNonzeroSign() < 0
                ? new FactorPair(
                    pair.left().negate(),
                    pair.right().negate())
                : pair;
        }

        private String canonicalMaterial() {
            return left.canonicalMaterial()
                + "|" + right.canonicalMaterial();
        }
    }

    private static final class Work {
        private int consideredConfigurations;

        private void consider(int maximum) {
            consideredConfigurations++;
            if (consideredConfigurations > maximum) {
                throw new BudgetExceeded(
                    "MAX_FACTOR_CONFIGURATIONS_EXCEEDED");
            }
        }

        private int consideredConfigurations() {
            return consideredConfigurations;
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
        private BudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }
}
