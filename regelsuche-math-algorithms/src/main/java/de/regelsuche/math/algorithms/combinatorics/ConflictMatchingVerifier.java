package de.regelsuche.math.algorithms.combinatorics;

import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching.Bound;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching.Certificate;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching.Request;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Direct weighted-cover replay. This checker neither constructs nor solves a linear program.
 * Summing edge cover inequalities over a compatible matching charges each vertex and each
 * conflict at most once, so the sum of all nonnegative weights bounds its cardinality.
 */
public final class ConflictMatchingVerifier {
    private ConflictMatchingVerifier() {}

    public record Verification(Status status, String reason, long work, Optional<Bound> bound) {
        public boolean valid() { return status == Status.VERIFIED; }
    }

    public static Verification verify(Request source, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var work = new CertificateWork(limits, cancellation);
        try {
            work.charge(1);
            if (certificate == null) return invalid("MISSING_CERTIFICATE", work);
            if (!sameShape(source, certificate.source())) return invalid("SOURCE_MISMATCH", work);
            if (certificate.leftWeights().size() != source.graph().leftCount()
                    || certificate.rightWeights().size() != source.graph().rightCount()
                    || certificate.conflictWeights().size() != source.conflicts().size()) {
                return invalid("CERTIFICATE_SHAPE", work);
            }
            long retained = ConflictMatching.sourceCells(source);
            if (source != certificate.source()) retained += ConflictMatching.sourceCells(certificate.source());
            retained += (long) certificate.leftWeights().size() + certificate.rightWeights().size()
                + certificate.conflictWeights().size() + source.graph().edges().size() + 16;
            work.cells(retained);
            if (!sameSource(source, certificate.source(), work)) return invalid("SOURCE_MISMATCH", work);
            ExactRational total = ZERO;
            for (var vector : List.of(certificate.leftWeights(), certificate.rightWeights(),
                    certificate.conflictWeights())) {
                for (var value : vector) {
                    work.charge(1);
                    // BigInteger is extensible: never invoke supplied numeric overrides in replay.
                    if (value.numerator().getClass() != BigInteger.class
                            || value.denominator().getClass() != BigInteger.class) {
                        return invalid("NON_CANONICAL_WEIGHT", work);
                    }
                    if (work.check(value).signum() < 0) return invalid("NEGATIVE_WEIGHT", work);
                    total = work.add(total, value);
                }
            }
            var coverage = new ExactRational[source.graph().edges().size()];
            for (int index = 0; index < coverage.length; index++) {
                work.charge(1);
                var edge = source.graph().edges().get(index);
                coverage[index] = work.add(certificate.leftWeights().get(edge.left()),
                    certificate.rightWeights().get(edge.right()));
            }
            for (int index = 0; index < source.conflicts().size(); index++) {
                work.charge(1);
                var conflict = source.conflicts().get(index);
                var weight = certificate.conflictWeights().get(index);
                coverage[conflict.first()] = work.add(coverage[conflict.first()], weight);
                coverage[conflict.second()] = work.add(coverage[conflict.second()], weight);
            }
            for (var value : coverage) {
                if (work.sub(value, ONE).signum() < 0) return invalid("UNCOVERED_EDGE", work);
            }
            work.charge(1);
            var floor = work.check(total.numerator().divide(total.denominator()));
            int vertexBound = Math.min(source.graph().leftCount(), source.graph().rightCount());
            int upper = floor.min(BigInteger.valueOf(vertexBound)).intValueExact();
            var bound = new Bound(total, upper, source.graph().leftCount() - upper);
            work.charge(0);
            return new Verification(Status.VERIFIED, "VERIFIED", work.used(), Optional.of(bound));
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used(), Optional.empty());
        }
    }

    private static boolean sameShape(Request first, Request second) {
        return first.graph().leftCount() == second.graph().leftCount()
            && first.graph().rightCount() == second.graph().rightCount()
            && first.graph().edges().size() == second.graph().edges().size()
            && first.conflicts().size() == second.conflicts().size();
    }

    private static boolean sameSource(Request first, Request second, CertificateWork work) {
        for (int index = 0; index < first.graph().edges().size(); index++) {
            work.charge(1);
            if (!first.graph().edges().get(index).equals(second.graph().edges().get(index))) return false;
        }
        for (int index = 0; index < first.conflicts().size(); index++) {
            work.charge(1);
            if (!first.conflicts().get(index).equals(second.conflicts().get(index))) return false;
        }
        return true;
    }

    private static Verification invalid(String reason, CertificateWork work) {
        work.charge(0);
        return new Verification(Status.INVALID, reason, work.used(), Optional.empty());
    }
}
