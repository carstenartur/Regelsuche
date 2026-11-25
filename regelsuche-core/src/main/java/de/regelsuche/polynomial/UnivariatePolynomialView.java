package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Efficient dense ascending coefficient view of one canonical univariate
 * polynomial.
 *
 * <p>The sparse polynomial remains the mathematical identity. This view is a
 * lossless algorithm representation and never owns source syntax.</p>
 */
public final class UnivariatePolynomialView<C> {
    private final PolynomialRing<C> ring;
    private final List<C> coefficients;

    private UnivariatePolynomialView(
        PolynomialRing<C> ring,
        List<C> coefficients
    ) {
        this(ring, coefficients, PolynomialWorkSink.none(), "univariate.construct");
    }

    private UnivariatePolynomialView(
        PolynomialRing<C> ring, List<C> coefficients, PolynomialWorkSink work, String stage
    ) {
        this.ring = requireUnivariateRing(ring);
        Objects.requireNonNull(work, "work");
        CoefficientDomain<C> domain = ring.coefficientDomain();
        Objects.requireNonNull(coefficients, "coefficients");
        checkpoint(work, stage, 0);
        ArrayList<C> canonical = new ArrayList<>(coefficients.size());
        int index = 0;
        for (C coefficient : coefficients) {
            checkpoint(work, stage, index++);
            canonical.add(domain.canonical(
                Objects.requireNonNull(coefficient, "coefficient")));
        }
        int size = trimmedDegree(canonical, domain, canonical.size() - 1, work, stage) + 1;
        if (work == PolynomialWorkSink.NoOp.INSTANCE) {
            this.coefficients = List.copyOf(canonical.subList(0, size));
        } else if (size == 0) {
            this.coefficients = List.of();
        } else {
            // The local list has no escaped mutable alias. Avoid List.copyOf's
            // unpolled degree-sized copy/null-check pass on checked paths.
            this.coefficients = Collections.unmodifiableList(size == canonical.size()
                ? canonical : copy(canonical, size, work, stage));
        }
        checkpoint(work, stage, 0);
    }

    public static <C> UnivariatePolynomialView<C> from(
        SparsePolynomial<C> polynomial
    ) {
        return from(polynomial, PolynomialWorkSink.none(), "univariate.from");
    }

    /** Convert using the sink's optional checkpoints, without charging prepaid work again. */
    public static <C> UnivariatePolynomialView<C> from(
        SparsePolynomial<C> polynomial, PolynomialWorkSink work, String stage
    ) {
        Objects.requireNonNull(polynomial, "polynomial");
        Objects.requireNonNull(work, "work");
        PolynomialRing<C> ring = requireUnivariateRing(polynomial.ring());
        checkpoint(work, stage, 0);
        if (polynomial.isZero()) return zero(ring);
        ArrayList<C> coefficients = zeros(
            ring.coefficientDomain(), polynomial.degree(0) + 1, work, stage);
        int index = 0;
        for (Map.Entry<Monomial, C> term : polynomial.terms().entrySet()) {
            checkpoint(work, stage, index++);
            coefficients.set(term.getKey().exponent(0), term.getValue());
        }
        return new UnivariatePolynomialView<>(ring, coefficients, work, stage);
    }

    /** Canonicalize with optional checkpoints; the result still owns an immutable coefficient list. */
    public static <C> UnivariatePolynomialView<C> of(
        PolynomialRing<C> ring, List<C> ascendingCoefficients, PolynomialWorkSink work, String stage
    ) {
        return new UnivariatePolynomialView<>(ring, ascendingCoefficients, work, stage);
    }

    public static <C> UnivariatePolynomialView<C> of(
        PolynomialRing<C> ring,
        List<C> ascendingCoefficients
    ) {
        return new UnivariatePolynomialView<>(ring, ascendingCoefficients);
    }

    public static <C> UnivariatePolynomialView<C> zero(
        PolynomialRing<C> ring
    ) {
        return new UnivariatePolynomialView<>(ring, List.of());
    }

    public static <C> UnivariatePolynomialView<C> one(
        PolynomialRing<C> ring
    ) {
        return new UnivariatePolynomialView<>(
            ring,
            List.of(ring.coefficientDomain().one()));
    }

    public PolynomialRing<C> ring() {
        return ring;
    }

    public List<C> coefficients() {
        return coefficients;
    }

    public int degree() {
        return coefficients.size() - 1;
    }

    public int coefficientCount() {
        return coefficients.size();
    }

    public boolean isZero() {
        return coefficients.isEmpty();
    }

    public boolean isOne() {
        return degree() == 0
            && ring.coefficientDomain().isOne(coefficients.getFirst());
    }

    public boolean isConstant() {
        return degree() <= 0;
    }

    public C coefficient(int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException(
                "univariate exponent must not be negative");
        }
        return exponent < coefficients.size()
            ? coefficients.get(exponent)
            : ring.coefficientDomain().zero();
    }

    public C leadingCoefficient() {
        if (isZero()) {
            throw new IllegalStateException(
                "zero polynomial has no leading coefficient");
        }
        return coefficients.getLast();
    }

    /** Poll both the dense scan and the sparse polynomial's canonicalization. */
    public SparsePolynomial<C> toSparsePolynomial(PolynomialWorkSink work, String stage) {
        Objects.requireNonNull(work, "work");
        checkpoint(work, stage, 0);
        TreeMap<Monomial, C> terms = new TreeMap<>(ring.monomialComparator());
        CoefficientDomain<C> domain = ring.coefficientDomain();
        for (int exponent = 0; exponent < coefficients.size(); exponent++) {
            checkpoint(work, stage, exponent);
            C coefficient = coefficients.get(exponent);
            if (!domain.isZero(coefficient)) terms.put(Monomial.of(exponent), coefficient);
        }
        return new SparsePolynomial<>(ring, terms, work, stage);
    }

    public SparsePolynomial<C> toSparsePolynomial() {
        return toSparsePolynomial(PolynomialWorkSink.none(), "univariate.to-sparse");
    }

    public UnivariatePolynomialView<C> add(
        UnivariatePolynomialView<C> other
    ) {
        return add(
            other,
            PolynomialWorkSink.none(),
            "univariate.add");
    }

    public UnivariatePolynomialView<C> subtract(
        UnivariatePolynomialView<C> other
    ) {
        return subtract(
            other,
            PolynomialWorkSink.none(),
            "univariate.subtract");
    }

    public UnivariatePolynomialView<C> negate() {
        CoefficientDomain<C> domain = ring.coefficientDomain();
        return new UnivariatePolynomialView<>(
            ring,
            coefficients.stream().map(domain::negate).toList());
    }

    public UnivariatePolynomialView<C> scale(C scalar) {
        return scale(
            scalar,
            PolynomialWorkSink.none(),
            "univariate.scale");
    }

    public UnivariatePolynomialView<C> multiply(
        UnivariatePolynomialView<C> other
    ) {
        return multiply(
            other,
            PolynomialWorkSink.none(),
            "univariate.multiply");
    }

    public UnivariatePolynomialView<C> derivative() {
        return derivative(
            PolynomialWorkSink.none(),
            "univariate.derivative");
    }

    public UnivariatePolynomialView<C> monic(
        ExactField<C> field
    ) {
        return monic(
            field,
            PolynomialWorkSink.none(),
            "univariate.monic");
    }

    public DivisionResult<C> divideAndRemainder(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field
    ) {
        return divideAndRemainder(
            divisor,
            field,
            PolynomialWorkSink.none(),
            "univariate.division");
    }

    public UnivariatePolynomialView<C> exactQuotient(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field
    ) {
        return exactQuotient(
            divisor,
            field,
            PolynomialWorkSink.none(),
            "univariate.exact-quotient");
    }

    public UnivariatePolynomialView<C> add(
        UnivariatePolynomialView<C> other,
        PolynomialWorkSink work,
        String stage
    ) {
        requireSameRing(other);
        CoefficientDomain<C> domain = ring.coefficientDomain();
        int size = Math.max(coefficientCount(), other.coefficientCount());
        ArrayList<C> result = zeros(domain, size, work, stage);
        for (int exponent = 0; exponent < size; exponent++) {
            consume(work, stage, 1);
            result.set(
                exponent,
                domain.add(
                    coefficient(exponent),
                    other.coefficient(exponent)));
        }
        return new UnivariatePolynomialView<>(ring, result, work, stage);
    }

    public UnivariatePolynomialView<C> subtract(
        UnivariatePolynomialView<C> other,
        PolynomialWorkSink work,
        String stage
    ) {
        requireSameRing(other);
        CoefficientDomain<C> domain = ring.coefficientDomain();
        int size = Math.max(coefficientCount(), other.coefficientCount());
        ArrayList<C> result = zeros(domain, size, work, stage);
        for (int exponent = 0; exponent < size; exponent++) {
            consume(work, stage, 1);
            result.set(
                exponent,
                domain.subtract(
                    coefficient(exponent),
                    other.coefficient(exponent)));
        }
        return new UnivariatePolynomialView<>(ring, result, work, stage);
    }

    public UnivariatePolynomialView<C> scale(
        C scalar,
        PolynomialWorkSink work,
        String stage
    ) {
        CoefficientDomain<C> domain = ring.coefficientDomain();
        C canonicalScalar = domain.canonical(
            Objects.requireNonNull(scalar, "scalar"));
        if (domain.isZero(canonicalScalar) || isZero()) {
            return zero(ring);
        }
        ArrayList<C> result = new ArrayList<>(coefficientCount());
        for (C coefficient : coefficients) {
            consume(work, stage, 1);
            result.add(domain.multiply(coefficient, canonicalScalar));
        }
        return new UnivariatePolynomialView<>(ring, result, work, stage);
    }

    public UnivariatePolynomialView<C> multiply(
        UnivariatePolynomialView<C> other,
        PolynomialWorkSink work,
        String stage
    ) {
        requireSameRing(other);
        if (isZero() || other.isZero()) {
            return zero(ring);
        }
        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> result = zeros(
            domain,
            degree() + other.degree() + 1, work, stage);
        for (int left = 0; left < coefficientCount(); left++) {
            for (int right = 0; right < other.coefficientCount(); right++) {
                consume(work, stage, 2);
                int exponent = left + right;
                result.set(
                    exponent,
                    domain.add(
                        result.get(exponent),
                        domain.multiply(
                            coefficient(left),
                            other.coefficient(right))));
            }
        }
        return new UnivariatePolynomialView<>(ring, result, work, stage);
    }

    public UnivariatePolynomialView<C> derivative(
        PolynomialWorkSink work,
        String stage
    ) {
        if (degree() <= 0) {
            return zero(ring);
        }
        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> result = zeros(domain, degree(), work, stage);
        for (int exponent = 1;
                exponent < coefficientCount();
                exponent++) {
            consume(work, stage, 1);
            result.set(
                exponent - 1,
                domain.multiply(
                    coefficient(exponent),
                    domain.fromInteger(
                        BigInteger.valueOf(exponent))));
        }
        return new UnivariatePolynomialView<>(ring, result, work, stage);
    }

    public UnivariatePolynomialView<C> monic(
        ExactField<C> field,
        PolynomialWorkSink work,
        String stage
    ) {
        requireField(field);
        if (isZero()) {
            return this;
        }
        consume(work, stage, 1);
        C inverse = field.divide(
            field.one(),
            leadingCoefficient());
        return scale(inverse, work, stage);
    }

    public DivisionResult<C> divideAndRemainder(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field,
        PolynomialWorkSink work,
        String stage
    ) {
        requireSameRing(divisor);
        requireField(field);
        if (divisor.isZero()) {
            throw new ArithmeticException(
                "polynomial division by zero");
        }
        return divideAndRemainder(divisor, work, stage, field);
    }

    /** Divides by a monic polynomial over the coefficient ring. */
    public DivisionResult<C> divideAndRemainderMonic(
        UnivariatePolynomialView<C> divisor,
        PolynomialWorkSink work,
        String stage
    ) {
        requireSameRing(divisor);
        if (divisor.isZero()
                || !ring.coefficientDomain().isOne(divisor.leadingCoefficient())) {
            throw new IllegalArgumentException("polynomial divisor must be monic");
        }
        return divideAndRemainder(divisor, work, stage, null);
    }

    private DivisionResult<C> divideAndRemainder(
        UnivariatePolynomialView<C> divisor,
        PolynomialWorkSink work,
        String stage,
        ExactField<C> field
    ) {
        if (isZero() || degree() < divisor.degree()) {
            return new DivisionResult<>(
                zero(ring),
                this);
        }

        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> quotient = zeros(
            domain,
            degree() - divisor.degree() + 1, work, stage);
        ArrayList<C> remainder = copy(coefficients, coefficientCount(), work, stage);
        int remainderDegree = degree();
        String iterationStage = stage + ".iterations";
        String divisionStage = stage + ".coefficient-divisions";
        String updateStage = stage + ".coefficient-updates";
        // Only the concrete immutable prime field has this specialization.
        // Generic field implementations keep their own divide operation.
        boolean reuseInverse = field instanceof PrimeField
            && domain instanceof PrimeField;
        C inverse = null;

        while (remainderDegree >= divisor.degree()) {
            consume(work, iterationStage, 1);
            int shift = remainderDegree - divisor.degree();
            C scale = remainder.get(remainderDegree);
            if (field != null) {
                consume(work, divisionStage, 1);
                if (reuseInverse) {
                    // Lazy initialization stays after both existing budget charges.
                    if (inverse == null) {
                        inverse = field.divide(field.one(), divisor.leadingCoefficient());
                    }
                    scale = field.multiply(scale, inverse);
                } else {
                    scale = field.divide(scale, divisor.leadingCoefficient());
                }
            }
            quotient.set(
                shift,
                domain.add(quotient.get(shift), scale));
            for (int exponent = 0;
                    exponent <= divisor.degree();
                    exponent++) {
                consume(work, updateStage, 2);
                int index = exponent + shift;
                remainder.set(
                    index,
                    domain.subtract(
                        remainder.get(index),
                        domain.multiply(
                            scale,
                            divisor.coefficient(exponent))));
            }
            remainderDegree = trimmedDegree(remainder, domain, remainderDegree, work, stage);
        }

        return new DivisionResult<>(
            new UnivariatePolynomialView<>(ring, quotient, work, stage),
            new UnivariatePolynomialView<>(ring, remainder, work, stage));
    }

    public UnivariatePolynomialView<C> exactQuotient(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field,
        PolynomialWorkSink work,
        String stage
    ) {
        DivisionResult<C> result =
            divideAndRemainder(divisor, field, work, stage);
        if (!result.remainder().isZero()) {
            throw new ArithmeticException(
                "polynomial division has nonzero remainder");
        }
        return result.quotient();
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder(
            ring.canonicalMaterial());
        CoefficientDomain<C> domain = ring.coefficientDomain();
        for (int exponent = 0;
                exponent < coefficients.size();
                exponent++) {
            PolynomialEvidence.append(
                result,
                Integer.toString(exponent));
            PolynomialEvidence.append(
                result,
                domain.canonicalText(coefficients.get(exponent)));
        }
        return result.toString();
    }

    private void requireSameRing(
        UnivariatePolynomialView<C> other
    ) {
        Objects.requireNonNull(other, "other");
        if (!ring.equals(other.ring)) {
            throw new IllegalArgumentException(
                "univariate polynomial ring mismatch");
        }
    }

    private void requireField(ExactField<C> field) {
        Objects.requireNonNull(field, "field");
        if (!ring.coefficientDomain().id().equals(field.id())) {
            throw new IllegalArgumentException(
                "field does not match polynomial coefficient domain");
        }
    }

    private static <C> PolynomialRing<C> requireUnivariateRing(
        PolynomialRing<C> ring
    ) {
        Objects.requireNonNull(ring, "ring");
        if (ring.variableCount() != 1) {
            throw new IllegalArgumentException(
                "univariate view requires exactly one ring variable");
        }
        return ring;
    }

    private static <C> ArrayList<C> zeros(
        CoefficientDomain<C> domain, int size, PolynomialWorkSink work, String stage
    ) {
        checkpoint(work, stage, 0);
        ArrayList<C> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            checkpoint(work, stage, index);
            result.add(domain.zero());
        }
        checkpoint(work, stage, 0);
        return result;
    }

    private static <C> ArrayList<C> copy(
        List<C> values, int size, PolynomialWorkSink work, String stage
    ) {
        checkpoint(work, stage, 0);
        ArrayList<C> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            checkpoint(work, stage, index);
            result.add(values.get(index));
        }
        checkpoint(work, stage, 0);
        return result;
    }

    private static <C> int trimmedDegree(
        List<C> values, CoefficientDomain<C> domain, int degree, PolynomialWorkSink work, String stage
    ) {
        // Entries above the previous remainder degree are already known zero.
        int scanned = 0;
        while (degree >= 0) {
            checkpoint(work, stage, scanned++);
            if (!domain.isZero(values.get(degree))) break;
            degree--;
        }
        checkpoint(work, stage, 0);
        return degree;
    }

    private static void checkpoint(PolynomialWorkSink work, String stage, int index) {
        if ((index & 1023) == 0) work.checkpoint(stage == null || stage.isBlank()
            ? "univariate.construction" : stage);
    }

    private static void consume(
        PolynomialWorkSink work,
        String stage,
        long units
    ) {
        Objects.requireNonNull(work, "work").consume(
            stage == null || stage.isBlank()
                ? "univariate.arithmetic"
                : stage,
            units);
    }

    public record DivisionResult<C>(
        UnivariatePolynomialView<C> quotient,
        UnivariatePolynomialView<C> remainder
    ) {
        public DivisionResult {
            Objects.requireNonNull(quotient, "quotient");
            Objects.requireNonNull(remainder, "remainder");
            if (!quotient.ring().equals(remainder.ring())) {
                throw new IllegalArgumentException(
                    "division result ring mismatch");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof UnivariatePolynomialView<?> view
                && ring.equals(view.ring)
                && coefficients.equals(view.coefficients);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ring, coefficients);
    }

    @Override
    public String toString() {
        return coefficients.toString();
    }
}
