package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent complete-factorization checking with no field-element enumeration.
 * The prime assumption remains explicit: Rabin and reconstruction prove the
 * factorization conditional on primality when the input domain is conditional.
 */
public final class LargePrimeFactorizationVerifier {
    private LargePrimeFactorizationVerifier() {}

    /**
     * Allocation reservations are cumulative, including arithmetic temporaries,
     * and never reset between search and checking. Together with the modulus bit
     * limit they bound coefficient storage; they are not a JVM heap-byte meter.
     */
    public record Policy(int maxModulusBitLength, long maxAllocatedCoefficientSlots, long maxNanos) {
        public Policy {
            if (maxModulusBitLength < 2 || maxAllocatedCoefficientSlots < 1 || maxNanos < 1)
                throw new IllegalArgumentException("invalid large-prime verification policy");
        }
    }

    public static LargePrimeFactorization.Result verify(FactorizationRequest<BigInteger> request, Policy policy,
        LargePrimeFactorization.Certificate certificate, BooleanSupplier cancelled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(certificate, "certificate");
        var work = new LargePrimeWorkBudget(request.maxWorkUnits(), policy, cancelled);
        try {
            return verifyWithBudget(request, certificate, work);
        } catch (LargePrimeWorkBudget.Stop stop) {
            return work.stopped(stop.status, stop.reason);
        } catch (ArithmeticException invalidField) {
            return work.stopped(LargePrimeFactorization.Status.UNSUPPORTED, "NON_FIELD_ARITHMETIC");
        }
    }

    /** Same verifier and same non-resettable budget used for producer output and external evidence. */
    static LargePrimeFactorization.Result verifyWithBudget(FactorizationRequest<BigInteger> request,
        LargePrimeFactorization.Certificate certificate, LargePrimeWorkBudget work) {
        var domain = work.prepare(request, "verify.input");
        String error = check(request.source(), certificate, domain.modulus(), work);
        work.consume("verify.final", 0);
        if (error != null) return work.stopped(LargePrimeFactorization.Status.INVALID_EVIDENCE, error);
        return work.completed(certificate, domain.conditional());
    }

    private static String check(SparsePolynomial<BigInteger> source, LargePrimeFactorization.Certificate evidence,
        BigInteger modulus, LargePrimeWorkBudget work) {
        work.consume("verify.source-binding", source.termCount());
        if (!source.ring().coefficientDomain().equals(evidence.source().ring().coefficientDomain())) return "SOURCE_DOMAIN";
        if (!samePolynomial(source, evidence.source(), work)) return "SOURCE";
        if (!source.leadingCoefficient().equals(evidence.unit())) return "UNIT";
        int degree = source.degree(0);
        if (evidence.factors().size() > degree) return "FACTOR_COUNT";
        var arithmetic = new IndependentBigPrimePolynomialArithmetic(modulus, work);
        var original = arithmetic.from(source);
        var product = arithmetic.constant(evidence.unit());
        var seen = new ArrayList<SparsePolynomial<BigInteger>>();
        long remaining = degree;
        for (var item : evidence.factors()) {
            work.consume("verify.factor", 1);
            var polynomial = item.polynomial();
            if (!source.ring().coefficientDomain().equals(polynomial.ring().coefficientDomain())
                    || !source.ring().equals(polynomial.ring())) return "FACTOR_RING";
            if (polynomial.termCount() > degree + 1L) return "FACTOR_SHAPE";
            work.consume("verify.factor-terms", polynomial.termCount());
            int factorDegree = polynomial.degree(0);
            long contribution = (long) factorDegree * item.multiplicity();
            if (factorDegree < 1 || contribution > remaining || !BigInteger.ONE.equals(polynomial.leadingCoefficient()))
                return "FACTOR_SHAPE";
            for (var earlier : seen) {
                work.consume("verify.distinct", polynomial.termCount() + 1L);
                if (samePolynomial(polynomial, earlier, work)) return "DUPLICATE_FACTOR";
            }
            work.reserve(1);
            seen.add(polynomial);
            remaining -= contribution;
            var factor = arithmetic.from(polynomial);
            if (!arithmetic.irreducible(factor)) return "REDUCIBLE_FACTOR";
            for (int i = 0; i < item.multiplicity(); i++) {
                work.consume("verify.multiplicity", 1);
                product = arithmetic.multiply(product, factor);
            }
        }
        work.consume("verify.product-comparison", degree + 1L);
        return remaining == 0 && arithmetic.equal(original, product) ? null : "PRODUCT";
    }

    private static boolean samePolynomial(SparsePolynomial<BigInteger> left,
        SparsePolynomial<BigInteger> right, LargePrimeWorkBudget work) {
        if (!left.ring().equals(right.ring()) || left.termCount() != right.termCount()) return false;
        var first = left.terms().entrySet().iterator();
        var second = right.terms().entrySet().iterator();
        int index = 0;
        while (first.hasNext()) {
            if ((index++ & 1023) == 0) work.checkpoint("verify.source-comparison");
            if (!first.next().equals(second.next())) return false;
        }
        work.checkpoint("verify.source-comparison");
        return true;
    }
}
