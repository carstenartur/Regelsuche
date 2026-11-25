package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.scalar.ExactRational.ONE;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Certificate;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Unbounded;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Decides a bounded affine implication by finding and checking exact LP evidence. */
public final class AffineImplication {
    private AffineImplication() {}

    /** Asks whether the original rows Ax <= b imply c^T x <= upperBound. */
    public record Request(Problem problem, ExactRational upperBound) {
        public Request {
            Objects.requireNonNull(problem);
            Objects.requireNonNull(upperBound);
        }
    }

    public enum Status { PROVED, REFUTED, UNAVAILABLE }

    public sealed interface Evidence permits Proof, Counterexample {
        Request source();
    }

    /** A checked infeasibility certificate or an optimum no larger than the requested bound. */
    public record Proof(Request source, Certificate certificate) implements Evidence {
        public Proof {
            Objects.requireNonNull(source);
            Objects.requireNonNull(certificate);
        }
    }

    /** A concrete point satisfying every premise and strictly violating the conclusion. */
    public record Counterexample(Request source, List<ExactRational> x) implements Evidence {
        public Counterexample {
            Objects.requireNonNull(source);
            x = List.copyOf(x);
        }
    }

    public record Result(Status status, String reason, long work, Optional<Evidence> evidence) {}

    public static Result search(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        var work = new CertificateWork(limits, cancellation);
        try {
            work.cells(request.problem().storageCells() + 1);
            work.check(request.upperBound());
            var evidence = propose(request, work);
            var checked = AffineImplicationVerifier.verify(request, evidence, work);
            if (!checked.valid()) throw new CertificateWork.Stop("OUTPUT_" + checked.reason());
            work.charge(0);
            var status = evidence instanceof Proof ? Status.PROVED : Status.REFUTED;
            return new Result(status, status.name(), work.used(), Optional.of(evidence));
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, stop.getMessage(), work.used(), Optional.empty());
        }
    }

    private static Evidence propose(Request request, CertificateWork work) {
        var certificate = LinearProgramSearch.search(request.problem(), 1, work);
        return switch (certificate) {
            case Infeasible ignored -> new Proof(request, certificate);
            case Optimal optimum -> {
                var value = LinearProgramVerifier.dot(request.problem().c(), optimum.x(), work);
                yield work.sub(value, request.upperBound()).signum() <= 0
                    ? new Proof(request, optimum) : new Counterexample(request, optimum.x());
            }
            case Unbounded unbounded -> counterexample(request, unbounded, work);
        };
    }

    private static Counterexample counterexample(Request request, Unbounded ray, CertificateWork work) {
        var problem = request.problem();
        var value = LinearProgramVerifier.dot(problem.c(), ray.x(), work);
        var missing = work.sub(request.upperBound(), value);
        if (missing.signum() < 0) return new Counterexample(request, ray.x());
        // The checked ray has unit objective gain. This step strictly crosses the bound.
        var step = work.add(missing, ONE);
        int variables = problem.variables();
        work.cells(problem.storageCells() + 1 + 4L * variables + 16);
        work.charge(2L * variables);
        var point = new ExactRational[variables];
        for (int column = 0; column < variables; column++) {
            point[column] = work.add(ray.x().get(column), work.mul(step, ray.v().get(column)));
        }
        return new Counterexample(request, Arrays.asList(point));
    }
}
