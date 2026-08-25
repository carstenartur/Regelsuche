package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Package-local exact arithmetic used by finite-field algorithms. */
final class FiniteFieldPolynomialArithmetic {
    static final String IRREDUCIBILITY_METHOD_ID =
        "regelsuche.prime-field-irreducibility-rabin/v1";

    private FiniteFieldPolynomialArithmetic() {
    }

    static UnivariatePolynomialView<BigInteger> x(
        PolynomialRing<BigInteger> ring
    ) {
        PrimeField field = requirePrimeField(ring);
        return UnivariatePolynomialView.of(
            ring,
            List.of(field.zero(), field.one()));
    }

    static UnivariatePolynomialView<BigInteger> constant(
        PolynomialRing<BigInteger> ring,
        BigInteger value
    ) {
        PrimeField field = requirePrimeField(ring);
        return UnivariatePolynomialView.of(
            ring,
            List.of(field.canonical(value)));
    }

    static UnivariatePolynomialView<BigInteger> subtractConstant(
        UnivariatePolynomialView<BigInteger> value,
        BigInteger constant,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireField(value, field);
        work.consume(stage, 1);
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(value.coefficients());
        BigInteger adjusted = field.subtract(
            value.coefficient(0),
            constant);
        if (coefficients.isEmpty()) {
            coefficients.add(adjusted);
        } else {
            coefficients.set(0, adjusted);
        }
        return UnivariatePolynomialView.of(
            value.ring(),
            coefficients);
    }

    static UnivariatePolynomialView<BigInteger> remainder(
        UnivariatePolynomialView<BigInteger> value,
        UnivariatePolynomialView<BigInteger> modulus,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(value, modulus, field);
        if (modulus.isZero() || modulus.isConstant()) {
            throw new IllegalArgumentException(
                "polynomial modulus must be nonconstant");
        }
        work.consume(stage + ".degree-comparisons", 1);
        if (value.isZero() || value.degree() < modulus.degree()) {
            return value;
        }
        return value.divideAndRemainder(
            modulus,
            field,
            work,
            stage + ".division")
            .remainder();
    }

    static UnivariatePolynomialView<BigInteger> multiplyMod(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        UnivariatePolynomialView<BigInteger> modulus,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(left, right, field);
        requireSameRing(left, modulus, field);
        UnivariatePolynomialView<BigInteger> product = left.multiply(
            right,
            work,
            stage + ".multiply");
        return remainder(
            product,
            modulus,
            field,
            work,
            stage + ".reduce");
    }

    static UnivariatePolynomialView<BigInteger> powMod(
        UnivariatePolynomialView<BigInteger> base,
        BigInteger exponent,
        UnivariatePolynomialView<BigInteger> modulus,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameRing(base, modulus, field);
        if (exponent.signum() < 0) {
            throw new IllegalArgumentException(
                "modular polynomial exponent must not be negative");
        }
        UnivariatePolynomialView<BigInteger> result =
            UnivariatePolynomialView.one(base.ring());
        UnivariatePolynomialView<BigInteger> factor = remainder(
            base,
            modulus,
            field,
            work,
            stage + ".base");
        BigInteger remaining = exponent;
        while (remaining.signum() > 0) {
            work.consume(stage + ".exponent-bits", 1);
            if (remaining.testBit(0)) {
                result = multiplyMod(
                    result,
                    factor,
                    modulus,
                    field,
                    work,
                    stage + ".accumulate");
            }
            remaining = remaining.shiftRight(1);
            if (remaining.signum() > 0) {
                factor = multiplyMod(
                    factor,
                    factor,
                    modulus,
                    field,
                    work,
                    stage + ".square");
            }
        }
        return result;
    }

    static IrreducibilityEvidence verifyIrreducible(
        UnivariatePolynomialView<BigInteger> factor,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireField(factor, field);
        if (factor.isZero() || factor.isConstant()) {
            throw new IllegalArgumentException(
                "irreducibility requires a nonconstant polynomial");
        }
        if (!field.isOne(factor.leadingCoefficient())) {
            throw new IllegalArgumentException(
                "irreducibility verification requires a monic polynomial");
        }

        int degree = factor.degree();
        List<Integer> primeDivisors = primeDivisors(degree);
        StringBuilder material = new StringBuilder(
            IRREDUCIBILITY_METHOD_ID);
        AlgorithmEvidence.append(material, field.id());
        AlgorithmEvidence.append(
            material,
            factor.canonicalMaterial());
        AlgorithmEvidence.append(material, Integer.toString(degree));
        primeDivisors.forEach(divisor ->
            AlgorithmEvidence.append(
                material,
                Integer.toString(divisor)));

        if (degree == 1) {
            work.consume(stage + ".linear", 1);
            AlgorithmEvidence.append(material, "LINEAR");
            return new IrreducibilityEvidence(
                true,
                AlgorithmEvidence.sha256(material.toString()));
        }

        Set<Integer> gcdCheckpoints = new LinkedHashSet<>();
        primeDivisors.forEach(divisor ->
            gcdCheckpoints.add(degree / divisor));
        UnivariatePolynomialView<BigInteger> x = x(factor.ring());
        UnivariatePolynomialView<BigInteger> frobenius = x;
        boolean irreducible = true;

        for (int exponent = 1; exponent <= degree; exponent++) {
            frobenius = powMod(
                frobenius,
                field.characteristic(),
                factor,
                field,
                work,
                stage + ".frobenius-" + exponent);
            if (gcdCheckpoints.contains(exponent)) {
                UnivariatePolynomialView<BigInteger> difference =
                    frobenius.subtract(
                        x,
                        work,
                        stage + ".checkpoint-" + exponent
                            + ".subtract");
                UnivariatePolynomialView<BigInteger> gcd =
                    UnivariatePolynomialAlgorithms.gcd(
                        factor,
                        difference,
                        field,
                        work,
                        stage + ".checkpoint-" + exponent
                            + ".gcd");
                work.consume(
                    stage + ".checkpoint-comparisons",
                    1);
                AlgorithmEvidence.append(
                    material,
                    exponent + ":" + gcd.canonicalMaterial());
                if (!gcd.isOne()) {
                    irreducible = false;
                }
            }
        }

        work.consume(stage + ".final-comparison", 1);
        boolean finalCongruence = frobenius.equals(x);
        AlgorithmEvidence.append(
            material,
            "FINAL=" + finalCongruence);
        irreducible &= finalCongruence;
        AlgorithmEvidence.append(
            material,
            "IRREDUCIBLE=" + irreducible);
        return new IrreducibilityEvidence(
            irreducible,
            AlgorithmEvidence.sha256(material.toString()));
    }

    private static List<Integer> primeDivisors(int value) {
        ArrayList<Integer> result = new ArrayList<>();
        int remaining = value;
        for (int divisor = 2;
                (long) divisor * divisor <= remaining;
                divisor++) {
            if (remaining % divisor == 0) {
                result.add(divisor);
                while (remaining % divisor == 0) {
                    remaining /= divisor;
                }
            }
        }
        if (remaining > 1) {
            result.add(remaining);
        }
        return List.copyOf(result);
    }

    private static PrimeField requirePrimeField(
        PolynomialRing<BigInteger> ring
    ) {
        if (!(ring.coefficientDomain() instanceof PrimeField field)) {
            throw new IllegalArgumentException(
                "polynomial ring is not over a declared prime field");
        }
        return field;
    }

    private static void requireField(
        UnivariatePolynomialView<BigInteger> value,
        PrimeField field
    ) {
        if (!value.ring().coefficientDomain().id().equals(field.id())) {
            throw new IllegalArgumentException(
                "prime field does not match polynomial ring");
        }
    }

    private static void requireSameRing(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        PrimeField field
    ) {
        requireField(left, field);
        requireField(right, field);
        if (!left.ring().equals(right.ring())) {
            throw new IllegalArgumentException(
                "finite-field polynomial ring mismatch");
        }
    }

    record IrreducibilityEvidence(
        boolean irreducible,
        String certificateHash
    ) {
        IrreducibilityEvidence {
            if (certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "irreducibility evidence is invalid");
            }
        }
    }
}
