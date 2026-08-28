package de.regelsuche.polynomial;

import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministically renders one verifier-issued exact-rational factorization
 * candidate as parser-compatible expression syntax.
 *
 * <p>The renderer is not a mathematical authority. It accepts only
 * {@link FactorizationVerifier.VerifiedCandidate} values issued by the
 * verifier, retains their certificate identity and accounts every emitted code
 * unit under an explicit work and representation policy.</p>
 */
public final class ExactFactorizationExpressionRenderer {
    public static final String RENDERER_ID =
        "regelsuche.exact-factorization-expression-renderer/v1";
    public static final int MAX_FACTORS = 4_096;
    public static final int MAX_POLYNOMIAL_TERMS = 65_536;
    public static final int MAX_EXPONENT = 1_000_000;
    public static final int MAX_COEFFICIENT_BITS = 1_000_000;
    public static final int MAX_OUTPUT_CODE_UNITS = 1_000_000;
    public static final long MAX_WORK_UNITS = 5_000_000L;
    private static final BigInteger MAX_PARSER_LITERAL_MAGNITUDE =
        BigInteger.TEN.pow(ExactRationalDomain.MAX_DIGITS)
            .subtract(BigInteger.ONE);
    private static final int MAX_PARSER_LITERAL_BITS =
        MAX_PARSER_LITERAL_MAGNITUDE.bitLength();

    private final Policy policy;

    public ExactFactorizationExpressionRenderer() {
        this(Policy.boundedDefaults());
    }

    public ExactFactorizationExpressionRenderer(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Policy policy() {
        return policy;
    }

    public Result render(
        FactorizationVerifier.VerifiedCandidate<ExactRational> candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Work work = new Work(policy.maxWorkUnits());
        try {
            PolynomialRing<ExactRational> ring =
                candidate.unresolvedRemainder().ring();
            requireSupportedRing(ring);
            requireCandidateWithinRepresentationLimits(candidate, work);

            BoundedText output = new BoundedText(policy, work);
            int components = 0;
            if (!candidate.unit().isOne()) {
                appendComponentSeparator(output, components++);
                output.append(renderRational(candidate.unit()));
                work.consume("render.unit-components", 1);
            }
            for (PolynomialFactor<ExactRational> factor
                    : candidate.factors()) {
                appendComponentSeparator(output, components++);
                renderFactor(output, factor, ring, work);
            }
            if (!candidate.unresolvedRemainder().isOne()) {
                appendComponentSeparator(output, components++);
                renderPolynomialFactor(
                    output,
                    candidate.unresolvedRemainder(),
                    1,
                    ring,
                    work);
                work.consume("render.unresolved-remainders", 1);
            }
            if (components == 0) {
                throw new IllegalStateException(
                    "verified candidate rendered no expression component");
            }
            return Result.rendered(
                policy,
                candidate,
                output.toString(),
                work.ledger());
        } catch (RepresentationLimitReached exception) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                policy,
                candidate,
                work.ledger());
        } catch (UnsupportedCandidate exception) {
            return Result.failure(
                Status.UNSUPPORTED,
                exception.getMessage(),
                policy,
                candidate,
                work.ledger());
        }
    }

    private void requireSupportedRing(
        PolynomialRing<ExactRational> ring
    ) {
        if (!ExactRationalField.DOMAIN_ID.equals(
                ring.coefficientDomain().id())) {
            throw unsupported("RENDERER_REQUIRES_EXACT_RATIONAL_DOMAIN");
        }
        if (ring.variableCount() != 1) {
            throw unsupported("RENDERER_REQUIRES_UNIVARIATE_RING");
        }
        String variable = ring.variables().getFirst().id();
        if (!isParserIdentifier(variable)) {
            throw unsupported(
                "POLYNOMIAL_VARIABLE_IS_NOT_PARSER_COMPATIBLE");
        }
    }

    private void requireCandidateWithinRepresentationLimits(
        FactorizationVerifier.VerifiedCandidate<ExactRational> candidate,
        Work work
    ) {
        int factors = candidate.factors().size();
        boolean hasRemainder =
            !candidate.unresolvedRemainder().isOne();
        if (factors > policy.maxFactors()
                || hasRemainder && factors >= policy.maxFactors()) {
            throw representationLimit("MAX_FACTORS_EXCEEDED");
        }
        checkCoefficient(candidate.unit(), work);
        int terms = 0;
        for (PolynomialFactor<ExactRational> factor
                : candidate.factors()) {
            if (factor.multiplicity() > policy.maxExponent()) {
                throw representationLimit("MAX_EXPONENT_EXCEEDED");
            }
            terms = addTermCount(
                terms,
                checkedTermCount(factor.polynomial()));
            checkPolynomial(factor.polynomial(), work);
            work.consume("render.factor-records", 1);
        }
        if (hasRemainder) {
            terms = addTermCount(
                terms,
                checkedTermCount(candidate.unresolvedRemainder()));
            checkPolynomial(candidate.unresolvedRemainder(), work);
        }
    }

    private int addTermCount(int current, int additional) {
        if (additional > policy.maxPolynomialTerms() - current) {
            throw representationLimit(
                "MAX_POLYNOMIAL_TERMS_EXCEEDED");
        }
        return current + additional;
    }

    private int checkedTermCount(
        SparsePolynomial<ExactRational> polynomial
    ) {
        if (polynomial.isZero()) {
            throw unsupported("ZERO_POLYNOMIAL_CANNOT_BE_RENDERED");
        }
        return polynomial.termCount();
    }

    private void checkPolynomial(
        SparsePolynomial<ExactRational> polynomial,
        Work work
    ) {
        if (polynomial.ring().variableCount() != 1
                || !ExactRationalField.DOMAIN_ID.equals(
                    polynomial.ring().coefficientDomain().id())) {
            throw unsupported("FACTORIZATION_CANDIDATE_RING_MISMATCH");
        }
        for (Map.Entry<Monomial, ExactRational> term
                : polynomial.terms().entrySet()) {
            work.consume("render.inspected-polynomial-terms", 1);
            if (term.getKey().exponent(0) > policy.maxExponent()) {
                throw representationLimit("MAX_EXPONENT_EXCEEDED");
            }
            checkCoefficient(term.getValue(), work);
        }
    }

    private void checkCoefficient(
        ExactRational coefficient,
        Work work
    ) {
        work.consume("render.inspected-coefficients", 1);
        if (ExactRationalField.INSTANCE.bitLength(coefficient)
                > policy.maxCoefficientBits()) {
            throw representationLimit(
                "MAX_COEFFICIENT_BIT_LENGTH_EXCEEDED");
        }
        if (exceedsExactParserDigitLimit(
                    coefficient.numerator().abs())
                || exceedsExactParserDigitLimit(
                    coefficient.denominator())) {
            throw representationLimit(
                "MAX_COEFFICIENT_DIGITS_EXCEEDED");
        }
    }

    private static boolean exceedsExactParserDigitLimit(
        BigInteger value
    ) {
        return value.bitLength() > MAX_PARSER_LITERAL_BITS
            || value.compareTo(MAX_PARSER_LITERAL_MAGNITUDE) > 0;
    }

    private void renderFactor(
        BoundedText output,
        PolynomialFactor<ExactRational> factor,
        PolynomialRing<ExactRational> expectedRing,
        Work work
    ) {
        renderPolynomialFactor(
            output,
            factor.polynomial(),
            factor.multiplicity(),
            expectedRing,
            work);
    }

    private void renderPolynomialFactor(
        BoundedText output,
        SparsePolynomial<ExactRational> polynomial,
        int multiplicity,
        PolynomialRing<ExactRational> expectedRing,
        Work work
    ) {
        if (!expectedRing.equals(polynomial.ring())) {
            throw unsupported("FACTORIZATION_CANDIDATE_RING_MISMATCH");
        }
        output.append("(");
        renderPolynomial(output, polynomial, work);
        output.append(")");
        if (multiplicity > 1) {
            output.append(" ^ ");
            output.append(Integer.toString(multiplicity));
            work.consume("render.factor-exponents", 1);
        }
    }

    private void renderPolynomial(
        BoundedText output,
        SparsePolynomial<ExactRational> polynomial,
        Work work
    ) {
        String variable = polynomial.ring().variables().getFirst().id();
        boolean first = true;
        for (Map.Entry<Monomial, ExactRational> entry
                : polynomial.terms().entrySet()) {
            ExactRational coefficient = entry.getValue();
            boolean negative = coefficient.signum() < 0;
            ExactRational magnitude = coefficient.abs();
            if (first) {
                if (negative) {
                    output.append("-");
                }
                first = false;
            } else {
                output.append(negative ? " - " : " + ");
            }
            renderTerm(
                output,
                magnitude,
                entry.getKey().exponent(0),
                variable,
                work);
            work.consume("render.polynomial-terms", 1);
        }
        if (first) {
            throw unsupported("ZERO_POLYNOMIAL_CANNOT_BE_RENDERED");
        }
    }

    private void renderTerm(
        BoundedText output,
        ExactRational coefficient,
        int exponent,
        String variable,
        Work work
    ) {
        if (exponent == 0) {
            output.append(renderRational(coefficient));
            return;
        }
        if (!coefficient.isOne()) {
            output.append(renderRational(coefficient));
            output.append(" * ");
        }
        output.append(variable);
        if (exponent > 1) {
            output.append(" ^ ");
            output.append(Integer.toString(exponent));
            work.consume("render.monomial-exponents", 1);
        }
    }

    private static String renderRational(ExactRational value) {
        if (value.isInteger()) {
            return value.numerator().toString();
        }
        return "("
            + value.numerator()
            + " / "
            + value.denominator()
            + ")";
    }

    private static void appendComponentSeparator(
        BoundedText output,
        int existingComponents
    ) {
        if (existingComponents > 0) {
            output.append(" * ");
        }
    }

    private static boolean isParserIdentifier(String value) {
        if (value == null
                || value.isEmpty()
                || !Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '_') {
                return false;
            }
        }
        return true;
    }

    private static UnsupportedCandidate unsupported(String detailCode) {
        return new UnsupportedCandidate(detailCode);
    }

    private static RepresentationLimitReached representationLimit(
        String detailCode
    ) {
        return new RepresentationLimitReached(detailCode);
    }

    public enum Status {
        RENDERED,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE
    }

    /** Explicit representation and work bounds for one rendered candidate. */
    public record Policy(
        int maxFactors,
        int maxPolynomialTerms,
        int maxExponent,
        int maxCoefficientBits,
        int maxOutputCodeUnits,
        long maxWorkUnits
    ) {
        public Policy {
            if (maxFactors < 1
                    || maxFactors > MAX_FACTORS
                    || maxPolynomialTerms < 1
                    || maxPolynomialTerms > MAX_POLYNOMIAL_TERMS
                    || maxExponent < 1
                    || maxExponent > MAX_EXPONENT
                    || maxCoefficientBits < 1
                    || maxCoefficientBits > MAX_COEFFICIENT_BITS
                    || maxOutputCodeUnits < 1
                    || maxOutputCodeUnits > MAX_OUTPUT_CODE_UNITS
                    || maxWorkUnits < maxOutputCodeUnits
                    || maxWorkUnits > MAX_WORK_UNITS) {
                throw new IllegalArgumentException(
                    "exact factorization renderer policy is invalid");
            }
        }

        public static Policy boundedDefaults() {
            return new Policy(
                256,
                4_096,
                ExactParsedUnivariatePolynomialView.MAX_DEGREE,
                ExactParsedUnivariatePolynomialView
                    .MAX_COEFFICIENT_BITS,
                100_000,
                200_000);
        }

        public String canonicalMaterial() {
            return maxFactors + ":"
                + maxPolynomialTerms + ":"
                + maxExponent + ":"
                + maxCoefficientBits + ":"
                + maxOutputCodeUnits + ":"
                + maxWorkUnits;
        }
    }

    /** Content-addressed rendering result bound to verifier evidence. */
    public static final class Result {
        private final Status status;
        private final String detailCode;
        private final Policy policy;
        private final String candidateCertificateHash;
        private final Optional<String> expression;
        private final PolynomialWorkLedger work;
        private final String certificateHash;

        private Result(
            Status status,
            String detailCode,
            Policy policy,
            String candidateCertificateHash,
            Optional<String> expression,
            PolynomialWorkLedger work
        ) {
            this.status = Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "renderer detail code must not be blank");
            }
            this.detailCode = detailCode;
            this.policy = Objects.requireNonNull(policy, "policy");
            if (candidateCertificateHash == null
                    || !candidateCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "renderer candidate certificate is invalid");
            }
            this.candidateCertificateHash = candidateCertificateHash;
            this.expression = Objects.requireNonNull(
                expression,
                "expression");
            this.work = Objects.requireNonNull(work, "work");
            if ((status == Status.RENDERED) != expression.isPresent()) {
                throw new IllegalArgumentException(
                    "renderer status/payload mismatch");
            }
            if (!work.within(policy.maxWorkUnits())) {
                throw new IllegalArgumentException(
                    "renderer work exceeds policy");
            }
            this.certificateHash = PolynomialEvidence.sha256(
                evidenceMaterial());
        }

        private static Result rendered(
            Policy policy,
            FactorizationVerifier.VerifiedCandidate<ExactRational> candidate,
            String expression,
            PolynomialWorkLedger work
        ) {
            return new Result(
                Status.RENDERED,
                "EXACT_FACTORIZATION_RENDERED",
                policy,
                candidate.verificationCertificateHash(),
                Optional.of(expression),
                work);
        }

        private static Result failure(
            Status status,
            String detailCode,
            Policy policy,
            FactorizationVerifier.VerifiedCandidate<ExactRational> candidate,
            PolynomialWorkLedger work
        ) {
            if (status == Status.RENDERED) {
                throw new IllegalArgumentException(
                    "rendered status requires expression syntax");
            }
            return new Result(
                status,
                detailCode,
                policy,
                candidate.verificationCertificateHash(),
                Optional.empty(),
                work);
        }

        public Status status() {
            return status;
        }

        public String detailCode() {
            return detailCode;
        }

        public Policy policy() {
            return policy;
        }

        public String candidateCertificateHash() {
            return candidateCertificateHash;
        }

        public Optional<String> expression() {
            return expression;
        }

        public PolynomialWorkLedger work() {
            return work;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean rendered() {
            return status == Status.RENDERED;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder(evidenceMaterial());
            PolynomialEvidence.append(result, certificateHash);
            return result.toString();
        }

        private String evidenceMaterial() {
            StringBuilder result = new StringBuilder(RENDERER_ID);
            PolynomialEvidence.append(result, status.name());
            PolynomialEvidence.append(result, detailCode);
            PolynomialEvidence.append(
                result,
                policy.canonicalMaterial());
            PolynomialEvidence.append(
                result,
                candidateCertificateHash);
            PolynomialEvidence.append(
                result,
                expression.orElse(""));
            PolynomialEvidence.append(
                result,
                work.canonicalMaterial());
            return result.toString();
        }
    }

    private static final class BoundedText {
        private final Policy policy;
        private final Work work;
        private final StringBuilder value = new StringBuilder();

        private BoundedText(Policy policy, Work work) {
            this.policy = policy;
            this.work = work;
        }

        private void append(String text) {
            Objects.requireNonNull(text, "text");
            if (value.length()
                    > policy.maxOutputCodeUnits() - text.length()) {
                throw representationLimit(
                    "MAX_OUTPUT_CODE_UNITS_EXCEEDED");
            }
            work.consume("render.output-code-units", text.length());
            value.append(text);
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private static final class Work {
        private final long limit;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private long total;

        private Work(long limit) {
            this.limit = limit;
        }

        private void consume(String stage, long units) {
            if (units < 0 || total > limit - units) {
                throw representationLimit(
                    "RENDERER_WORK_BUDGET_EXCEEDED");
            }
            if (units == 0) {
                return;
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private PolynomialWorkLedger ledger() {
            return new PolynomialWorkLedger(stages);
        }
    }

    private static final class UnsupportedCandidate
            extends RuntimeException {
        private UnsupportedCandidate(String message) {
            super(message);
        }
    }

    private static final class RepresentationLimitReached
            extends RuntimeException {
        private RepresentationLimitReached(String message) {
            super(message);
        }
    }
}
