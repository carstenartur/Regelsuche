package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.TreeSet;

/** Deterministic elimination orders on the graph of conditioned factor scopes. */
public final class FiniteConstraintPlanner {
    /** Min-fill breaks ties by remaining degree; both graph heuristics break final ties by original variable ID. */
    public enum Strategy { ASCENDING, MIN_DEGREE, MIN_FILL }

    private final ArrayList<TreeSet<Integer>> graph;
    private final TreeSet<Integer> remaining;
    private final CertificateWork work;
    private final long baseCells;
    private long adjacencyEntries;

    private FiniteConstraintPlanner(Problem problem, long baseCells, CertificateWork work) {
        this.work = work;
        this.baseCells = baseCells;
        work.cells(baseCells);
        graph = new ArrayList<>(problem.variableCount());
        remaining = new TreeSet<>(Comparator.<Integer>comparingInt(v -> graph.get(v).size())
            .thenComparingInt(Integer::intValue));
        for (int v = 0; v < problem.variableCount(); v++) {
            work.charge(1);
            graph.add(problem.fixed().containsKey(v) ? null : new TreeSet<>());
        }
    }

    /** Returns null for the ascending order when conditioning leaves only isolated vertices. */
    static int[] order(Problem problem, Collection<Factor> conditioned, Strategy strategy,
            long retained, CertificateWork work) {
        work.charge(0);
        boolean hasEdge = false;
        for (Factor factor : conditioned) {
            work.charge(1);
            if (factor.variables().size() > 1) {
                hasEdge = true;
                break;
            }
        }
        if (!hasEdge) return null;
        int free = problem.variableCount() - problem.fixed().size();
        // Graph slots, free-vertex sets, ordered candidate entries and output order coexist.
        var planner = new FiniteConstraintPlanner(problem, retained + problem.variableCount() + 3L * free, work);
        for (Factor factor : conditioned) {
            var scope = factor.variables();
            work.charge(1L + scope.size());
            for (int i = 0; i < scope.size(); i++) {
                for (int j = i + 1; j < scope.size(); j++) planner.connect(scope.get(i), scope.get(j));
            }
        }
        for (int v = 0; v < problem.variableCount(); v++) {
            work.charge(1);
            if (planner.graph.get(v) != null) planner.insertCandidate(v);
        }
        var order = new int[free];
        for (int i = 0; i < free; i++) {
            int variable = planner.choose(strategy);
            planner.eliminate(variable);
            order[i] = variable;
        }
        return order;
    }

    private int choose(Strategy strategy) {
        // Degrees change only for the neighbors of the selected vertex.
        work.charge(treeWork(remaining.size()));
        if (strategy == Strategy.MIN_DEGREE) return remaining.first();
        int selected = -1;
        long bestScore = Long.MAX_VALUE;
        for (int v : remaining) {
            work.charge(1);
            long score = fillCount(graph.get(v), bestScore);
            if (score < bestScore) {
                selected = v;
                bestScore = score;
                if (score == 0) break;
            }
        }
        return selected;
    }

    /** A search-tree access is charged against a logarithmic size bound. */
    private static long treeWork(int size) {
        return 1L + 2L * (Integer.SIZE - Integer.numberOfLeadingZeros(size));
    }

    private void insertCandidate(int v) {
        work.charge(treeWork(remaining.size()));
        remaining.add(v);
    }

    private void removeCandidate(int v) {
        work.charge(treeWork(remaining.size()));
        remaining.remove(v);
    }

    private long fillCount(TreeSet<Integer> neighbors, long cutoff) {
        long missing = 0;
        for (int left : neighbors) {
            work.charge(treeWork(neighbors.size()));
            for (int right : neighbors.tailSet(left, false)) {
                work.charge(treeWork(graph.get(left).size()));
                if (!graph.get(left).contains(right) && ++missing > cutoff) return missing;
            }
        }
        return missing;
    }

    private void eliminate(int variable) {
        var neighbors = graph.get(variable);
        FiniteConstraintSupport.requireWidth(neighbors.size());
        removeCandidate(variable);
        // Remove every affected key before its comparator-visible degree changes.
        for (int v : neighbors) removeCandidate(v);
        for (int v : neighbors) {
            work.charge(treeWork(graph.get(v).size()));
            graph.get(v).remove(variable);
            adjacencyEntries--;
        }
        for (int left : neighbors) {
            work.charge(treeWork(neighbors.size()));
            for (int right : neighbors.tailSet(left, false)) connect(left, right);
        }
        for (int v : neighbors) insertCandidate(v);
        graph.set(variable, null);
        adjacencyEntries -= neighbors.size();
    }

    private void connect(int left, int right) {
        var adjacent = graph.get(left);
        work.charge(treeWork(adjacent.size()));
        if (adjacent.contains(right)) return;
        work.cells(baseCells + adjacencyEntries + 2);
        work.charge(treeWork(adjacent.size()) + treeWork(graph.get(right).size()));
        adjacent.add(right);
        graph.get(right).add(left);
        adjacencyEntries += 2;
    }
}
