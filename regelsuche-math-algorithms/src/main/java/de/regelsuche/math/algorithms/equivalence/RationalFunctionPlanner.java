package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Source-bound rational simplification over the existing exact polynomial representation.
 * Cancellation uses exact polynomial division by visible source divisors. The independent
 * normal-form receiver re-parses both expressions and all assumptions before certification.
 * A cancelled divisor remains a required condition of the original expression.
 */
public final class RationalFunctionPlanner {
    private static final GradedReverseLexOrder ORDER = new GradedReverseLexOrder();
    private RationalFunctionPlanner() {}

    public static Result simplify(String source, List<String> assumptions, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source, "source");
        assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        var work = new CertificateWork(limits, cancellation);
        String candidate = source;
        List<String> conditions = List.of();
        try {
            work.charge(0);
            var producer = new RationalFunctionNormalFormEquivalenceService(work);
            var projected = producer.parse(source);
            var arithmetic = producer.arithmetic();
            conditions = producer.requiredFactorKeys(projected, projected).stream()
                .map(factor -> factor + " != 0").toList();
            Polynomial numerator = projected.numerator(), denominator = projected.denominator();
            var divisors = new ArrayList<>(projected.denominatorFactors());
            divisors.add(denominator);
            for (var divisor : divisors) {
                work.charge(1);
                if (divisor.isZero() || divisor.totalDegree() == 0) continue;
                // Repeated factors are handled without factoring an arbitrary sum.
                while (denominator.totalDegree() >= divisor.totalDegree()) {
                    var reducedDenominator = divideExactly(denominator, divisor, arithmetic);
                    if (reducedDenominator.isEmpty()) break;
                    var reducedNumerator = divideExactly(numerator, divisor, arithmetic);
                    if (reducedNumerator.isEmpty()) break;
                    numerator = reducedNumerator.orElseThrow();
                    denominator = reducedDenominator.orElseThrow();
                }
            }
            // Normalize a constant scale without introducing a new domain restriction.
            var leading = denominator.leadingTerm(ORDER).orElseThrow().coefficient();
            var scale = Polynomial.constant(Rational.ONE.divide(leading));
            numerator = arithmetic.multiply(numerator, scale);
            denominator = arithmetic.multiply(denominator, scale);
            candidate = denominator.equals(Polynomial.constant(Rational.ONE))
                ? arithmetic.text(numerator)
                : "(" + arithmetic.text(numerator) + ")/(" + arithmetic.text(denominator) + ")";
            arithmetic.source(candidate);
            var parser = new ExpressionParser();
            if (operations(parser.parseExactTerm(candidate).expression(), arithmetic)
                    > operations(parser.parseExactTerm(source).expression(), arithmetic)) candidate = source;
            // The proposal must work on the complete original domain. The receiver
            // deliberately does not infer arbitrary factorizations of new divisors.
            // Retain the source if its conditions cannot authorize the new syntax.
            var domainCheck = new RationalFunctionNormalFormEquivalenceService(work)
                .evaluate(source, candidate, conditions);
            if (!domainCheck.equivalent()) candidate = source;
            var receipt = new Certificate(source, candidate, assumptions);
            var evaluation = new RationalFunctionNormalFormEquivalenceService(work)
                .evaluate(source, candidate, assumptions);
            work.charge(0);
            if (evaluation.equivalent()) return new Result(Status.VERIFIED, source, candidate,
                conditions, Optional.of(receipt), work.used(), "EXACT_RATIONAL_IDENTITY");
            if (evaluation.status() == RationalFunctionNormalFormEquivalenceService.Status.MISSING_ASSUMPTION)
                return new Result(Status.REQUIRES_ASSUMPTIONS, source, candidate, conditions,
                    Optional.empty(), work.used(), "ORIGINAL_DOMAIN_NOT_DECLARED");
            return new Result(Status.UNAVAILABLE, source, candidate, conditions, Optional.empty(),
                work.used(), evaluation.detail());
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, source, candidate, conditions, Optional.empty(), work.used(), stop.getMessage());
        } catch (RationalFunctionNormalFormEquivalenceService.UnsupportedExpression unsupported) {
            return new Result(Status.UNAVAILABLE, source, candidate, conditions, Optional.empty(), work.used(), unsupported.getMessage());
        }
    }

    public static Verification verify(String expectedSource, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(expectedSource, "expectedSource");
        Objects.requireNonNull(certificate, "certificate");
        var work = new CertificateWork(limits, cancellation);
        try {
            work.charge(0);
            work.charge(Math.max(expectedSource.length(), certificate.source().length()));
            if (!expectedSource.equals(certificate.source()))
                return new Verification(false, "SOURCE_MISMATCH", work.used());
            var result = new RationalFunctionNormalFormEquivalenceService(work).evaluate(
                expectedSource, certificate.candidate(), certificate.assumptions());
            work.charge(0);
            return new Verification(result.equivalent(), result.status().name(), work.used());
        } catch (CertificateWork.Stop stop) {
            return new Verification(false, stop.getMessage(), work.used());
        }
    }

    private static Optional<Polynomial> divideExactly(Polynomial dividend, Polynomial divisor,
            RationalFunctionWork arithmetic) {
        Polynomial quotient = Polynomial.zero(), current = dividend;
        var leadingDivisor = divisor.leadingTerm(ORDER).orElseThrow();
        while (!current.isZero()) {
            arithmetic.charge(1L + current.termCount() + divisor.termCount());
            var leading = current.leadingTerm(ORDER).orElseThrow();
            if (!leadingDivisor.monomial().divides(leading.monomial())) return Optional.empty();
            var term = arithmetic.checked(Polynomial.term(
                leading.monomial().divideBy(leadingDivisor.monomial()),
                leading.coefficient().divide(leadingDivisor.coefficient())));
            quotient = arithmetic.add(quotient, term, false);
            current = arithmetic.add(current, arithmetic.multiply(term, divisor), true);
        }
        return Optional.of(quotient);
    }

    /** Saturated expanded arithmetic cost; a power spelling cannot manufacture an improvement. */
    private static long operations(Expr expression, RationalFunctionWork work) {
        work.charge(1);
        if (!(expression instanceof BinaryExpr binary)) return 0;
        if (binary.operator() == BinaryOperator.POW && binary.right() instanceof NumberExpr exponent) {
            int power = exponent.value().intValueExact();
            return power == 0 ? 0 : Math.min(1_000_000_000L,
                power * operations(binary.left(), work) + power - 1);
        }
        return Math.min(1_000_000_000L, 1 + operations(binary.left(), work) + operations(binary.right(), work));
    }

    public enum Status { VERIFIED, REQUIRES_ASSUMPTIONS, UNAVAILABLE }

    /** A claim, never self-authorizing; verify against the expected original source. */
    public record Certificate(String source, String candidate, List<String> assumptions) {
        public Certificate {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(candidate, "candidate");
            assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        }
    }

    public record Result(Status status, String source, String candidate, List<String> requiredConditions,
            Optional<Certificate> certificate, long work, String reason) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(source);
            Objects.requireNonNull(candidate);
            requiredConditions = List.copyOf(requiredConditions);
            Objects.requireNonNull(certificate);
            Objects.requireNonNull(reason);
        }
    }

    public record Verification(boolean valid, String reason, long work) {}
}
