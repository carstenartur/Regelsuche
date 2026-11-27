package de.regelsuche.math.algorithms.combinatorics;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramSearch;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Exact, independently checkable upper bounds for bipartite matchings with incompatible edges. */
public final class ConflictMatching {
    private ConflictMatching() {}

    /** Indices into the canonical {@link BipartiteMatching.Request#edges()} list. */
    public record Conflict(int first, int second) {}

    /**
     * Immutable graph and unordered pairs of edges that cannot both be selected. Canonicalization
     * occurs on construction, outside the search/verification budget, as for the underlying graph.
     * A domain-specific consumer must justify every edge exclusion and conflict it supplies.
     */
    public record Request(BipartiteMatching.Request graph, List<Conflict> conflicts) {
        public Request {
            Objects.requireNonNull(graph);
            var canonical = new ArrayList<Conflict>();
            for (var conflict : conflicts) {
                Objects.requireNonNull(conflict);
                int first = Math.min(conflict.first(), conflict.second());
                int second = Math.max(conflict.first(), conflict.second());
                if (first < 0 || first == second || second >= graph.edges().size()) {
                    throw new IllegalArgumentException("INVALID_EDGE_CONFLICT");
                }
                canonical.add(new Conflict(first, second));
            }
            canonical.sort(Comparator.comparingInt(Conflict::first).thenComparingInt(Conflict::second));
            int unique = 0;
            for (var conflict : canonical) {
                if (unique == 0 || !conflict.equals(canonical.get(unique - 1))) {
                    canonical.set(unique++, conflict);
                }
            }
            conflicts = List.copyOf(canonical.subList(0, unique));
        }
    }

    /**
     * Nonnegative weights on left capacities, right capacities and conflict capacities. Each
     * edge must receive total incident weight at least one. Externally supplied evidence is
     * untrusted until {@link ConflictMatchingVerifier#verify} accepts it.
     */
    public record Certificate(Request source, List<ExactRational> leftWeights,
            List<ExactRational> rightWeights, List<ExactRational> conflictWeights) {
        public Certificate {
            Objects.requireNonNull(source);
            leftWeights = List.copyOf(leftWeights);
            rightWeights = List.copyOf(rightWeights);
            conflictWeights = List.copyOf(conflictWeights);
        }
    }

    /** A relaxation bound, not a claim of an exact maximum compatible matching. */
    public record Bound(ExactRational fractionalUpperBound, int matchingSizeUpperBound,
            int guaranteedUnmatchedLeft) {}

    public record Result(Status status, String reason, long work,
            Optional<Certificate> certificate, Optional<Bound> bound) {}

    /**
     * Maximizes the fractional matching relaxation using the existing exact LP producer, then
     * independently checks a small weighted-cover certificate against the original graph.
     * All phases share one work allowance and deadline. Logical retained cells, including the
     * dense LP and its tableau, are bounded before allocation. Exhaustion publishes no bound.
     */
    public static Result search(Request source, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var work = new CertificateWork(limits, cancellation);
        try {
            var certificate = produce(source, work, cancellation);
            // The LP and its solution are no longer retained; the checker accounts for source/proof.
            var verified = ConflictMatchingVerifier.verify(source, certificate,
                work.remainingLimits(), cancellation);
            work.chargeCompleted(verified.work());
            work.charge(0);
            if (!verified.valid()) throw new CertificateWork.Stop("OUTPUT_" + verified.reason());
            return new Result(Status.VERIFIED, "VERIFIED", work.used(),
                Optional.of(certificate), verified.bound());
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, stop.getMessage(), work.used(),
                Optional.empty(), Optional.empty());
        }
    }

    private static Certificate produce(Request source, CertificateWork work, BooleanSupplier cancellation) {
        work.charge(1);
        long sourceCells = sourceCells(source);
        work.cells(sourceCells);
        int left = source.graph().leftCount();
        int right = source.graph().rightCount();
        int conflicts = source.conflicts().size();
        long weights = (long) left + right + conflicts;
        if (source.graph().edges().isEmpty()) {
            work.cells(sourceCells + 2 * weights + 16);
            var leftWeights = vector(left, ZERO, work);
            var rightWeights = vector(right, ZERO, work);
            var conflictWeights = vector(conflicts, ZERO, work);
            work.charge(weights);
            var certificate = new Certificate(source, leftWeights, rightWeights, conflictWeights);
            work.charge(0);
            return certificate;
        }
        var problem = relaxation(source, work);
        // The nested LP search accounts for the problem itself; reserve the original graph as well.
        var solved = LinearProgramSearch.search(problem, work.remainingLimits(sourceCells), cancellation);
        work.chargeCompleted(solved.work());
        work.charge(0);
        if (solved.status() != Status.VERIFIED) throw new CertificateWork.Stop("LP_" + solved.reason());
        if (!(solved.certificate().orElseThrow() instanceof Optimal optimal)) {
            throw new CertificateWork.Stop("UNEXPECTED_LP_CLASSIFICATION");
        }
        long problemCells = (long) problem.constraints() * (problem.variables() + 2L) + problem.variables();
        work.cells(sourceCells + problemCells + optimal.x().size() + optimal.y().size() + 2 * weights + 16);
        var leftWeights = copy(optimal.y(), 0, left, work);
        var rightWeights = copy(optimal.y(), left, right, work);
        var conflictWeights = copy(optimal.y(), left + right, conflicts, work);
        work.charge(weights);
        var certificate = new Certificate(source, leftWeights, rightWeights, conflictWeights);
        work.charge(0);
        return certificate;
    }

    private static Problem relaxation(Request source, CertificateWork work) {
        int left = source.graph().leftCount();
        int right = source.graph().rightCount();
        int conflicts = source.conflicts().size();
        int edges = source.graph().edges().size();
        // produce() has bounded all dimensions before these int sums or dense allocations.
        int capacities = left + right + conflicts;
        int rows = capacities + edges;
        long problemCells = (long) rows * (edges + 2L) + edges;
        work.cells(sourceCells(source) + 2 * problemCells + 16);
        var matrix = new ArrayList<List<ExactRational>>(rows);
        var bounds = new ArrayList<ExactRational>(rows);
        for (int row = 0; row < rows; row++) {
            work.charge(1);
            matrix.add(vector(edges, ZERO, work));
            bounds.add(row < capacities ? ONE : ZERO);
        }
        for (int index = 0; index < edges; index++) {
            work.charge(3);
            var edge = source.graph().edges().get(index);
            matrix.get(edge.left()).set(index, ONE);
            matrix.get(left + edge.right()).set(index, ONE);
            matrix.get(capacities + index).set(index, NEGATIVE_ONE);
        }
        for (int index = 0; index < conflicts; index++) {
            work.charge(2);
            var conflict = source.conflicts().get(index);
            var row = matrix.get(left + right + index);
            row.set(conflict.first(), ONE);
            row.set(conflict.second(), ONE);
        }
        var objective = vector(edges, ONE, work);
        work.charge(problemCells); // Account for the immutable Problem's defensive copies.
        var problem = new Problem(matrix, bounds, objective);
        work.charge(0);
        return problem;
    }

    private static List<ExactRational> vector(int size, ExactRational value, CertificateWork work) {
        var result = new ArrayList<ExactRational>(size);
        for (int index = 0; index < size; index++) {
            work.charge(1);
            result.add(value);
        }
        return result;
    }

    private static List<ExactRational> copy(List<ExactRational> values, int start, int size,
            CertificateWork work) {
        var result = new ArrayList<ExactRational>(size);
        for (int index = 0; index < size; index++) {
            result.add(work.check(values.get(start + index)));
        }
        return result;
    }

    static long sourceCells(Request source) {
        return 6L + source.graph().leftCount() + source.graph().rightCount()
            + 2L * source.graph().edges().size() + 2L * source.conflicts().size();
    }
}
