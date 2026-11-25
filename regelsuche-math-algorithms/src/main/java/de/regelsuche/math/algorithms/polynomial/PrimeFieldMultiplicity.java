package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Complete univariate prime-field decomposition, including the unit and inseparable factors. */
public final class PrimeFieldMultiplicity {
    private PrimeFieldMultiplicity() {}

    public enum Status { COMPLETE, INVALID_EVIDENCE, UNSUPPORTED, INCONCLUSIVE }

    public record Certificate(SparsePolynomial<BigInteger> source, BigInteger unit,
                              List<PolynomialFactor<BigInteger>> factors) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(unit);
            factors = List.copyOf(factors);
        }
    }

    public record Result(Status status, Optional<Certificate> certificate, String reason, PolynomialWorkLedger work) {}

    public static Result factor(FactorizationRequest<BigInteger> request, FiniteFieldFactorizationPolicy policy,
                                BooleanSupplier cancelled) {
        return run(request, policy, null, cancelled);
    }

    public static Result verify(FactorizationRequest<BigInteger> request, FiniteFieldFactorizationPolicy policy,
                                Certificate evidence, BooleanSupplier cancelled) {
        return run(request, policy, Objects.requireNonNull(evidence), cancelled);
    }

    private static Result run(FactorizationRequest<BigInteger> request, FiniteFieldFactorizationPolicy policy,
                              Certificate evidence, BooleanSupplier cancelled) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(cancelled);
        var work = new PolynomialWorkBudget(request.maxWorkUnits(),
            () -> cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
        var source = request.source();
        try {
            work.consume("input", 0);
            if (!(source.ring().coefficientDomain() instanceof PrimeField field) || source.ring().variableCount() != 1)
                return stopped(Status.UNSUPPORTED, "REQUIRES_UNIVARIATE_PRIME_FIELD", work);
            work.consume("input.terms", source.termCount());
            var violation = request.structuralViolation();
            if (violation.isPresent()) return stopped(Status.INCONCLUSIVE, violation.orElseThrow(), work);
            if (request.maxCandidates() == 0 || field.prime() > policy.maxEnumeratedFieldElements()
                || !policy.permitsMatrixDegree(source.degree(0)))
                return stopped(Status.INCONCLUSIVE, "FACTORIZATION_POLICY", work);
            work.consume("input.dense", source.degree(0) + 1L);
            var original = UnivariatePolynomialView.from(source);
            if (evidence == null) {
                var factors = new ArrayList<PolynomialFactor<BigInteger>>();
                var monic = original.monic(field, work, "normalize");
                decompose(monic, 1, field, factors, work);
                evidence = new Certificate(source, original.leadingCoefficient(), factors);
            }
            String error = check(original, source, evidence, field, work);
            work.consume("final", 0);
            if (error != null) return stopped(Status.INVALID_EVIDENCE, error, work);
            return new Result(Status.COMPLETE, Optional.of(evidence), "", work.ledger());
        } catch (PolynomialWorkBudget.LimitReached stop) {
            return stopped(Status.INCONCLUSIVE, "WORK_LIMIT", work);
        } catch (PolynomialWorkBudget.Cancelled stop) {
            return stopped(Status.INCONCLUSIVE, "CANCELLED", work);
        }
    }

    private static Result stopped(Status status, String reason, PolynomialWorkBudget work) {
        return new Result(status, Optional.empty(), reason, work.ledger());
    }

    /** Separate exact multiplicity layers. Residual exponents divisible by p descend via Frobenius. */
    private static void decompose(UnivariatePolynomialView<BigInteger> f, int scale, PrimeField field,
                                  List<PolynomialFactor<BigInteger>> factors, PolynomialWorkBudget work) {
        if (f.isOne()) return;
        var common = UnivariatePolynomialAlgorithms.gcd(f, f.derivative(work, "layers.derivative"),
            field, work, "layers.gcd");
        var remaining = f.exactQuotient(common, field, work, "layers.divide");
        for (int i = 1; !remaining.isOne(); i++) {
            var next = UnivariatePolynomialAlgorithms.gcd(remaining, common, field, work, "layers.intersection");
            var layer = remaining.exactQuotient(next, field, work, "layers.extract");
            if (!layer.isOne()) {
                var kernel = BerlekampKernel.compute(layer, field, work);
                for (var factor : FiniteFieldFactorization.split(layer, kernel, field, work)) {
                    work.consume("layers.issue-factor", factor.degree() + 1L);
                    factors.add(new PolynomialFactor<>(factor.toSparsePolynomial(), Math.multiplyExact(i, scale)));
                }
            }
            remaining = next;
            common = common.exactQuotient(next, field, work, "layers.remove");
        }
        if (!common.isOne()) {
            int p = field.prime();
            var root = new ArrayList<BigInteger>();
            for (int k = 0; k <= common.degree(); k++) {
                work.consume("layers.frobenius-root", 1);
                if (k % p == 0) root.add(common.coefficient(k));
                else if (common.coefficient(k).signum() != 0)
                    throw new IllegalStateException("INVALID_FROBENIUS_RESIDUE");
            }
            decompose(UnivariatePolynomialView.of(f.ring(), root), Math.multiplyExact(scale, p), field, factors, work);
        }
    }

    /** Verify source binding, uniqueness, Rabin irreducibility, and complete reconstruction. */
    private static String check(UnivariatePolynomialView<BigInteger> original, SparsePolynomial<BigInteger> source,
                                Certificate evidence, PrimeField field, PolynomialWorkBudget work) {
        work.consume("check.source-terms", source.termCount());
        if (source.termCount() != evidence.source().termCount() || !source.equals(evidence.source())) return "SOURCE";
        if (!original.leadingCoefficient().equals(evidence.unit())) return "UNIT";
        if (evidence.factors().size() > original.degree()) return "FACTOR_COUNT";
        var product = FiniteFieldPolynomialArithmetic.constant(source.ring(), evidence.unit());
        var seen = new ArrayList<SparsePolynomial<BigInteger>>();
        long remainingDegree = original.degree();
        for (var item : evidence.factors()) {
            var sparse = item.polynomial();
            work.consume("check.factor", 1);
            if (!source.ring().equals(sparse.ring())) return "FACTOR_RING";
            work.consume("check.factor-terms", sparse.termCount());
            long degree = sparse.degree(0);
            long contribution = degree * item.multiplicity();
            if (contribution > remainingDegree || !BigInteger.ONE.equals(sparse.leadingCoefficient())) return "FACTOR_SHAPE";
            for (var previous : seen) {
                work.consume("check.distinct", 1L + sparse.termCount());
                if (sparse.equals(previous)) return "DUPLICATE_FACTOR";
            }
            seen.add(sparse);
            remainingDegree -= contribution;
            work.consume("check.factor-conversion", degree + 1);
            var factor = UnivariatePolynomialView.from(sparse);
            if (!FiniteFieldPolynomialArithmetic.verifyIrreducible(factor, field, work, "check.rabin").irreducible())
                return "REDUCIBLE";
            for (int i = 0; i < item.multiplicity(); i++) product = product.multiply(factor, work, "check.multiply");
        }
        work.consume("check.product", original.degree() + 1L);
        return original.equals(product) ? null : "PRODUCT";
    }
}
