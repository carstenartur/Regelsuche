package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded maximum matching and independently replayable Hall deficiency for finite bipartite graphs. */
public final class BipartiteMatching {
    // A caller may request a larger budget, but the dense working arrays still have a runtime cap.
    private static final int MAX_GRAPH_SIZE = 1_000_000;

    private BipartiteMatching() {}

    /** An edge from a left vertex to a right vertex, in separate zero-based index spaces. */
    public record Edge(int left, int right) {}

    /** Immutable simple graph. Repetitions and input order do not change graph identity. */
    public record Request(int leftCount, int rightCount, List<Edge> edges) {
        public Request {
            if (leftCount < 0 || rightCount < 0) {
                throw new IllegalArgumentException("INVALID_GRAPH_DIMENSIONS");
            }
            var canonical = new ArrayList<>(edges);
            for (var edge : canonical) {
                Objects.requireNonNull(edge);
                if (!inRange(edge.left(), leftCount) || !inRange(edge.right(), rightCount)) {
                    throw new IllegalArgumentException("INVALID_EDGE_ENDPOINT");
                }
            }
            canonical.sort(Comparator.comparingInt(Edge::left).thenComparingInt(Edge::right));
            int unique = 0;
            for (var edge : canonical) {
                if (unique == 0 || !edge.equals(canonical.get(unique - 1))) {
                    canonical.set(unique++, edge);
                }
            }
            edges = List.copyOf(canonical.subList(0, unique));
        }
    }

    /**
     * Source graph, matching and left vertex subset W. Treat externally constructed evidence
     * as untrusted until it passes {@link #verify}.
     */
    public record Certificate(Request source, List<Edge> matching, List<Integer> witness) {
        public Certificate {
            Objects.requireNonNull(source);
            matching = List.copyOf(matching);
            witness = List.copyOf(witness);
        }

        public int matchingSize() {
            return matching.size();
        }

        /** Maximum Hall deficiency for a certificate that passes replay. */
        public int deficiency() {
            return source.leftCount() - matchingSize();
        }
    }

    public enum Status { COMPLETE, UNAVAILABLE }

    /** A completed search carries a certificate. Interruption produces no claim. */
    public record Result(Status status, Optional<Certificate> certificate, String reason, long work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(certificate);
            Objects.requireNonNull(reason);
            if (work < 0 || (status == Status.COMPLETE) != certificate.isPresent()) {
                throw new IllegalArgumentException("INVALID_MATCHING_RESULT");
            }
        }
    }

    /** A verifier failure includes invalid evidence and resource exhaustion. */
    public record Verification(boolean valid, String reason, long work) {}

    /**
     * Searches by iterative augmenting paths in ascending vertex and neighbor order. Both
     * operations use {@code maximumTerms} to bound the sum of both vertex counts and the number
     * of distinct edges, with a runtime cap of one million graph entries.
     */
    public static Result search(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        var work = new Work(limits, cancellation);
        try {
            work.checkSize(request);
            var proof = new Search(request, work).certificate();
            work.charge(1);
            return new Result(Status.COMPLETE, Optional.of(proof), "COMPLETE", work.used);
        } catch (Unavailable unavailable) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), unavailable.getMessage(), work.used);
        }
    }

    /**
     * Replays the matching and the vertex cover formed by the left complement of W and
     * all right neighbors of W. Equal cardinalities certify optimality. Replay is linear
     * in the graph and the certificate and does not run the search.
     */
    public static Verification verify(Request request, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(certificate);
        var work = new Work(limits, cancellation);
        try {
            work.checkSize(request);
            var source = certificate.source();
            int leftCount = request.leftCount();
            int rightCount = request.rightCount();
            int edgeCount = request.edges().size();
            if (leftCount != source.leftCount() || rightCount != source.rightCount()
                    || edgeCount != source.edges().size()) {
                return new Verification(false, "SOURCE_MISMATCH", work.used);
            }

            int matchingSize = certificate.matchingSize();
            int witnessSize = certificate.witness().size();
            if (matchingSize > Math.min(leftCount, rightCount)) {
                return new Verification(false, "INVALID_MATCHING", work.used);
            }
            if (witnessSize > leftCount) {
                return new Verification(false, "INVALID_WITNESS", work.used);
            }

            work.charge(2L * leftCount + 2L * rightCount);
            int[] matchedRight = new int[leftCount];
            boolean[] usedRight = new boolean[rightCount];
            boolean[] inWitness = new boolean[leftCount];
            boolean[] inNeighborhood = new boolean[rightCount];

            for (var edge : certificate.matching()) {
                work.charge(1);
                int left = edge.left();
                int right = edge.right();
                if (!inRange(left, leftCount) || !inRange(right, rightCount)
                        || matchedRight[left] != 0 || usedRight[right]) {
                    return new Verification(false, "INVALID_MATCHING", work.used);
                }
                matchedRight[left] = right + 1;
                usedRight[right] = true;
            }

            for (int left : certificate.witness()) {
                work.charge(1);
                if (!inRange(left, leftCount) || inWitness[left]) {
                    return new Verification(false, "INVALID_WITNESS", work.used);
                }
                inWitness[left] = true;
            }

            int checkedMatches = 0;
            int neighborhoodSize = 0;
            for (int i = 0; i < edgeCount; i++) {
                work.charge(1);
                var edge = request.edges().get(i);
                if (!edge.equals(source.edges().get(i))) {
                    return new Verification(false, "SOURCE_MISMATCH", work.used);
                }
                int left = edge.left();
                int right = edge.right();
                if (matchedRight[left] == right + 1) {
                    checkedMatches++;
                }
                if (inWitness[left] && !inNeighborhood[right]) {
                    inNeighborhood[right] = true;
                    neighborhoodSize++;
                }
            }

            work.charge(1);
            if (checkedMatches != matchingSize) {
                return new Verification(false, "INVALID_MATCHING", work.used);
            }
            long coverSize = (long) leftCount - witnessSize + neighborhoodSize;
            if (coverSize != matchingSize) {
                return new Verification(false, "CARDINALITY_MISMATCH", work.used);
            }
            return new Verification(true, "VERIFIED", work.used);
        } catch (Unavailable unavailable) {
            return new Verification(false, unavailable.getMessage(), work.used);
        }
    }

    private static boolean inRange(int index, int count) {
        return index >= 0 && index < count;
    }

    private static final class Search {
        private final Request request;
        private final Work work;
        private final int[] edgeStarts;
        // Matches store index plus one, leaving zero for an unmatched vertex.
        private final int[] leftMatch;
        private final int[] rightMatch;
        private final int[] reachedLeft;
        private final int[] reachedRight;
        private final int[] rightPredecessor;
        private final int[] queue;

        private Search(Request request, Work work) {
            this.request = request;
            this.work = work;
            int leftCount = request.leftCount();
            int rightCount = request.rightCount();
            work.charge(4L * leftCount + 3L * rightCount + 1);
            edgeStarts = new int[leftCount + 1];
            leftMatch = new int[leftCount];
            rightMatch = new int[rightCount];
            reachedLeft = new int[leftCount];
            reachedRight = new int[rightCount];
            rightPredecessor = new int[rightCount];
            queue = new int[leftCount];

            int index = 0;
            for (int left = 0; left < leftCount; left++) {
                work.charge(1);
                while (index < request.edges().size() && request.edges().get(index).left() == left) {
                    work.charge(1);
                    index++;
                }
                edgeStarts[left + 1] = index;
            }
        }

        private Certificate certificate() {
            // Once the previous prefix has a maximum matching, only the new left vertex
            // can start an augmenting path that increases it.
            for (int left = 0; left < leftMatch.length; left++) {
                work.charge(1);
                queue[0] = left;
                int freeRight = findFreeRight(1, left + 1);
                if (freeRight >= 0) {
                    augment(freeRight);
                }
            }

            int queued = 0;
            int witnessMark = -1;
            for (int left = 0; left < leftMatch.length; left++) {
                work.charge(1);
                if (leftMatch[left] == 0) {
                    queue[queued++] = left;
                    reachedLeft[left] = witnessMark;
                }
            }
            if (findFreeRight(queued, witnessMark) >= 0) {
                throw new IllegalStateException("MATCHING_HAS_AUGMENTING_PATH");
            }

            var matching = new ArrayList<Edge>();
            var witness = new ArrayList<Integer>();
            for (int left = 0; left < leftMatch.length; left++) {
                work.charge(1);
                if (leftMatch[left] != 0) {
                    matching.add(new Edge(left, leftMatch[left] - 1));
                }
                if (reachedLeft[left] == witnessMark) {
                    witness.add(left);
                }
            }
            work.charge(1L + matching.size() + witness.size());
            return new Certificate(request, matching, witness);
        }

        private int findFreeRight(int queued, int mark) {
            for (int next = 0; next < queued; next++) {
                work.charge(1);
                int left = queue[next];
                for (int i = edgeStarts[left]; i < edgeStarts[left + 1]; i++) {
                    work.charge(1);
                    int right = request.edges().get(i).right();
                    if (leftMatch[left] == right + 1 || reachedRight[right] == mark) {
                        continue;
                    }
                    reachedRight[right] = mark;
                    rightPredecessor[right] = left;
                    if (rightMatch[right] == 0) {
                        return right;
                    }
                    // A matched left vertex is reached only through its unique matched right.
                    int matchedLeft = rightMatch[right] - 1;
                    reachedLeft[matchedLeft] = mark;
                    queue[queued++] = matchedLeft;
                }
            }
            return -1;
        }

        private void augment(int right) {
            while (right >= 0) {
                work.charge(1);
                int left = rightPredecessor[right];
                int previousRight = leftMatch[left] - 1;
                leftMatch[left] = right + 1;
                rightMatch[right] = left + 1;
                right = previousRight;
            }
        }
    }

    private static final class Work {
        private final IterationLimits limits;
        private final BooleanSupplier cancellation;
        private final long started = System.nanoTime();
        private final long timeoutNanos;
        private long used;

        private Work(IterationLimits limits, BooleanSupplier cancellation) {
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            timeoutNanos = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        }

        private void checkSize(Request request) {
            charge(1);
            long size = (long) request.leftCount() + request.rightCount() + request.edges().size();
            if (size > Math.min(limits.maximumTerms(), MAX_GRAPH_SIZE)) {
                throw new Unavailable("GRAPH_SIZE_LIMIT");
            }
        }

        private void charge(long units) {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new Unavailable("CANCELLED");
            }
            if (System.nanoTime() - started >= timeoutNanos) {
                throw new Unavailable("TIME_LIMIT");
            }
            if (units > limits.maximumWork() - used) {
                throw new Unavailable("WORK_LIMIT");
            }
            used += units;
        }
    }

    private static final class Unavailable extends RuntimeException {
        private Unavailable(String reason) {
            super(reason, null, false, false);
        }
    }
}
