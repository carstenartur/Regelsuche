package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.modular.SharedPowerPlan.*;

/** Checked modular execution, returning raw power-equivalent values without canonicalization. */
public final class SharedPowerEvaluation {
    private SharedPowerEvaluation() {}

    public record Result(Optional<List<BigInteger>> values, String reason, long work) {
        public Result { values = values.map(List::copyOf); }
    }

    /**
     * Checks the expected source, certificate and x^n=1 mod m before execution.
     * m may be composite: equal squares then need not mean equality up to sign.
     * Verification and the group-assumption exponentiation are included in work.
     */
    public static Result evaluate(Request expected, Certificate certificate, BigInteger modulus,
            BigInteger base, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(certificate);
        bounded(modulus);
        bounded(base);
        if (modulus.compareTo(BigInteger.TWO) < 0) throw new IllegalArgumentException("INVALID_MODULUS");
        var work = new CertificateWork(limits, cancellation);
        try {
            var verification = SharedPowerVerifier.verify(expected, certificate, new Weights(1, 1, 1), work, 0);
            if (!verification.valid()) return new Result(Optional.empty(), verification.reason(), work.used());
            work.check(modulus);
            work.check(base);
            var x = base.mod(modulus);
            chargePower(expected.orderMultiple(), modulus, work);
            if (!x.modPow(expected.orderMultiple(), modulus).equals(BigInteger.ONE)) {
                return new Result(Optional.empty(), "GROUP_ASSUMPTION_FAILED", work.used());
            }
            var schedule = certificate.schedule();
            work.cells(3L * (schedule.steps().size() + 2) + 4L * expected.outputs().size());
            var values = new ArrayList<BigInteger>();
            values.add(BigInteger.ONE);
            values.add(x);
            for (var step : schedule.steps()) {
                BigInteger value;
                switch (step.operation()) {
                    case POWER -> {
                        chargePower(step.exponent(), modulus, work);
                        value = x.modPow(step.exponent(), modulus);
                    }
                    case MULTIPLY -> {
                        work.charge(1L + SharedPowerVerifier.words(modulus) * SharedPowerVerifier.words(modulus));
                        value = values.get(step.left()).multiply(values.get(step.right())).mod(modulus);
                    }
                    case INVERSE -> {
                        // Conservative word-work charge for a bounded Euclidean inverse, separate from plan weights.
                        chargePower(modulus, modulus, work);
                        value = values.get(step.left()).modInverse(modulus);
                    }
                    default -> throw new IllegalStateException("UNKNOWN_OPERATION");
                }
                work.check(value);
                values.add(value);
            }
            var outputs = new ArrayList<BigInteger>();
            for (int output : schedule.outputs()) {
                work.charge(1);
                outputs.add(values.get(output));
            }
            work.charge(1L + outputs.size());
            return new Result(Optional.of(outputs), "VERIFIED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result(Optional.empty(), stop.getMessage(), work.used());
        }
    }

    private static void chargePower(BigInteger exponent, BigInteger modulus, CertificateWork work) {
        work.charge(1L + (1L + 2L * exponent.bitLength())
                * SharedPowerVerifier.words(modulus) * SharedPowerVerifier.words(modulus));
    }
}
