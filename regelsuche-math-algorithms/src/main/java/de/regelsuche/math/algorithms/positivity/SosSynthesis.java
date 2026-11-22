package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Bounded rational Gram search for polynomial nonnegativity on a basic semialgebraic set. */
public final class SosSynthesis {
    private SosSynthesis() {}

    public enum Relation { NONNEGATIVE, ZERO }
    public enum Status { VERIFIED, INVALID_CERTIFICATE, INCONCLUSIVE }

    /** The basis describes an SOS multiplier for g >= 0 or a free polynomial multiplier for h = 0. */
    public record Premise(Relation relation, Polynomial polynomial, List<Monomial> basis) {
        public Premise {
            Objects.requireNonNull(relation);
            Objects.requireNonNull(polynomial);
            basis = checkedBasis(basis);
        }
    }

    /** The first basis is the unweighted Gram block. Free entries range over the supplied rational grid. */
    public record Template(List<Monomial> basis, List<Premise> premises, List<ExactRational> freeValues) {
        public Template {
            basis = checkedBasis(basis);
            premises = List.copyOf(premises);
            freeValues = checkedValues(freeValues);
        }
    }

    public record Request(Polynomial target, Template template) {
        public Request {
            Objects.requireNonNull(target);
            Objects.requireNonNull(template);
        }
    }
    public record Gram(List<List<ExactRational>> matrix) {
        public Gram { matrix = matrix.stream().map(List::copyOf).toList(); }
    }

    /** Unweighted Gram first, then the inequality Grams in premise order. Multipliers follow equality order. */
    public record Certificate(Request source, List<Gram> grams, List<List<ExactRational>> multipliers) {
        public Certificate {
            Objects.requireNonNull(source);
            grams = List.copyOf(grams);
            multipliers = multipliers.stream().map(List::copyOf).toList();
        }
    }
    public record Result(Status status, Optional<Certificate> certificate, String reason, long work) {}
    public record Verification(Status status, String reason, long work) {
        public boolean valid() { return status == Status.VERIFIED; }
    }

    public static Result search(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            var proof = find(request, work, cancellation);
            return new Result(proof.isPresent() ? Status.VERIFIED : Status.INCONCLUSIVE, proof,
                proof.isPresent() ? "VERIFIED" : "SEARCH_SPACE_EXHAUSTED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.INCONCLUSIVE, Optional.empty(), stop.getMessage(), work.used());
        }
    }

    static Optional<Certificate> find(Request request, CertificateWork work, BooleanSupplier cancellation) {
        SosPolynomials.validate(request, work);
        return SosGramSystem.search(request, work, cancellation);
    }

    static List<Monomial> checkedBasis(List<Monomial> source) {
        var result = List.copyOf(source);
        if (result.isEmpty() || new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("BASIS_MUST_BE_NONEMPTY_AND_DISTINCT");
        }
        return result;
    }

    static List<ExactRational> checkedValues(List<ExactRational> values) {
        var result = List.copyOf(values);
        if (result.isEmpty()) throw new IllegalArgumentException("EMPTY_COEFFICIENT_GRID");
        return result;
    }

    /** Advance a finite Cartesian product without recursion or precomputing its cardinality. */
    static boolean advance(int[] digits, int radix, CertificateWork work) {
        for (int i = digits.length-1; i >= 0; i--) {
            work.charge(1);
            if (++digits[i] < radix) return true;
            digits[i] = 0;
        }
        work.charge(0);
        return false;
    }
}
