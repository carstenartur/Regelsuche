package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Independent invariants for issuer-owned Zassenhaus results. */
final class ZassenhausEvidence {
    private ZassenhausEvidence() {
    }

    static void validateCompleted(
        BigInteger coefficientBound,
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<List<Integer>> partitions,
        long candidatesConsidered,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy
    ) {
        if (!selection.completed()
                || !lifting.completed()
                || coefficientBound.signum() < 0
                || targetModulus.compareTo(
                    coefficientBound.shiftLeft(1)) <= 0
                || !targetModulus.equals(lifting.targetModulus())
                || !lifting.selectionCertificateHash().equals(
                    selection.certificateHash())
                || candidatesConsidered
                    > policy.maxSubsetCandidates()
                || candidatesConsidered
                    + selection.attempts().size()
                    > request.maxCandidates()
                || factors.isEmpty()
                || factors.size() != partitions.size()
                || !work.within(request.maxWorkUnits())
                || !extendsLedger(work, lifting.work())) {
            throw new IllegalArgumentException(
                "completed Zassenhaus contract is invalid");
        }

        int modularFactorCount =
            selection.modularFactorization().factors().size();
        boolean[] assigned = new boolean[modularFactorCount];
        for (int index = 0; index < factors.size(); index++) {
            SparsePolynomial<BigInteger> factor = factors.get(index);
            if (!request.source().ring().equals(factor.ring())
                    || factor.isConstant()
                    || factor.leadingCoefficient().signum() <= 0
                    || factor.maxCoefficientBitLength()
                        > policy.maxIntermediateCoefficientBitLength()
                    || !primitive(factor)) {
                throw new IllegalArgumentException(
                    "completed Zassenhaus factor is invalid");
            }
            List<Integer> partition = partitions.get(index);
            for (int modularIndex : partition) {
                if (modularIndex < 0
                        || modularIndex >= assigned.length
                        || assigned[modularIndex]) {
                    throw new IllegalArgumentException(
                        "Zassenhaus modular partition is invalid");
                }
                assigned[modularIndex] = true;
            }
            verifyReduction(factor, partition, selection);
        }
        for (boolean present : assigned) {
            if (!present) {
                throw new IllegalArgumentException(
                    "Zassenhaus modular partition is incomplete");
            }
        }

        SparsePolynomial<BigInteger> product =
            SparsePolynomial.one(request.source().ring());
        for (SparsePolynomial<BigInteger> factor : factors) {
            product = product.multiply(factor);
        }
        if (!request.source().equals(product)) {
            throw new IllegalArgumentException(
                "Zassenhaus factors do not reconstruct source");
        }
    }

    private static void verifyReduction(
        SparsePolynomial<BigInteger> factor,
        List<Integer> partition,
        SuitablePrimeSelectionResult selection
    ) {
        PolynomialRing<BigInteger> modularRing =
            selection.modularSource().ring();
        PrimeField field = (PrimeField)
            modularRing.coefficientDomain();
        SparsePolynomial<BigInteger> expected =
            SparsePolynomial.one(modularRing);
        List<PolynomialFactor<BigInteger>> modularFactors =
            selection.modularFactorization().factors();
        for (int index : partition) {
            expected = expected.multiply(
                modularFactors.get(index).polynomial());
        }
        expected = expected.scale(
            field.canonical(factor.leadingCoefficient()));
        SparsePolynomial<BigInteger> actual =
            reduce(factor, modularRing, field);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "integer factor does not match its modular partition");
        }
    }

    private static SparsePolynomial<BigInteger> reduce(
        SparsePolynomial<BigInteger> source,
        PolynomialRing<BigInteger> ring,
        PrimeField field
    ) {
        NavigableMap<Monomial, BigInteger> terms =
            new TreeMap<>(ring.monomialComparator());
        for (Map.Entry<Monomial, BigInteger> term :
                source.terms().entrySet()) {
            BigInteger coefficient =
                field.canonical(term.getValue());
            if (!field.isZero(coefficient)) {
                terms.put(term.getKey(), coefficient);
            }
        }
        return new SparsePolynomial<>(ring, terms);
    }

    private static boolean primitive(
        SparsePolynomial<BigInteger> polynomial
    ) {
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : polynomial.terms().values()) {
            gcd = gcd.gcd(coefficient.abs());
        }
        return BigInteger.ONE.equals(gcd);
    }

    private static boolean extendsLedger(
        PolynomialWorkLedger work,
        PolynomialWorkLedger prefix
    ) {
        if (work.totalWorkUnits() < prefix.totalWorkUnits()) {
            return false;
        }
        return prefix.stages().entrySet().stream().allMatch(entry ->
            work.units(entry.getKey()) == entry.getValue());
    }
}
