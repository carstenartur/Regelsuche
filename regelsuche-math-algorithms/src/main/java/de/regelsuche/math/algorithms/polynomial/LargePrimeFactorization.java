package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Bounded standard factorization over prime moduli of arbitrary permitted bit size. */
public final class LargePrimeFactorization {
    private LargePrimeFactorization() {}

    public enum Status { COMPLETE, COMPLETE_CONDITIONAL, INVALID_EVIDENCE, UNSUPPORTED, INCONCLUSIVE }

    public record Policy(LargePrimeFactorizationVerifier.Policy resources, int maxSplitAttempts, long randomSeed) {
        public Policy {
            Objects.requireNonNull(resources, "resources");
            if (maxSplitAttempts < 0) throw new IllegalArgumentException("negative split attempt limit");
        }
    }

    public record Certificate(SparsePolynomial<BigInteger> source, BigInteger unit,
                              List<PolynomialFactor<BigInteger>> factors) {
        public Certificate {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(unit, "unit");
            factors = List.copyOf(factors);
        }
    }

    public record Result(Status status, Optional<Certificate> certificate, String reason,
                         PolynomialWorkLedger work, long allocatedCoefficientSlots, int splitAttempts) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(work, "work");
            boolean complete = status == Status.COMPLETE || status == Status.COMPLETE_CONDITIONAL;
            if (complete != certificate.isPresent() || allocatedCoefficientSlots < 0 || splitAttempts < 0)
                throw new IllegalArgumentException("inconsistent factorization result");
        }
    }

    public static Result factor(FactorizationRequest<BigInteger> request, Policy policy, BooleanSupplier cancelled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        var work = new LargePrimeWorkBudget(request.maxWorkUnits(), policy.resources(), cancelled);
        try {
            var domain = work.prepare(request, "search.input");
            if (request.maxCandidates() == 0) LargePrimeWorkBudget.stop("CANDIDATE_LIMIT");
            var arithmetic = new LargePrimePolynomialArithmetic(request.source().ring(), domain, work);
            var original = arithmetic.from(request.source());
            var search = new Search(arithmetic, domain.modulus(), work, policy);
            search.squareFree(arithmetic.monic(original), 1);
            work.reserveVector(search.factors.size(), 2);
            var certificate = new Certificate(request.source(), original.leadingCoefficient(), search.factors);
            return LargePrimeFactorizationVerifier.verifyWithBudget(request, certificate, work);
        } catch (LargePrimeWorkBudget.Stop stop) {
            return work.stopped(stop.status, stop.reason);
        } catch (ArithmeticException invalidField) {
            return work.stopped(Status.UNSUPPORTED, "NON_FIELD_ARITHMETIC");
        }
    }

    private static final class Search {
        private final LargePrimePolynomialArithmetic arithmetic;
        private final BigInteger prime;
        private final LargePrimeWorkBudget work;
        private final Policy policy;
        private final Random random;
        private final List<PolynomialFactor<BigInteger>> factors = new ArrayList<>();

        Search(LargePrimePolynomialArithmetic arithmetic, BigInteger prime, LargePrimeWorkBudget work, Policy policy) {
            this.arithmetic = arithmetic;
            this.prime = prime;
            this.work = work;
            this.policy = policy;
            random = new Random(policy.randomSeed());
        }

        /** Derivative/GCD layers, including recursively extracted p-th powers. */
        void squareFree(UnivariatePolynomialView<BigInteger> value, int scale) {
            work.consume("search.square-free", 1);
            if (value.isOne()) return;
            var common = arithmetic.gcd(value, arithmetic.derivative(value));
            var remaining = arithmetic.quotient(value, common);
            for (int multiplicity = 1; !remaining.isOne(); multiplicity++) {
                work.consume("search.multiplicity-layer", 1);
                var intersection = arithmetic.gcd(remaining, common);
                var layer = arithmetic.quotient(remaining, intersection);
                if (!layer.isOne()) distinctDegree(layer, Math.multiplyExact(scale, multiplicity));
                remaining = intersection;
                common = arithmetic.quotient(common, intersection);
            }
            if (!common.isOne()) {
                var root = arithmetic.frobeniusRoot(common);
                // The degree check in frobeniusRoot proves that p fits an int.
                squareFree(root, Math.multiplyExact(scale, prime.intValueExact()));
            }
        }

        /** Standard Frobenius DDF: no field-element scan and no x^(p^d) expansion. */
        private void distinctDegree(UnivariatePolynomialView<BigInteger> value, int multiplicity) {
            var remaining = value;
            var x = arithmetic.x();
            var frobenius = x;
            for (int degree = 1; 2L * degree <= remaining.degree(); degree++) {
                work.consume("search.distinct-degree", 1);
                frobenius = arithmetic.power(frobenius, prime, remaining);
                var group = arithmetic.gcd(remaining, arithmetic.subtract(frobenius, x));
                if (!group.isOne()) {
                    equalDegree(group, degree, multiplicity);
                    remaining = arithmetic.quotient(remaining, group);
                    if (!remaining.isOne()) frobenius = arithmetic.remainder(frobenius, remaining);
                }
            }
            if (!remaining.isOne()) issue(remaining, multiplicity);
        }

        /** Iterative Cantor-Zassenhaus splitting with one total attempt allowance. */
        private void equalDegree(UnivariatePolynomialView<BigInteger> value, int degree, int multiplicity) {
            var pending = new ArrayDeque<UnivariatePolynomialView<BigInteger>>();
            work.reserve(1);
            pending.push(value);
            while (!pending.isEmpty()) {
                work.consume("search.equal-degree", 1);
                var current = pending.pop();
                if (current.degree() == degree) {
                    issue(current, multiplicity);
                    continue;
                }
                UnivariatePolynomialView<BigInteger> divisor;
                do {
                    work.attempt(policy.maxSplitAttempts());
                    var candidate = arithmetic.randomBelowDegree(current.degree(), random);
                    divisor = arithmetic.gcd(current, candidate);
                    if (proper(divisor, current)) break;
                    if (divisor.degree() == current.degree()) continue;
                    var splitter = prime.equals(BigInteger.TWO)
                        ? trace(candidate, degree, current)
                        : oddSplitter(candidate, degree, current);
                    divisor = arithmetic.gcd(current, splitter);
                } while (!proper(divisor, current));
                work.reserve(2);
                pending.push(divisor);
                pending.push(arithmetic.quotient(current, divisor));
            }
        }

        private UnivariatePolynomialView<BigInteger> trace(UnivariatePolynomialView<BigInteger> candidate,
            int degree, UnivariatePolynomialView<BigInteger> modulus) {
            var sum = candidate;
            var power = candidate;
            for (int i = 1; i < degree; i++) {
                work.consume("search.binary-trace", 1);
                power = arithmetic.power(power, BigInteger.TWO, modulus);
                sum = arithmetic.add(sum, power);
            }
            return sum;
        }

        private UnivariatePolynomialView<BigInteger> oddSplitter(UnivariatePolynomialView<BigInteger> candidate,
            int degree, UnivariatePolynomialView<BigInteger> modulus) {
            work.consume("search.odd-exponent", 2);
            var power = arithmetic.power(candidate, prime.subtract(BigInteger.ONE).shiftRight(1), modulus);
            var norm = power;
            // Product of Frobenius conjugates of a^((p-1)/2) equals
            // a^((p^degree-1)/2). Only p-bit exponents are ever materialized.
            for (int i = 1; i < degree; i++) {
                work.consume("search.odd-norm", 1);
                power = arithmetic.power(power, prime, modulus);
                norm = arithmetic.multiplyMod(norm, power, modulus);
            }
            return arithmetic.subtract(norm, arithmetic.one());
        }

        private static boolean proper(UnivariatePolynomialView<BigInteger> divisor,
                                      UnivariatePolynomialView<BigInteger> value) {
            return divisor.degree() > 0 && divisor.degree() < value.degree();
        }

        private void issue(UnivariatePolynomialView<BigInteger> factor, int multiplicity) {
            work.reserve(1);
            factors.add(new PolynomialFactor<>(arithmetic.sparse(factor), multiplicity));
        }
    }
}
