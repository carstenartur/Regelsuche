package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Each live nonscalar factor is indexed once, at the first remaining variable of its scope.
 * Increasing source and output IDs keep each bucket in the verifier's canonical order.
 */
final class FiniteConstraintBuckets {
    private final List<List<Integer>> buckets;
    private final int[] positions;
    private long entries;
    private final CertificateWork work;

    FiniteConstraintBuckets(int variableCount, int[] order, Map<Integer, Factor> active,
            long retained, CertificateWork work) {
        this.work = work;
        int size = order == null ? variableCount : order.length;
        work.cells(retained + 2L * size + (order == null ? 0 : variableCount));
        positions = order == null ? null : new int[variableCount];
        if (order != null) {
            for (int i = 0; i < order.length; i++) {
                work.charge(1);
                positions[order[i]] = i;
            }
        }
        buckets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            work.charge(1);
            buckets.add(null);
        }
        for (var entry : active.entrySet()) add(entry.getKey(), entry.getValue(), retained);
    }

    long cells() {
        return 2L * buckets.size() + (positions == null ? 0 : positions.length) + entries;
    }

    void add(int id, Factor factor, long retained) {
        work.charge(1);
        int first = Integer.MAX_VALUE;
        for (int variable : factor.variables()) {
            work.charge(1);
            first = Math.min(first, positions == null ? variable : positions[variable]);
        }
        if (first == Integer.MAX_VALUE) return;
        work.cells(retained + cells() + 1);
        var inputs = buckets.get(first);
        if (inputs == null) {
            inputs = new ArrayList<>();
            buckets.set(first, inputs);
        }
        inputs.add(id);
        entries++;
    }

    List<Integer> take(int position) {
        work.charge(1);
        var inputs = buckets.set(position, null);
        if (inputs == null) return List.of();
        entries -= inputs.size();
        return inputs;
    }
}
