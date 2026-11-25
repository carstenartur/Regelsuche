package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Counterexample;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Evidence;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Proof;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Request;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Unbounded;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Verification;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Independent source-bound replay of affine implication evidence. */
public final class AffineImplicationVerifier {
    private AffineImplicationVerifier() {}

    public static Verification verify(Request request, Evidence evidence,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            return verify(request, evidence, work);
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used());
        }
    }

    static Verification verify(Request request, Evidence evidence, CertificateWork work) {
        Objects.requireNonNull(request);
        work.charge(1);
        if (evidence == null) return result("MISSING_EVIDENCE", work);
        var problem = request.problem();
        var bound = evidence.source();
        if (problem.constraints() != bound.problem().constraints()
                || problem.variables() != bound.problem().variables()) {
            return result("SOURCE_MISMATCH", work);
        }
        long boundCells = request == bound ? 0
            : 1 + (problem == bound.problem() ? 0 : bound.problem().storageCells());
        work.cells(boundCells);
        long evidenceCells = switch (evidence) {
            case Proof proof -> LinearProgramVerifier.storageCells(problem, proof.certificate(), work);
            case Counterexample point -> problem.storageCells() + point.x().size();
        };
        work.cells(1 + boundCells + evidenceCells);
        var upperBound = work.check(request.upperBound());
        if (request != bound && !upperBound.equals(work.check(bound.upperBound()))) {
            return result("SOURCE_MISMATCH", work);
        }
        if (!LinearProgramVerifier.sameSource(problem, bound.problem(), work)) {
            return result("SOURCE_MISMATCH", work);
        }
        return switch (evidence) {
            case Proof proof -> checkProof(request, proof, 1 + boundCells, work);
            case Counterexample point -> checkCounterexample(request, point, work);
        };
    }

    private static Verification checkProof(Request request, Proof proof,
            long retainedCells, CertificateWork work) {
        if (proof.certificate() instanceof Unbounded) return result("NOT_AN_IMPLICATION_PROOF", work);
        var checked = LinearProgramVerifier.verify(request.problem(), proof.certificate(),
            false, retainedCells, work);
        if (!checked.valid()) return checked;
        if (proof.certificate() instanceof Optimal optimum) {
            var value = LinearProgramVerifier.dot(request.problem().c(), optimum.x(), work);
            if (work.sub(value, request.upperBound()).signum() > 0) {
                return result("CONCLUSION_NOT_ENTAILED", work);
            }
        }
        return result(null, work);
    }

    private static Verification checkCounterexample(Request request, Counterexample point, CertificateWork work) {
        var problem = request.problem();
        if (point.x().size() != problem.variables()) return result("COUNTEREXAMPLE_SHAPE", work);
        for (var value : point.x()) work.check(value);
        for (int row = 0; row < problem.constraints(); row++) {
            var value = LinearProgramVerifier.dot(problem.a().get(row), point.x(), work);
            if (work.sub(value, problem.b().get(row)).signum() > 0) {
                return result("COUNTEREXAMPLE_PREMISES", work);
            }
        }
        var conclusion = LinearProgramVerifier.dot(problem.c(), point.x(), work);
        return result(work.sub(conclusion, request.upperBound()).signum() > 0
            ? null : "COUNTEREXAMPLE_CONCLUSION", work);
    }

    private static Verification result(String rejection, CertificateWork work) {
        work.charge(0);
        return rejection == null ? new Verification(Status.VERIFIED, "VERIFIED", work.used())
            : new Verification(Status.INVALID, rejection, work.used());
    }
}
