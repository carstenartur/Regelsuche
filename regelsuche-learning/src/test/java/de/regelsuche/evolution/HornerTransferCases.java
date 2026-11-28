package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Seeded public source fixture. Structural labels never enter the solver. */
final class HornerTransferCases {
    static final int MAX_NODES = 17;
    enum Topology {
        H(MAX_NODES), D(19);
        private final int nodeBound;
        Topology(int nodeBound) { this.nodeBound = nodeBound; }
    }
    record Input(int index, int distributed, Topology topology, String source) implements TransferStudyInput {
        @Override public int variant() { return distributed; }
        @Override public int nodeBound() { return topology.nodeBound; }
    }

    private HornerTransferCases() {}

    static List<Input> panel(String seed) {
        if (!seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected the full freeze commit SHA");
        var inputs = new ArrayList<Input>();
        for (int block = 0; block < 32; block++) {
            var counts = order(seed + "/count/" + block + "/", 4);
            for (int slot = 0; slot < counts.size(); slot++) {
                int index = 4 * block + slot;
                int count = counts.get(slot);
                inputs.add(input(seed, index, count, 101 + index, Topology.H));
            }
        }
        return List.copyOf(inputs);
    }

    private record Stratum(Topology topology, int count) {}

    static List<Input> mixedPanel(String seed) {
        if (!seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected the full freeze commit SHA");
        var inputs = new ArrayList<Input>();
        var strata = new ArrayList<Stratum>();
        for (Topology topology : Topology.values()) {
            for (int count = 0; count < 4; count++) strata.add(new Stratum(topology, count));
        }
        for (int block = 0; block < 128; block++) {
            String prefix = seed + "/order/" + block + "/";
            Comparator<Stratum> digest = Comparator.comparing(s -> SchematicProofPlan.hash(prefix + s.topology() + "/" + s.count()));
            var ordered = strata.stream().sorted(digest.thenComparing(s -> s.topology().name()).thenComparingInt(Stratum::count)).toList();
            for (var stratum : ordered) {
                int index = inputs.size();
                inputs.add(input(seed, index, stratum.count(), 10_001 + index, stratum.topology()));
            }
        }
        return List.copyOf(inputs);
    }

    private static Input input(String seed, int index, int count, int constant, Topology topology) {
        var positions = order(seed + "/position/" + index + "/", 3);
        String[] left = {"(a+" + constant + ")", "e", "i"};
        String[] right = {"b", "f", "j"};
        String[] p = {"c", "g", "k"};
        String[] q = {"d", "h", "l"};
        String[] coefficients = new String[3];
        for (int i = 0; i < coefficients.length; i++) {
            String u = left[i], v = right[i];
            String term = u + "*" + v;
            if (positions.subList(0, count).contains(i)) {
                term = "(" + u + "*((" + v + "-" + p[i] + ")-" + q[i] + ")+" + u + "*" + q[i] + ")+" + u + "*" + p[i];
            } else if (count == 0 && positions.getFirst() == i) {
                term = "(" + term + ")+0";
            }
            coefficients[i] = "(" + term + ")";
        }
        String source = switch (topology) {
            case H -> "(" + coefficients[0] + "*r+" + coefficients[1] + ")*s+" + coefficients[2];
            case D -> "(" + coefficients[0] + "*r+" + coefficients[1] + "*s)+" + coefficients[2] + "*t";
        };
        return new Input(index, count, topology, source);
    }

    static List<Integer> order(String prefix, int count) {
        Comparator<Integer> digest = Comparator.comparing(i -> SchematicProofPlan.hash(prefix + i));
        return IntStream.range(0, count).boxed().sorted(digest.thenComparingInt(Integer::intValue)).toList();
    }

    /**
     * Variable renaming preserves the multiset of exact polynomial coefficients. Pairwise
     * disjoint signatures therefore certify disjoint alpha identities without enumerating
     * all permutations of this family's variables. An inconclusive signature fails the run.
     */
    static void verifySplit(List<Input> finalInputs) {
        Set<List<String>> seen = new HashSet<>();
        for (var input : DistributiveCoverageCases.training()) requireDistinct(seen, input.source());
        for (var input : DistributiveCoverageCases.gate()) requireDistinct(seen, input.source());
        for (var input : finalInputs) requireDistinct(seen, input.source());
    }

    private static void requireDistinct(Set<List<String>> seen, String source) {
        if (!seen.add(coefficientSignature(source))) throw new IllegalStateException("coefficient signatures do not establish disjointness");
    }

    static List<String> coefficientSignature(String source) {
        return new ExactPolynomialAnalysis().normalForm(source).terms().values().stream()
            .map(Object::toString).sorted().toList();
    }
}
