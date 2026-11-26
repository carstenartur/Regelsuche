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

/** Symbolic exponent replay, independent of the proposal search and modular evaluator. */
public final class SharedPowerVerifier {
    private SharedPowerVerifier() {}

    public record Verification(boolean valid, String reason, Optional<Cost> cost,
            List<BigInteger> outputExponents, long work) {
        public Verification { outputExponents = List.copyOf(outputExponents); }
    }

    /** Proves every advertised observation for all group elements satisfying x^n=1. */
    public static Verification verify(Request expected, Certificate certificate, Weights weights,
            IterationLimits limits, BooleanSupplier cancellation) {
        return verify(expected, certificate, weights, new CertificateWork(limits, cancellation), 0);
    }

    static Verification verify(Request expected, Certificate certificate, Weights weights,
            CertificateWork work, long retainedCells) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(certificate);
        Objects.requireNonNull(weights);
        long start = work.used();
        try {
            inspect(expected, work);
            inspect(certificate.source(), work);
            work.charge(1L + expected.outputs().size() + certificate.source().outputs().size());
            if (!expected.equals(certificate.source())) return failure("SOURCE_MISMATCH", work, start);
            var schedule = certificate.schedule();
            work.cells(retainedCells + 3L * (schedule.steps().size() + 2) + 3L * expected.outputs().size());
            work.charge(1L + schedule.steps().size() + schedule.outputs().size());
            if (schedule.outputs().size() != expected.outputs().size()) {
                return failure("OUTPUT_COUNT_MISMATCH", work, start);
            }
            var n = expected.orderMultiple();
            var exponents = new ArrayList<BigInteger>();
            exponents.add(BigInteger.ZERO);
            exponents.add(BigInteger.ONE.mod(n));
            int powers = 0, multiplications = 0, squarings = 0, inversions = 0;
            int powerMultiplications = 0, powerSquarings = 0;
            for (var step : schedule.steps()) {
                work.charge(1);
                if (step.left() < 0 || step.left() >= exponents.size()
                        || step.right() < 0 || step.right() >= exponents.size()) {
                    return failure("INVALID_REFERENCE", work, start);
                }
                BigInteger exponent;
                switch (step.operation()) {
                    case POWER -> {
                        work.check(step.exponent());
                        exponent = step.exponent().mod(n);
                        powers++;
                        powerSquarings += Math.max(0, step.exponent().bitLength() - 1);
                        powerMultiplications += Math.max(0, step.exponent().bitCount() - 1);
                    }
                    case MULTIPLY -> {
                        exponent = exponents.get(step.left()).add(exponents.get(step.right())).mod(n);
                        if (step.left() == step.right()) squarings++;
                        else multiplications++;
                    }
                    case INVERSE -> {
                        exponent = exponents.get(step.left()).negate().mod(n);
                        inversions++;
                    }
                    default -> throw new IllegalStateException("UNKNOWN_OPERATION");
                }
                work.check(exponent);
                exponents.add(exponent);
            }
            var observed = new ArrayList<BigInteger>();
            for (int i = 0; i < schedule.outputs().size(); i++) {
                work.charge(1);
                int ref = schedule.outputs().get(i);
                if (ref < 0 || ref >= exponents.size()) return failure("INVALID_REFERENCE", work, start);
                var output = expected.outputs().get(i);
                var value = exponents.get(ref);
                var difference = value.subtract(output.exponent());
                work.check(difference);
                work.charge(1L + words(difference) * words(output.observationPower()));
                if (!difference.multiply(output.observationPower()).mod(n).equals(BigInteger.ZERO)) {
                    return failure("OBSERVATION_MISMATCH", work, start);
                }
                observed.add(value);
            }
            long cost = weights.multiplication() * (multiplications + powerMultiplications)
                    + weights.squaring() * (squarings + powerSquarings)
                    + weights.inverse() * inversions;
            work.charge(1L + observed.size());
            return new Verification(true, "VERIFIED", Optional.of(new Cost(powers, multiplications,
                    squarings, inversions, powerMultiplications, powerSquarings, cost)), observed, work.used() - start);
        } catch (CertificateWork.Stop stop) {
            return failure(stop.getMessage(), work, start);
        }
    }

    static void inspect(Request request, CertificateWork work) {
        work.cells(1L + 3L * request.outputs().size());
        work.check(request.orderMultiple());
        for (var output : request.outputs()) {
            work.check(output.exponent());
            work.check(output.observationPower());
        }
    }

    static long words(BigInteger value) { return 1L + value.abs().bitLength() / Long.SIZE; }

    private static Verification failure(String reason, CertificateWork work, long start) {
        return new Verification(false, reason, Optional.empty(), List.of(), work.used() - start);
    }
}
