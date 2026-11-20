package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage.Ray;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage.Request;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage.Unavailable;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage.Verification;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage.Work;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static java.math.BigInteger.ZERO;

/** Hunter's spanning-tree lower bound on survivors of a finite activated-congruence request. */
public final class HunterCoverage {
    private HunterCoverage() {}

    /** Endpoints index original rule positions, including repeated and inactive rules. */
    public record Edge(int left, int right, BigInteger intersectionCount) {
        public Edge { Objects.requireNonNull(intersectionCount); }
    }

    /** An arbitrary spanning tree suffices for replay. Its maximum weight is not a certified claim. */
    public record Certificate(Request source, List<BigInteger> singletonCounts, List<Edge> tree,
            BigInteger lowerBound) {
        public Certificate {
            Objects.requireNonNull(source);
            singletonCounts = List.copyOf(singletonCounts);
            tree = List.copyOf(tree);
            Objects.requireNonNull(lowerBound);
        }
    }

    public record Result(Optional<Certificate> certificate, String reason, long work) {}

    /** Dense Prim search with exact pair counts and linear auxiliary storage in the rule count. */
    public static Result bounds(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        var work = new Work(limits, cancellation);
        try {
            int n = request.rules().size();
            work.terms(n);
            CongruenceCoverage.validateInput(request, work);
            var size = request.progression().count();
            var rays = rays(request, work);
            var singles = new ArrayList<BigInteger>(n);
            var bound = size;
            for (var ray : rays) {
                var count = count(ray, size, work);
                singles.add(count);
                bound = work.subtract(bound, count);
            }
            var tree = maximumTree(rays, size, work);
            for (var edge : tree) bound = work.add(bound, edge.intersectionCount());
            work.charge(1L + n + tree.size());
            var certificate = new Certificate(request, singles, tree, bound.max(ZERO));
            work.charge(1);
            return new Result(Optional.of(certificate), "CERTIFIED", work.used);
        } catch (Unavailable stopped) {
            return new Result(Optional.empty(), stopped.getMessage(), work.used);
        }
    }

    private static Ray[] rays(Request request, Work work) {
        int n = request.rules().size();
        work.charge(1L + n);
        var rays = new Ray[n];
        if (request.progression().count().signum() > 0) {
            for (int i = 0; i < n; i++) {
                rays[i] = CongruenceCoverage.indexRay(request.progression(), request.rules().get(i), work);
            }
        }
        return rays;
    }

    private static BigInteger count(Ray ray, BigInteger size, Work work) {
        return ray == null ? ZERO : CongruenceCoverage.rayCount(ray.first(), ray.period(), size, work);
    }

    private static BigInteger intersection(Ray left, Ray right, BigInteger size, Work work) {
        work.charge(1);
        return left == null || right == null ? ZERO : count(CongruenceCoverage.intersect(left, right, size, work), size, work);
    }

    private static List<Edge> maximumTree(Ray[] rays, BigInteger size, Work work) {
        int n = rays.length;
        if (n < 2) return List.of();
        work.charge(4L * n);
        var tree = new ArrayList<Edge>(n - 1);
        boolean[] inTree = new boolean[n];
        int[] parent = new int[n];
        var best = new BigInteger[n];
        int current = 0;
        for (int added = 1; added < n; added++) {
            inTree[current] = true;
            int next = -1;
            for (int i = 0; i < n; i++) {
                work.charge(1);
                if (inTree[i]) continue;
                var weight = intersection(rays[current], rays[i], size, work);
                if (best[i] == null || weight.compareTo(best[i]) > 0) {
                    best[i] = weight;
                    parent[i] = current;
                }
                if (next < 0 || best[i].compareTo(best[next]) > 0) next = i;
            }
            tree.add(new Edge(parent[next], next, best[next]));
            current = next;
        }
        return tree;
    }

    /** Independently checks the selected edges, counts, tree structure and clipped arithmetic. */
    public static Verification verify(Request request, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(certificate);
        var work = new Work(limits, cancellation);
        try {
            int n = request.rules().size();
            work.terms(n);
            work.terms(certificate.source().rules().size());
            CongruenceCoverage.validateInput(request, work);
            if (!CongruenceCoverage.sameSource(request, certificate.source(), work))
                return new Verification(false, "SOURCE_MISMATCH", work.used);
            if (certificate.singletonCounts().size() != n || certificate.tree().size() != Math.max(0, n - 1))
                return new Verification(false, "SHAPE_MISMATCH", work.used);
            work.integer(certificate.lowerBound());
            var size = request.progression().count();
            var rays = rays(request, work);
            var bound = size;
            work.charge(n);
            int[] component = new int[n];
            for (int i = 0; i < n; i++) {
                var actual = count(rays[i], size, work);
                if (!work.integer(certificate.singletonCounts().get(i)).equals(actual))
                    return new Verification(false, "SINGLETON_MISMATCH", work.used);
                bound = work.subtract(bound, actual);
                component[i] = i;
            }
            // With exactly n-1 edges, acyclicity implies connectivity (n > 0).
            for (var edge : certificate.tree()) {
                int a = edge.left();
                int b = edge.right();
                if (a < 0 || b < 0 || a >= n || b >= n) return new Verification(false, "INVALID_ENDPOINT", work.used);
                int rootA = root(component, a, work);
                int rootB = root(component, b, work);
                if (rootA == rootB) return new Verification(false, "INVALID_TREE", work.used);
                component[rootA] = rootB;
                var actual = intersection(rays[a], rays[b], size, work);
                if (!work.integer(edge.intersectionCount()).equals(actual))
                    return new Verification(false, "INTERSECTION_MISMATCH", work.used);
                bound = work.add(bound, actual);
            }
            work.charge(1);
            if (!bound.max(ZERO).equals(certificate.lowerBound()))
                return new Verification(false, "BOUND_MISMATCH", work.used);
            return new Verification(true, "VERIFIED", work.used);
        } catch (Unavailable stopped) {
            return new Verification(false, stopped.getMessage(), work.used);
        }
    }

    private static int root(int[] component, int index, Work work) {
        while (component[index] != index) {
            work.charge(1);
            component[index] = component[component[index]];
            index = component[index];
        }
        return index;
    }
}
