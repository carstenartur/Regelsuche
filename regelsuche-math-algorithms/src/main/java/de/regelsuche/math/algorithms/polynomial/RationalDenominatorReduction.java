package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.CoefficientDomain;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PolynomialWorkSink;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Derives and verifies a common base for monic square and cube denominators. */
public final class RationalDenominatorReduction {
    private static final Coefficient ZERO = new Coefficient(BigInteger.ZERO, BigInteger.ZERO);
    private static final Coefficient ONE = new Coefficient(BigInteger.ONE, BigInteger.ZERO);
    private static final List<PolynomialVariable> VARIABLE = List.of(new PolynomialVariable("x"));
    private static final int MAX_INFERENCE_BITS = 16_384;
    private static final String WORK = "rational-denominator.";

    private RationalDenominatorReduction() {}

    /** Represents {@code real + imaginary * u}, with {@code u^2 = -1}. */
    public record Coefficient(BigInteger real, BigInteger imaginary) {
        public Coefficient {
            Objects.requireNonNull(real, "real");
            Objects.requireNonNull(imaginary, "imaginary");
        }
    }

    /** The ascending coefficients of the proposed denominator, including its leading one. */
    public record Plan(List<Coefficient> denominator) {
        public Plan {
            denominator = List.copyOf(denominator);
        }
    }

    /** A plan valid for an inferred modulus. A consumer must check equality with its runtime modulus. */
    public record GuardedPlan(BigInteger modulus, Plan plan) {
        public GuardedPlan {
            Objects.requireNonNull(modulus, "modulus");
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * Divides {@code yDenominator} by {@code xDenominator} and checks that the
     * quotient gives both input denominators when squared and cubed.
     * Inputs use ascending coefficients and a literal leading one. Lower
     * coefficients may be signed integer representatives modulo {@code modulus}.
     * A rejected charge from {@code work} propagates to the caller.
     */
    public static Optional<Plan> discover(List<Coefficient> xDenominator, List<Coefficient> yDenominator,
            BigInteger modulus, PolynomialWorkSink work) {
        Objects.requireNonNull(work, "work");
        int degree = commonDegree(xDenominator, yDenominator);
        if (degree < 1 || modulus.compareTo(BigInteger.ONE) <= 0) {
            return Optional.empty();
        }
        work.consume(WORK + "inputs", (long) xDenominator.size() + yDenominator.size());
        var ring = ring(new QuadraticResidueDomain(modulus));
        var x = UnivariatePolynomialView.of(ring, xDenominator);
        var y = UnivariatePolynomialView.of(ring, yDenominator);
        var division = y.divideAndRemainderMonic(x, work, WORK + "quotient");
        var denominator = division.quotient();
        if (!division.remainder().isZero() || !identitiesHold(x, y, denominator, work)) {
            return Optional.empty();
        }
        return Optional.of(new Plan(denominator.coefficients()));
    }

    /** Replays the square and cube identities directly from the supplied plan and input coefficients. */
    public static boolean verify(List<Coefficient> xDenominator, List<Coefficient> yDenominator,
            BigInteger modulus, Plan plan, PolynomialWorkSink work) {
        Objects.requireNonNull(work, "work");
        int degree = commonDegree(xDenominator, yDenominator);
        if (degree < 1 || modulus.compareTo(BigInteger.ONE) <= 0
                || plan.denominator().size() != degree + 1 || !isMonic(plan.denominator())) {
            return false;
        }
        work.consume(WORK + "inputs", (long) xDenominator.size() + yDenominator.size() + plan.denominator().size());
        var ring = ring(new QuadraticResidueDomain(modulus));
        return identitiesHold(UnivariatePolynomialView.of(ring, xDenominator),
            UnivariatePolynomialView.of(ring, yDenominator),
            UnivariatePolynomialView.of(ring, plan.denominator()), work);
    }

    /**
     * Proposes a modulus for real coefficients by taking the GCD of the integer
     * coefficient residuals. The input identities may also hold for divisors of
     * the proposal. The returned plan has been checked for the proposed modulus.
     */
    public static Optional<GuardedPlan> inferModulus(List<Coefficient> xDenominator, List<Coefficient> yDenominator,
            PolynomialWorkSink work) {
        Objects.requireNonNull(work, "work");
        int degree = commonDegree(xDenominator, yDenominator);
        if (degree < 1 || !fitsIntegerInference(xDenominator, yDenominator, degree, work)) {
            return Optional.empty();
        }
        var ring = ring(BigIntegerDomain.INSTANCE);
        var x = UnivariatePolynomialView.of(ring, xDenominator.stream().map(Coefficient::real).toList());
        var y = UnivariatePolynomialView.of(ring, yDenominator.stream().map(Coefficient::real).toList());
        // Monic division commutes with reduction modulo every candidate modulus.
        var quotient = y.divideAndRemainderMonic(x, work, WORK + "integer-quotient").quotient();
        var square = quotient.multiply(quotient, work, WORK + "integer-square");
        var cube = square.multiply(quotient, work, WORK + "integer-cube");
        var xResidual = x.subtract(square, work, WORK + "square-residual");
        var yResidual = y.subtract(cube, work, WORK + "cube-residual");
        BigInteger modulus = gcdCoefficients(yResidual, gcdCoefficients(xResidual, BigInteger.ZERO, work), work);
        if (modulus.compareTo(BigInteger.ONE) <= 0) {
            return Optional.empty();
        }
        return discover(xDenominator, yDenominator, modulus, work).map(plan -> new GuardedPlan(modulus, plan));
    }

    private static boolean fitsIntegerInference(List<Coefficient> x, List<Coefficient> y, int degree,
            PolynomialWorkSink work) {
        if (inferenceBitBound(degree, 1) > MAX_INFERENCE_BITS) {
            return false;
        }
        work.consume(WORK + "integer-inputs", (long) x.size() + y.size());
        long xBits = realBitLength(x);
        long yBits = realBitLength(y);
        return xBits >= 0 && yBits >= 0
            && inferenceBitBound(degree, Math.max(xBits, yBits)) <= MAX_INFERENCE_BITS;
    }

    private static long realBitLength(List<Coefficient> coefficients) {
        long bits = 0;
        for (Coefficient coefficient : coefficients) {
            if (coefficient.imaginary().signum() != 0) {
                return -1;
            }
            BigInteger real = coefficient.real();
            // For negative BigIntegers, bitLength omits the sign bit.
            bits = Math.max(bits, (long) real.bitLength() + (real.signum() < 0 ? 1 : 0));
        }
        return bits;
    }

    private static long inferenceBitBound(int degree, long inputBits) {
        // Each quotient step adds at most inputBits + 1 bits to a coefficient.
        // The cube sums at most (degree + 1)^2 such triple products per coefficient.
        long quotientBits = inputBits + (degree + 1L) * (inputBits + 1);
        int countBits = Integer.SIZE - Integer.numberOfLeadingZeros(degree + 1);
        return 3 * quotientBits + 2L * countBits + 1;
    }

    private static BigInteger gcdCoefficients(UnivariatePolynomialView<BigInteger> residual,
            BigInteger gcd, PolynomialWorkSink work) {
        for (BigInteger coefficient : residual.coefficients()) {
            work.consume(WORK + "residual-gcd", 1);
            gcd = gcd.gcd(coefficient);
            if (BigInteger.ONE.equals(gcd)) {
                break;
            }
        }
        return gcd;
    }

    private static boolean identitiesHold(UnivariatePolynomialView<Coefficient> x,
            UnivariatePolynomialView<Coefficient> y, UnivariatePolynomialView<Coefficient> denominator,
            PolynomialWorkSink work) {
        var square = denominator.multiply(denominator, work, WORK + "square");
        work.consume(WORK + "compare-square", x.coefficientCount());
        if (!x.coefficients().equals(square.coefficients())) {
            return false;
        }
        var cube = square.multiply(denominator, work, WORK + "cube");
        work.consume(WORK + "compare-cube", y.coefficientCount());
        return y.coefficients().equals(cube.coefficients());
    }

    private static int commonDegree(List<Coefficient> x, List<Coefficient> y) {
        int degree = y.size() - x.size();
        return degree > 0 && x.size() == 2L * degree + 1 && y.size() == 3L * degree + 1
                && isMonic(x) && isMonic(y) ? degree : -1;
    }

    private static boolean isMonic(List<Coefficient> coefficients) {
        return ONE.equals(coefficients.getLast());
    }

    private static <C> PolynomialRing<C> ring(CoefficientDomain<C> domain) {
        return new PolynomialRing<>(domain, VARIABLE, PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }

    private record QuadraticResidueDomain(BigInteger modulus) implements CoefficientDomain<Coefficient> {
        @Override public String id() { return "quadratic-residue(-1," + modulus + ')'; }
        @Override public BigInteger characteristic() { return modulus; }
        @Override public Coefficient zero() { return ZERO; }
        @Override public Coefficient one() { return ONE; }
        @Override public Coefficient fromInteger(BigInteger value) { return new Coefficient(value.mod(modulus), BigInteger.ZERO); }

        @Override
        public Coefficient canonical(Coefficient value) {
            return reduce(value.real(), value.imaginary());
        }

        @Override
        public Coefficient add(Coefficient left, Coefficient right) {
            return reduce(left.real().add(right.real()), left.imaginary().add(right.imaginary()));
        }

        @Override
        public Coefficient negate(Coefficient value) {
            return reduce(value.real().negate(), value.imaginary().negate());
        }

        @Override
        public Coefficient multiply(Coefficient left, Coefficient right) {
            BigInteger realProduct = left.real().multiply(right.real());
            if (left.imaginary().signum() == 0 && right.imaginary().signum() == 0) {
                return fromInteger(realProduct);
            }
            return reduce(realProduct.subtract(left.imaginary().multiply(right.imaginary())),
                left.real().multiply(right.imaginary()).add(left.imaginary().multiply(right.real())));
        }

        @Override
        public boolean isZero(Coefficient value) {
            return ZERO.equals(value);
        }

        @Override
        public String canonicalText(Coefficient value) {
            Coefficient canonical = canonical(value);
            return canonical.real() + "," + canonical.imaginary();
        }

        @Override
        public int bitLength(Coefficient value) {
            Coefficient canonical = canonical(value);
            return Math.max(canonical.real().bitLength(), canonical.imaginary().bitLength());
        }

        private Coefficient reduce(BigInteger real, BigInteger imaginary) {
            return new Coefficient(real.mod(modulus), imaginary.mod(modulus));
        }
    }
}
