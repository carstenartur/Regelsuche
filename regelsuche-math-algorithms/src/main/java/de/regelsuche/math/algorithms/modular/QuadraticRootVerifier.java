package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import static de.regelsuche.math.algorithms.modular.QuadraticRootMachine.*;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Arm;

/** Universal proof by exact Laurent arithmetic in all four independent character lanes. */
public final class QuadraticRootVerifier {
    private QuadraticRootVerifier() {}
    public record Result(boolean valid, String reason, long work) {}

    public static Result verify(QuadraticRootContract expected, QuadraticRootSearch.Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            checkField(expected, work, cancellation);
            verifyProgram(expected, certificate, work, cancellation);
            work.charge(0);
            return new Result(true, "VERIFIED", work.used());
        } catch (CertificateWork.Stop | IllegalArgumentException ex) {
            return new Result(false, ex.getMessage(), work.used());
        }
    }

    static void checkField(QuadraticRootContract expected, CertificateWork work, BooleanSupplier cancellation) {
        work.charge(1);
        if (expected == null || expected.modulus().signum() <= 0 || (expected.modulus().intValue() & 3) != 3) {
            throw new IllegalArgumentException("PRIME_THREE_MOD_FOUR_REQUIRED");
        }
        var prime = PrimeEvidenceVerifier.verify(expected.modulus(), expected.primeEvidence(), work.remainingLimits(), cancellation);
        work.chargeCompleted(prime.work());
        if (!prime.valid()) throw new IllegalArgumentException(prime.reason());
        validate(expected.original(), expected.modulus(), work);
    }

    static void verifyProgram(QuadraticRootContract expected, QuadraticRootSearch.Certificate certificate, CertificateWork work,
            BooleanSupplier cancellation) {
        if (certificate == null) throw new IllegalArgumentException("MISSING_CERTIFICATE");
        var bound = certificate.contract();
        if (!bound.sourceIdentity().equals(expected.sourceIdentity()) || !bound.modulus().equals(expected.modulus())
                || bound.observation() != expected.observation() || !bound.original().equals(expected.original())) {
            throw new IllegalArgumentException("SOURCE_MISMATCH");
        }
        if (bound.primeEvidence() != expected.primeEvidence()) {
            // Bound the depth and authenticate both trees before any structural record comparison.
            checkField(bound, work, cancellation);
            if (!bound.primeEvidence().equals(expected.primeEvidence())) throw new IllegalArgumentException("SOURCE_MISMATCH");
        }
        var program = certificate.program();
        validate(program, expected.modulus(), work);
        var basis = new QuadraticRootBasis(expected, work);
        var nested = SharedPowerVerifier.verify(basis.request(), certificate.power(), new SharedPowerPlan.Weights(1, 1, 1), work,
                basis.retainedCells());
        if (!nested.valid()) throw new IllegalArgumentException(nested.reason());
        var template = basis.instantiate(certificate.power().schedule());
        if (!template.prefix().equals(program.prefix()) || template.guardLeft() != program.guardLeft()
                || template.guardRight() != program.guardRight()) throw new IllegalArgumentException("POWER_BINDING_MISMATCH");
        var lanes = lanes(expected, program, work);
        if (!matches(lanes, true, program.equal(), expected.observation(), work)
                || !matches(lanes, false, program.unequal(), expected.observation(), work)) {
            throw new IllegalArgumentException("COORDINATE_MISMATCH");
        }
    }

    record Lane(QuadraticLaurent field, Pair<QuadraticLaurent.Form> original, Prefix<QuadraticLaurent.Form> candidate) {}

    static List<Lane> lanes(QuadraticRootContract expected, QuadraticRootProgram candidate, CertificateWork work) {
        var lanes = new ArrayList<Lane>();
        long retained = 0;
        for (int u : new int[]{1, -1}) for (int s : new int[]{1, -1}) {
            var field = new QuadraticLaurent(expected.modulus(), u, s, work);
            field.retain(retained);
            // H=b/2 and X=(a+g)/2 are units: X*(a-g)/2=-H². Conversely every unit pair gives a valid input.
            var x = field.term(BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE);
            var h = field.term(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
            var h2OverX = field.term(BigInteger.ONE, BigInteger.TWO, BigInteger.ONE.negate());
            var a = field.add(x, field.negate(h2OverX));
            var b = field.add(h, h);
            var g = field.add(x, h2OverX);
            var inputs = List.of(a, b, g);
            var prefix = prefix(expected.original(), inputs, field, work);
            var arm = prefix.equal() ? expected.original().equal() : expected.original().unequal();
            var old = exit(arm, prefix, field, work);
            var realSquare = field.multiply(old.real(), old.real());
            var imaginarySquare = field.multiply(old.imaginary(), old.imaginary());
            var product = field.multiply(old.real(), old.imaginary());
            if (!a.equals(field.add(realSquare, field.negate(imaginarySquare))) || !b.equals(field.add(product, product))) {
                throw new IllegalArgumentException("SOURCE_IS_NOT_A_ROOT");
            }
            retained += field.cells(old.real()) + field.cells(old.imaginary());
            field.retain(retained);
            var next = prefix(candidate, inputs, field, work);
            for (var register : next.registers()) retained += field.cells(register);
            work.cells(retained);
            lanes.add(new Lane(field, old, next));
        }
        for (var lane : lanes) {
            // The machine charges the active prefix itself. The other prefixes and all original exit pairs stay live.
            long ownPrefix = lane.candidate().registers().stream().mapToLong(lane.field()::cells).sum();
            lane.field().retain(retained - ownPrefix);
        }
        return List.copyOf(lanes);
    }

    static boolean matches(List<Lane> lanes, boolean equal, Arm candidate, QuadraticRootContract.Observation observation,
            CertificateWork work) {
        for (var lane : lanes) {
            if (lane.candidate().equal() != equal) continue;
            var field = lane.field();
            var actual = exit(candidate, lane.candidate(), field, work);
            if (actual.equals(lane.original())) continue;
            if (observation == QuadraticRootContract.Observation.EXACT_PAIR
                    || !field.negate(actual.real()).equals(lane.original().real())
                    || !field.negate(actual.imaginary()).equals(lane.original().imaginary())) return false;
        }
        return true;
    }
}
