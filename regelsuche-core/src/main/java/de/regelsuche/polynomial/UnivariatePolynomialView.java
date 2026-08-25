package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.ArrayList;
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
        this.ring = requireUnivariateRing(ring);
        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> canonical = new ArrayList<>(
            Objects.requireNonNull(coefficients, "coefficients").size());
        for (C coefficient : coefficients) {
            canonical.add(domain.canonical(
                Objects.requireNonNull(coefficient, "coefficient")));
        }
        int size = canonical.size();
        while (size > 0 && domain.isZero(canonical.get(size - 1))) {
            size--;
        }
        this.coefficients = List.copyOf(canonical.subList(0, size));
    }

    public static <C> UnivariatePolynomialView<C> from(
        SparsePolynomial<C> polynomial
    ) {
        Objects.requireNonNull(polynomial, "polynomial");
        PolynomialRing<C> ring = requireUnivariateRing(polynomial.ring());
        if (polynomial.isZero()) {
            return zero(ring);
        }
        ArrayList<C> coefficients = zeros(
            ring.coefficientDomain(),
            polynomial.degree(0) + 1);
        for (Map.Entry<Monomial, C> term : polynomial.terms().entrySet()) {
            coefficients.set(
                term.getKey().exponent(0),
                term.getValue());
        }
        return new UnivariatePolynomialView<>(ring, coefficients);
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

    public SparsePolynomial<C> toSparsePolynomial() {
        TreeMap<Monomial, C> terms =
            new TreeMap<>(ring.monomialComparator());
        CoefficientDomain<C> domain = ring.coefficientDomain();
        for (int exponent = 0;
                exponent < coefficients.size();
                exponent++) {
            C coefficient = coefficients.get(exponent);
            if (!domain.isZero(coefficient)) {
                terms.put(Monomial.of(exponent), coefficient);
            }
        }
        return new SparsePolynomial<>(ring, terms);
    }

    public UnivariatePolynomialView<C> add(
        UnivariatePolynomialView<C> other
    ) {
        return add(other, null, "");
    }

    public UnivariatePolynomialView<C> subtract(
        UnivariatePolynomialView<C> other
    ) {
        return subtract(other, null, "");
    }

    public UnivariatePolynomialView<C> negate() {
        CoefficientDomain<C> domain = ring.coefficientDomain();
        return new UnivariatePolynomialView<>(
            ring,
            coefficients.stream().map(domain::negate).toList());
    }

    public UnivariatePolynomialView<C> scale(C scalar) {
        return scale(scalar, null, "");
    }

    public UnivariatePolynomialView<C> multiply(
        UnivariatePolynomialView<C> other
    ) {
        return multiply(other, null, "");
    }

    public UnivariatePolynomialView<C> derivative() {
        return derivative(null, "");
    }

    public UnivariatePolynomialView<C> monic(
        ExactField<C> field
    ) {
        return monic(field, null, "");
    }

    public DivisionResult<C> divideAndRemainder(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field
    ) {
        return divideAndRemainder(divisor, field, null, "");
    }

    public UnivariatePolynomialView<C> exactQuotient(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field
    ) {
        DivisionResult<C> result = divideAndRemainder(divisor, field);
        if (!result.remainder().isZero()) {
            throw new ArithmeticException(
                "polynomial division has nonzero remainder");
        }
        return result.quotient();
    }

    UnivariatePolynomialView<C> add(
        UnivariatePolynomialView<C> other,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(other);
        CoefficientDomain<C> domain = ring.coefficientDomain();
        int size = Math.max(coefficientCount(), other.coefficientCount());
        ArrayList<C> result = zeros(domain, size);
        for (int exponent = 0; exponent < size; exponent++) {
            consume(work, stage, 1);
            result.set(
                exponent,
                domain.add(
                    coefficient(exponent),
                    other.coefficient(exponent)));
        }
        return new UnivariatePolynomialView<>(ring, result);
    }

    UnivariatePolynomialView<C> subtract(
        UnivariatePolynomialView<C> other,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(other);
        CoefficientDomain<C> domain = ring.coefficientDomain();
        int size = Math.max(coefficientCount(), other.coefficientCount());
        ArrayList<C> result = zeros(domain, size);
        for (int exponent = 0; exponent < size; exponent++) {
            consume(work, stage, 1);
            result.set(
                exponent,
                domain.subtract(
                    coefficient(exponent),
                    other.coefficient(exponent)));
        }
        return new UnivariatePolynomialView<>(ring, result);
    }

    UnivariatePolynomialView<C> scale(
        C scalar,
        PolynomialWorkBudget work,
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
        return new UnivariatePolynomialView<>(ring, result);
    }

    UnivariatePolynomialView<C> multiply(
        UnivariatePolynomialView<C> other,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(other);
        if (isZero() || other.isZero()) {
            return zero(ring);
        }
        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> result = zeros(
            domain,
            degree() + other.degree() + 1);
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
        return new UnivariatePolynomialView<>(ring, result);
    }

    UnivariatePolynomialView<C> derivative(
        PolynomialWorkBudget work,
        String stage
    ) {
        if (degree() <= 0) {
            return zero(ring);
        }
        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> result = zeros(domain, degree());
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
        return new UnivariatePolynomialView<>(ring, result);
    }

    UnivariatePolynomialView<C> monic(
        ExactField<C> field,
        PolynomialWorkBudget work,
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

    DivisionResult<C> divideAndRemainder(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(divisor);
        requireField(field);
        if (divisor.isZero()) {
            throw new ArithmeticException(
                "polynomial division by zero");
        }
        if (isZero() || degree() < divisor.degree()) {
            return new DivisionResult<>(
                zero(ring),
                this);
        }

        CoefficientDomain<C> domain = ring.coefficientDomain();
        ArrayList<C> quotient = zeros(
            domain,
            degree() - divisor.degree() + 1);
        ArrayList<C> remainder = new ArrayList<>(coefficients);
        int remainderDegree = trimmedDegree(remainder, domain);

        while (remainderDegree >= divisor.degree()) {
            consume(work, stage + ".iterations", 1);
            int shift = remainderDegree - divisor.degree();
            consume(work, stage + ".coefficient-divisions", 1);
            C scale = field.divide(
                remainder.get(remainderDegree),
                divisor.leadingCoefficient());
            quotient.set(
                shift,
                domain.add(quotient.get(shift), scale));
            for (int exponent = 0;
                    exponent <= divisor.degree();
                    exponent++) {
                consume(work, stage + ".coefficient-updates", 2);
                int index = exponent + shift;
                remainder.set(
                    index,
                    domain.subtract(
                        remainder.get(index),
                        domain.multiply(
                            scale,
                            divisor.coefficient(exponent))));
            }
            remainderDegree = trimmedDegree(remainder, domain);
        }

        return new DivisionResult<>(
            new UnivariatePolynomialView<>(ring, quotient),
            new UnivariatePolynomialView<>(ring, remainder));
    }

    UnivariatePolynomialView<C> exactQuotient(
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field,
        PolynomialWorkBudget work,
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
        CoefficientDomain<C> domain,
        int size
    ) {
        ArrayList<C> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(domain.zero());
        }
        return result;
    }

    private static <C> int trimmedDegree(
        List<C> values,
        CoefficientDomain<C> domain
    ) {
        int degree = values.size() - 1;
        while (degree >= 0 && domain.isZero(values.get(degree))) {
            degree--;
        }
        return degree;
    }

    private static void consume(
        PolynomialWorkBudget work,
        String stage,
        long units
    ) {
        if (work != null) {
            work.consume(
                stage == null || stage.isBlank()
                    ? "univariate.arithmetic"
                    : stage,
                units);
        }
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
