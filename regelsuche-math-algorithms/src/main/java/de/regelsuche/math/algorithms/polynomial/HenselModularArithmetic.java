package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Modular source, CRT and correction arithmetic for Hensel lifting. */
final class HenselModularArithmetic {
    private HenselModularArithmetic() {
    }

    static void verifySourceCorrespondence(
        UnivariatePolynomialView<BigInteger> source,
        SparsePolynomial<BigInteger> modularSource,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<BigInteger> reduced = reduce(
            source,
            modularSource.ring(),
            field,
            work,
            "hensel.verify.source-reduction");
        work.consume("hensel.verify.source-reduction.comparisons", 1);
        if (!reduced.toSparsePolynomial().equals(modularSource)) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_SELECTION_SOURCE_MISMATCH");
        }
    }

    static List<UnivariatePolynomialView<BigInteger>>
            anchoredModularFactors(
        SuitablePrimeSelectionResult selection,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        FiniteFieldFactorizationResult factorization =
            selection.modularFactorization();
        ArrayList<UnivariatePolynomialView<BigInteger>> result =
            new ArrayList<>(factorization.factors().size());
        for (int index = 0;
                index < factorization.factors().size();
                index++) {
            PolynomialFactor<BigInteger> factor =
                factorization.factors().get(index);
            UnivariatePolynomialView<BigInteger> value =
                UnivariatePolynomialView.from(factor.polynomial());
            result.add(index == 0
                ? value.scale(
                    factorization.unit(),
                    work,
                    "hensel.modular-anchor.scale")
                : value);
        }
        List<UnivariatePolynomialView<BigInteger>> retained =
            List.copyOf(result);
        UnivariatePolynomialView<BigInteger> product =
            multiplyFieldFactors(
                retained,
                work,
                "hensel.modular-anchor.product");
        work.consume("hensel.modular-anchor.comparisons", 1);
        if (!product.toSparsePolynomial().equals(
                selection.modularSource())) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_MODULAR_FACTORIZATION_SOURCE_MISMATCH");
        }
        retained.forEach(value -> requireField(value, field));
        return retained;
    }

    static List<CrtEntry> precomputeCrt(
        List<UnivariatePolynomialView<BigInteger>> factors,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        ArrayList<CrtEntry> result = new ArrayList<>(factors.size());
        for (int index = 0; index < factors.size(); index++) {
            ArrayList<UnivariatePolynomialView<BigInteger>> others =
                new ArrayList<>(factors.size() - 1);
            for (int other = 0; other < factors.size(); other++) {
                if (other != index) {
                    others.add(factors.get(other));
                }
            }
            UnivariatePolynomialView<BigInteger> cofactor =
                others.isEmpty()
                    ? UnivariatePolynomialView.one(
                        factors.get(index).ring())
                    : multiplyFieldFactors(
                        others,
                        work,
                        "hensel.crt.factor-" + index + ".cofactor");
            UnivariatePolynomialView<BigInteger> inverse = inverseModulo(
                cofactor,
                factors.get(index),
                field,
                work,
                "hensel.crt.factor-" + index + ".inverse");
            result.add(new CrtEntry(
                factors.get(index),
                cofactor,
                inverse));
        }
        return List.copyOf(result);
    }

    private static UnivariatePolynomialView<BigInteger> inverseModulo(
        UnivariatePolynomialView<BigInteger> value,
        UnivariatePolynomialView<BigInteger> modulus,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameFieldRing(value, modulus, field);
        UnivariatePolynomialView<BigInteger> r0 = modulus;
        UnivariatePolynomialView<BigInteger> r1 =
            FiniteFieldPolynomialArithmetic.remainder(
                value,
                modulus,
                field,
                work,
                stage + ".initial-remainder");
        UnivariatePolynomialView<BigInteger> s0 =
            UnivariatePolynomialView.zero(value.ring());
        UnivariatePolynomialView<BigInteger> s1 =
            UnivariatePolynomialView.one(value.ring());

        while (!r1.isZero()) {
            UnivariatePolynomialView.DivisionResult<BigInteger> division =
                r0.divideAndRemainder(
                    r1,
                    field,
                    work,
                    stage + ".euclid");
            UnivariatePolynomialView<BigInteger> nextS = s0.subtract(
                division.quotient().multiply(
                    s1,
                    work,
                    stage + ".bezout.multiply"),
                work,
                stage + ".bezout.subtract");
            r0 = r1;
            r1 = division.remainder();
            s0 = s1;
            s1 = nextS;
        }
        work.consume(stage + ".gcd-tests", 1);
        if (r0.degree() != 0) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_MODULAR_FACTORS_NOT_PAIRWISE_COPRIME");
        }
        BigInteger normalization = field.divide(
            field.one(),
            r0.coefficient(0));
        UnivariatePolynomialView<BigInteger> inverse =
            FiniteFieldPolynomialArithmetic.remainder(
                s0.scale(
                    normalization,
                    work,
                    stage + ".normalize"),
                modulus,
                field,
                work,
                stage + ".reduce");
        UnivariatePolynomialView<BigInteger> check =
            FiniteFieldPolynomialArithmetic.multiplyMod(
                value,
                inverse,
                modulus,
                field,
                work,
                stage + ".verify");
        work.consume(stage + ".verify.comparisons", 1);
        if (!check.isOne()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_CRT_INVERSE_VERIFICATION_FAILED");
        }
        return inverse;
    }

    static List<UnivariatePolynomialView<BigInteger>> corrections(
        UnivariatePolynomialView<BigInteger> error,
        List<CrtEntry> entries,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        ArrayList<UnivariatePolynomialView<BigInteger>> result =
            new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CrtEntry entry = entries.get(index);
            result.add(FiniteFieldPolynomialArithmetic.multiplyMod(
                error,
                entry.inverse(),
                entry.factor(),
                field,
                work,
                stage + ".factor-" + index));
        }
        return List.copyOf(result);
    }

    static void verifyCorrectionEquation(
        UnivariatePolynomialView<BigInteger> error,
        List<UnivariatePolynomialView<BigInteger>> corrections,
        List<CrtEntry> entries,
        PolynomialWorkBudget work,
        String stage
    ) {
        UnivariatePolynomialView<BigInteger> sum =
            UnivariatePolynomialView.zero(error.ring());
        for (int index = 0; index < corrections.size(); index++) {
            UnivariatePolynomialView<BigInteger> contribution =
                corrections.get(index).multiply(
                    entries.get(index).cofactor(),
                    work,
                    stage + ".factor-" + index + ".multiply");
            sum = sum.add(
                contribution,
                work,
                stage + ".factor-" + index + ".add");
        }
        work.consume(stage + ".comparisons", 1);
        if (!sum.equals(error)) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_CORRECTION_EQUATION_FAILED");
        }
    }

    static UnivariatePolynomialView<BigInteger> reduce(
        UnivariatePolynomialView<BigInteger> source,
        PolynomialRing<BigInteger> modularRing,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (!modularRing.coefficientDomain().id().equals(field.id())
                || !source.ring().variables().equals(
                    modularRing.variables())
                || source.ring().monomialOrder()
                    != modularRing.monomialOrder()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_REDUCTION_RING_MISMATCH");
        }
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(source.coefficientCount());
        for (BigInteger coefficient : source.coefficients()) {
            work.consume(stage + ".coefficients", 1);
            coefficients.add(field.canonical(coefficient));
        }
        return UnivariatePolynomialView.of(
            modularRing,
            coefficients);
    }

    private static UnivariatePolynomialView<BigInteger>
            multiplyFieldFactors(
        List<UnivariatePolynomialView<BigInteger>> factors,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (factors.isEmpty()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_FIELD_FACTOR_PRODUCT_REQUIRES_RING");
        }
        UnivariatePolynomialView<BigInteger> product =
            UnivariatePolynomialView.one(factors.getFirst().ring());
        for (int index = 0; index < factors.size(); index++) {
            product = product.multiply(
                factors.get(index),
                work,
                stage + ".factor-" + index);
        }
        return product;
    }

    private static void requireField(
        UnivariatePolynomialView<BigInteger> value,
        PrimeField field
    ) {
        if (!value.ring().coefficientDomain().id().equals(field.id())) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_PRIME_FIELD_MISMATCH");
        }
    }

    private static void requireSameFieldRing(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        PrimeField field
    ) {
        requireField(left, field);
        requireField(right, field);
        if (!left.ring().equals(right.ring())) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_PRIME_FIELD_RING_MISMATCH");
        }
    }

    record CrtEntry(
        UnivariatePolynomialView<BigInteger> factor,
        UnivariatePolynomialView<BigInteger> cofactor,
        UnivariatePolynomialView<BigInteger> inverse
    ) {
        CrtEntry {
            Objects.requireNonNull(factor, "factor");
            Objects.requireNonNull(cofactor, "cofactor");
            Objects.requireNonNull(inverse, "inverse");
            if (!factor.ring().equals(cofactor.ring())
                    || !factor.ring().equals(inverse.ring())) {
                throw new IllegalArgumentException(
                    "Hensel CRT entry ring mismatch");
            }
        }
    }
}
