package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Certificate;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Unbounded;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Checks supplied evidence by exact arithmetic on the original linear program. */
public final class LinearProgramVerifier {
    private LinearProgramVerifier() {}

    public enum Status { VERIFIED, INVALID, UNAVAILABLE }

    public record Verification(Status status, String reason, long work) {
        public boolean valid() { return status == Status.VERIFIED; }
    }

    public static Verification verify(Problem problem, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            return verify(problem, certificate, false, 0, work);
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used());
        }
    }

    static Verification verify(Problem problem, Certificate certificate, boolean normalized,
            long retainedCells, CertificateWork work) {
        Objects.requireNonNull(problem);
        work.charge(1);
        if (certificate == null) return result("MISSING_CERTIFICATE", work);
        if (problem.constraints() != certificate.source().constraints()
                || problem.variables() != certificate.source().variables()) {
            return result("SOURCE_MISMATCH", work);
        }
        if (!compatible(problem, certificate)) return result("CERTIFICATE_SHAPE", work);
        work.cells(retainedCells + storageCells(problem, certificate, work));
        if (!sameSource(problem, certificate.source(), work)) return result("SOURCE_MISMATCH", work);
        inspectValues(certificate, work);
        return result(switch (certificate) {
            case Infeasible proof -> checkInfeasible(problem, proof, normalized, work);
            case Optimal proof -> checkOptimal(problem, proof, work);
            case Unbounded proof -> checkUnbounded(problem, proof, work);
        }, work);
    }

    private static boolean compatible(Problem problem, Certificate certificate) {
        return switch (certificate) {
            case Infeasible proof -> proof.y().size() == problem.constraints();
            case Optimal proof -> proof.x().size() == problem.variables()
                && proof.y().size() == problem.constraints();
            case Unbounded proof -> proof.x().size() == problem.variables()
                && proof.v().size() == problem.variables();
        };
    }

    static long storageCells(Problem problem, Certificate certificate, CertificateWork work) {
        long source = problem.storageCells();
        long bound = problem == certificate.source() ? 0 : certificate.source().storageCells();
        work.cells(source);
        work.cells(bound);
        return source + bound + switch (certificate) {
            case Infeasible proof -> proof.y().size();
            case Optimal proof -> proof.x().size() + (long) proof.y().size();
            case Unbounded proof -> proof.x().size() + (long) proof.v().size();
        };
    }

    private static boolean sameSource(Problem request, Problem bound, CertificateWork work) {
        for (int row = 0; row < request.constraints(); row++) {
            work.charge(1);
            if (!sameValues(request.a().get(row), bound.a().get(row), work)) return false;
        }
        return sameValues(request.b(), bound.b(), work) && sameValues(request.c(), bound.c(), work);
    }

    private static boolean sameValues(List<ExactRational> first, List<ExactRational> second, CertificateWork work) {
        for (int index = 0; index < first.size(); index++) {
            var value = work.check(first.get(index));
            if (first != second && !value.equals(work.check(second.get(index)))) return false;
        }
        return true;
    }

    private static void inspectValues(Certificate certificate, CertificateWork work) {
        switch (certificate) {
            case Infeasible proof -> inspectVector(proof.y(), work);
            case Optimal proof -> {
                inspectVector(proof.x(), work);
                inspectVector(proof.y(), work);
            }
            case Unbounded proof -> {
                inspectVector(proof.x(), work);
                inspectVector(proof.v(), work);
            }
        }
    }

    private static void inspectVector(List<ExactRational> vector, CertificateWork work) {
        for (ExactRational value : vector) work.check(value);
    }

    private static String checkInfeasible(Problem problem, Infeasible proof, boolean normalized,
            CertificateWork work) {
        if (!matchesDual(problem, proof.y(), false, work)) return "FARKAS_MULTIPLIER";
        var contradiction = dot(problem.b(), proof.y(), work);
        return (normalized ? contradiction.equals(NEGATIVE_ONE) : contradiction.signum() < 0)
            ? null : "FARKAS_CONTRADICTION";
    }

    private static String checkOptimal(Problem problem, Optimal proof, CertificateWork work) {
        if (!withinRows(problem, proof.x(), false, work)) return "PRIMAL_INFEASIBLE";
        if (!matchesDual(problem, proof.y(), true, work)) return "DUAL_INFEASIBLE";
        return dot(problem.c(), proof.x(), work).equals(dot(problem.b(), proof.y(), work))
            ? null : "DUALITY_GAP";
    }

    private static String checkUnbounded(Problem problem, Unbounded proof, CertificateWork work) {
        if (!withinRows(problem, proof.x(), false, work)) return "PRIMAL_INFEASIBLE";
        if (!withinRows(problem, proof.v(), true, work)) return "RAY_DIRECTION";
        return dot(problem.c(), proof.v(), work).equals(ONE) ? null : "RAY_OBJECTIVE_GAIN";
    }

    private static boolean withinRows(Problem problem, List<ExactRational> vector, boolean homogeneous,
            CertificateWork work) {
        for (int row = 0; row < problem.constraints(); row++) {
            var bound = homogeneous ? ZERO : problem.b().get(row);
            if (work.sub(dot(problem.a().get(row), vector, work), bound).signum() > 0) return false;
        }
        return true;
    }

    private static boolean matchesDual(Problem problem, List<ExactRational> y, boolean optimality,
            CertificateWork work) {
        for (ExactRational weight : y) {
            work.charge(1);
            if (weight.signum() < 0) return false;
        }
        for (int column = 0; column < problem.variables(); column++) {
            var sum = ZERO;
            for (int row = 0; row < problem.constraints(); row++) {
                sum = work.add(sum, work.mul(problem.a().get(row).get(column), y.get(row)));
            }
            if (!sum.equals(optimality ? problem.c().get(column) : ZERO)) return false;
        }
        return true;
    }

    static ExactRational dot(List<ExactRational> first, List<ExactRational> second, CertificateWork work) {
        var sum = ZERO;
        for (int index = 0; index < first.size(); index++) {
            sum = work.add(sum, work.mul(first.get(index), second.get(index)));
        }
        return sum;
    }

    private static Verification result(String rejection, CertificateWork work) {
        work.charge(0);
        return rejection == null ? new Verification(Status.VERIFIED, "VERIFIED", work.used())
            : new Verification(Status.INVALID, rejection, work.used());
    }
}
