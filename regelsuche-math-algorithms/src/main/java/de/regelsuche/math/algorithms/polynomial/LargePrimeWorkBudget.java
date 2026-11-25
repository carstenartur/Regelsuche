package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigPrimeField;
import de.regelsuche.polynomial.ExactField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PolynomialWorkSink;
import de.regelsuche.polynomial.PrimeField;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** One non-resettable work, allocation and elapsed-time budget, including final verification. */
final class LargePrimeWorkBudget implements PolynomialWorkSink {
    private final LargePrimeFactorizationVerifier.Policy policy;
    private final BooleanSupplier cancelled;
    private final PolynomialWorkBudget work;
    private final long started = System.nanoTime();
    private long coefficientSlots;
    private long bitWeight = 1;
    private int modulusBits;
    private int attempts;

    LargePrimeWorkBudget(long maxWork, LargePrimeFactorizationVerifier.Policy policy, BooleanSupplier cancelled) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        work = new PolynomialWorkBudget(maxWork);
    }

    @Override public void checkpoint(String stage) { check(); }

    @Override public void consume(String stage, long units) {
        check();
        if (units < 0) throw new IllegalArgumentException("negative work");
        if (units > Long.MAX_VALUE / bitWeight) stop("WORK_LIMIT");
        try {
            work.consume(stage, units * bitWeight);
        } catch (PolynomialWorkBudget.LimitReached exhausted) {
            stop("WORK_LIMIT");
        }
        // A conservative cumulative reservation for scalar arithmetic temporaries.
        // Explicit polynomial/container allocations are reserved by their callers.
        if (units > Long.MAX_VALUE / 16) stop("ALLOCATION_LIMIT");
        reserve(16 * units);
    }

    void reserve(long slots) {
        check();
        if (slots < 0 || slots > policy.maxAllocatedCoefficientSlots() - coefficientSlots)
            stop("ALLOCATION_LIMIT");
        coefficientSlots += slots;
    }

    void reserveVector(long size, long copies) {
        if (size < 0 || size > Integer.MAX_VALUE - 8L) stop("DEGREE_LIMIT");
        if (copies < 0 || size > Long.MAX_VALUE / Math.max(1, copies)) stop("ALLOCATION_LIMIT");
        reserve(size * copies + 1);
    }

    void inversion(String stage) {
        // Extended Euclid is on at most b-bit inputs. Poll before and after the
        // bounded BigInteger primitive; account b modular-operation units.
        consume(stage, Math.max(1, modulusBits));
    }

    void attempt(int maximum) {
        consume("search.split-attempt", 1);
        if (attempts >= maximum) stop("SPLIT_ATTEMPT_LIMIT");
        attempts++;
    }

    int attempts() { return attempts; }
    int modulusBits() { return modulusBits; }
    PolynomialWorkLedger ledger() { return work.ledger(); }

    Domain prepare(FactorizationRequest<BigInteger> request, String stage) {
        consume(stage + ".entry", 0);
        var source = request.source();
        Domain domain;
        if (source.ring().coefficientDomain() instanceof PrimeField small) {
            domain = new Domain(small, small.modulus(), false);
        } else if (source.ring().coefficientDomain() instanceof BigPrimeField large) {
            domain = new Domain(large, large.modulus(), large.primality() == BigPrimeField.Primality.ASSUMED_PRIME);
        } else {
            throw new Stop(LargePrimeFactorization.Status.UNSUPPORTED, "REQUIRES_PRIME_FIELD");
        }
        if (source.ring().variableCount() != 1)
            throw new Stop(LargePrimeFactorization.Status.UNSUPPORTED, "REQUIRES_UNIVARIATE_POLYNOMIAL");
        modulusBits = domain.modulus().bitLength();
        if (modulusBits > policy.maxModulusBitLength()) stop("MODULUS_BIT_LIMIT");
        long words = (modulusBits + 31L) / 32;
        bitWeight = words * words;
        consume(stage + ".source-terms", source.termCount());
        var limits = request.structuralLimits();
        if (source.degree(0) > limits.maxTotalDegree()) stop("MAX_TOTAL_DEGREE_EXCEEDED");
        if (source.termCount() > limits.maxTerms()) stop("MAX_TERMS_EXCEEDED");
        int index = 0;
        for (BigInteger coefficient : source.terms().values()) {
            if ((index++ & 1023) == 0) checkpoint(stage + ".coefficient-bits");
            if (domain.field().bitLength(coefficient) > limits.maxCoefficientBitLength())
                stop("MAX_COEFFICIENT_BIT_LENGTH_EXCEEDED");
        }
        reserveVector(source.degree(0) + 1L, 4);
        if (domain.conditional() && compositeWitness(domain.modulus(), stage + ".modulus-screen"))
            throw new Stop(LargePrimeFactorization.Status.UNSUPPORTED, "COMPOSITE_MODULUS");
        return domain;
    }

    /** Fixed-base Miller-Rabin can disprove the assumption, never establish it. */
    private boolean compositeWitness(BigInteger modulus, String stage) {
        if (modulus.equals(BigInteger.TWO)) return false;
        if (!modulus.testBit(0)) return true;
        consume(stage, 2);
        BigInteger minusOne = modulus.subtract(BigInteger.ONE);
        int twos = minusOne.getLowestSetBit();
        BigInteger oddPart = minusOne.shiftRight(twos);
        for (int base : new int[]{2, 3, 5, 7, 11, 13, 17}) {
            consume(stage, 1);
            BigInteger a = BigInteger.valueOf(base);
            if (modulus.equals(a)) return false;
            if (a.compareTo(modulus) >= 0) continue;
            BigInteger value = scalarPow(a, oddPart, modulus, stage);
            if (value.equals(BigInteger.ONE) || value.equals(minusOne)) continue;
            boolean passed = false;
            for (int i = 1; i < twos; i++) {
                consume(stage, 1);
                value = value.multiply(value).mod(modulus);
                if (value.equals(minusOne)) { passed = true; break; }
                if (value.equals(BigInteger.ONE)) break;
            }
            if (!passed) return true;
        }
        return false;
    }

    private BigInteger scalarPow(BigInteger base, BigInteger exponent, BigInteger modulus, String stage) {
        BigInteger result = BigInteger.ONE;
        for (int bit = exponent.bitLength() - 1; bit >= 0; bit--) {
            consume(stage, 1);
            result = result.multiply(result).mod(modulus);
            if (exponent.testBit(bit)) {
                consume(stage, 1);
                result = result.multiply(base).mod(modulus);
            }
        }
        return result;
    }

    private void check() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) stop("CANCELLED");
        if (System.nanoTime() - started >= policy.maxNanos()) stop("TIME_LIMIT");
    }

    static void stop(String reason) { throw new Stop(LargePrimeFactorization.Status.INCONCLUSIVE, reason); }

    LargePrimeFactorization.Result stopped(LargePrimeFactorization.Status status, String reason) {
        return new LargePrimeFactorization.Result(status, Optional.empty(), reason, ledger(), coefficientSlots, attempts);
    }

    LargePrimeFactorization.Result completed(LargePrimeFactorization.Certificate certificate, boolean conditional) {
        consume("final", 0);
        return new LargePrimeFactorization.Result(conditional ? LargePrimeFactorization.Status.COMPLETE_CONDITIONAL
            : LargePrimeFactorization.Status.COMPLETE, Optional.of(certificate), "", ledger(), coefficientSlots, attempts);
    }

    record Domain(ExactField<BigInteger> field, BigInteger modulus, boolean conditional) {}

    static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final LargePrimeFactorization.Status status;
        final String reason;
        Stop(LargePrimeFactorization.Status status, String reason) {
            super(reason, null, false, false);
            this.status = status;
            this.reason = reason;
        }
    }
}
