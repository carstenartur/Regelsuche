package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Dynamic evaluation in the product of fields represented by a square-free quotient. */
public final class DynamicAlgebraEvaluation {
    private DynamicAlgebraEvaluation() {}

    public sealed interface Expr permits Literal, Add, Product, Inverse, IfZero {}

    /** Ascending integer coefficients, reduced in the requested field. */
    public record Literal(List<BigInteger> coefficients) implements Expr {
        public Literal { coefficients = List.copyOf(coefficients); }
    }
    public record Add(Expr left, Expr right) implements Expr {
        public Add { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public record Product(Expr left, Expr right) implements Expr {
        public Product { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public record Inverse(Expr operand) implements Expr {
        public Inverse { Objects.requireNonNull(operand); }
    }
    public record IfZero(Expr condition, Expr whenZero, Expr whenUnit) implements Expr {
        public IfZero {
            Objects.requireNonNull(condition);
            Objects.requireNonNull(whenZero);
            Objects.requireNonNull(whenUnit);
        }
    }

    public record Request(int prime, List<BigInteger> modulus, Expr expression) {
        public Request { modulus = List.copyOf(modulus); Objects.requireNonNull(expression); }
    }

    /** One witness per reached inverse or conditional, in strict left-to-right evaluation order. */
    public sealed interface Decision permits Zero, Unit {}
    public record Zero() implements Decision {}
    public record Unit(List<BigInteger> inverse) implements Decision {
        public Unit { inverse = List.copyOf(inverse); }
    }

    /** An absent value means that evaluation reached an inverse of zero on this entire component. */
    public record Branch(List<BigInteger> factor, Optional<List<BigInteger>> value, List<Decision> decisions) {
        public Branch {
            factor = List.copyOf(factor);
            value = value.map(List::copyOf);
            decisions = List.copyOf(decisions);
        }
    }
    public record Certificate(Request source, List<Branch> branches) {
        public Certificate { Objects.requireNonNull(source); branches = List.copyOf(branches); }
    }

    public enum Status { VERIFIED, REJECTED, INCONCLUSIVE }
    public record Result<T>(Status status, String reason, Optional<T> value, long work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(value);
            if ((status == Status.VERIFIED) != value.isPresent() || work < 0) {
                throw new IllegalArgumentException("INVALID_DYNAMIC_RESULT");
            }
        }
    }

    public static Result<Certificate> evaluate(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        return bounded(limits, cancellation, work -> {
            var domain = new DynamicAlgebraDomain(request, work);
            var certificate = new DynamicAlgebraBranches(domain).build(request);
            DynamicAlgebraVerifier.check(request, certificate, domain);
            return certificate;
        });
    }

    static <T> Result<T> bounded(IterationLimits limits, BooleanSupplier cancellation,
                                 Function<CertificateWork, T> body) {
        var work = new CertificateWork(limits, cancellation);
        try {
            work.charge(0);
            var result = body.apply(work);
            work.charge(0);
            return new Result<>(Status.VERIFIED, "VERIFIED", Optional.of(result), work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result<>(Status.INCONCLUSIVE, stop.getMessage(), Optional.empty(), work.used());
        } catch (Invalid invalid) {
            return new Result<>(Status.REJECTED, invalid.getMessage(), Optional.empty(), work.used());
        }
    }

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Invalid(String reason) { super(reason, null, false, false); }
    }
}
