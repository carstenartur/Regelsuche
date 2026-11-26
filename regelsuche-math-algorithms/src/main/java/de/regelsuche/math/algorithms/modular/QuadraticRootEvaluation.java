package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Concrete evaluator of a replayed certificate on the contract's canonical nonreal norm domain. */
public final class QuadraticRootEvaluation {
    private QuadraticRootEvaluation() {}
    public record Pair(BigInteger real, BigInteger imaginary) {}
    public record Result(Optional<Pair> pair, String reason, long work) {}

    public record Preparation(Optional<Prepared> plan, String reason, long work) {}

    /** Replay the source binding, prime evidence and program proof once, within a single allowance. */
    public static Preparation prepare(QuadraticRootContract expected, QuadraticRootSearch.Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        var checked = QuadraticRootVerifier.verify(expected, certificate, limits, cancellation);
        var plan = checked.valid() ? Optional.of(new Prepared(expected.modulus(), certificate.program()))
                : Optional.<Prepared>empty();
        return new Preparation(plan, checked.reason(), checked.work());
    }

    /** Immutable, source-verified execution plan. Each invocation has an independent budget and input guard. */
    public static final class Prepared {
        private final BigInteger modulus;
        private final QuadraticRootProgram program;

        private Prepared(BigInteger modulus, QuadraticRootProgram program) {
            this.modulus = modulus;
            this.program = program;
        }

        public Result evaluate(BigInteger a, BigInteger b, BigInteger g, IterationLimits limits,
                BooleanSupplier cancellation) {
            var work = new CertificateWork(limits, cancellation);
            try {
                return execute(modulus, program, a, b, g, work);
            } catch (CertificateWork.Stop ex) {
                return new Result(Optional.empty(), ex.getMessage(), work.used());
            }
        }
    }

    public static Result evaluate(QuadraticRootContract expected, QuadraticRootSearch.Certificate certificate,
            BigInteger a, BigInteger b, BigInteger g, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            QuadraticRootVerifier.checkField(expected, work, cancellation);
            QuadraticRootVerifier.verifyProgram(expected, certificate, work, cancellation);
            return execute(expected.modulus(), certificate.program(), a, b, g, work);
        } catch (CertificateWork.Stop | IllegalArgumentException ex) {
            return new Result(Optional.empty(), ex.getMessage(), work.used());
        }
    }

    static Result execute(QuadraticRootContract contract, QuadraticRootProgram program, BigInteger a, BigInteger b,
            BigInteger g, IterationLimits limits, BooleanSupplier cancellation) {
        return execute(contract.modulus(), program, a, b, g, new CertificateWork(limits, cancellation));
    }

    private static Result execute(BigInteger p, QuadraticRootProgram program, BigInteger a, BigInteger b,
            BigInteger g, CertificateWork work) {
        long words = SharedPowerVerifier.words(p);
        work.charge(1L + 4 * words * words);
        if (!QuadraticRootContract.accepts(p, a, b, g)) return new Result(Optional.empty(), "OUTSIDE_FIELD_DOMAIN", work.used());
        var field = new Concrete(p, work, words * words);
        var prefix = QuadraticRootMachine.prefix(program, List.of(a, b, g), field, work);
        var arm = prefix.equal() ? program.equal() : program.unequal();
        var pair = QuadraticRootMachine.exit(arm, prefix, field, work);
        work.charge(0);
        return new Result(Optional.of(new Pair(pair.real(), pair.imaginary())), "EVALUATED", work.used());
    }

    private record Concrete(BigInteger p, CertificateWork work, long wordCost) implements QuadraticRootMachine.Field<BigInteger> {
        @Override public BigInteger constant(BigInteger literal) { return literal; }
        @Override public BigInteger add(BigInteger a, BigInteger b) { work.charge(wordCost); return a.add(b).mod(p); }
        @Override public BigInteger negate(BigInteger a) { work.charge(wordCost); return a.negate().mod(p); }
        @Override public BigInteger multiply(BigInteger a, BigInteger b) { work.charge(wordCost); return a.multiply(b).mod(p); }
        @Override public BigInteger power(BigInteger a, BigInteger exponent) {
            work.charge(wordCost * (1L + exponent.abs().bitLength() + (exponent.signum() < 0 ? p.bitLength() : 0)));
            return a.modPow(exponent, p);
        }
        @Override public boolean equal(BigInteger a, BigInteger b) { return a.equals(b); }
        @Override public int character(BigInteger a) { return QuadraticCharacter.jacobi(a, p, work); }
        @Override public int cells(BigInteger a) { return 1; }
    }
}
