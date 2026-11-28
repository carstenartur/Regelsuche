package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Seeded public source fixture. Structural labels never enter the solver. */
final class HornerTransferCases {
    static final int MAX_NODES = 17;
    static final MoveSearch.Budget QUERY = new MoveSearch.Budget(32, 32, 200_000, 512, 8_192);
    record Input(int index, int distributed, String source) {
        CheckedSchemaTaskSolver.Task task() {
            return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(MAX_NODES), QUERY);
        }
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
                var positions = order(seed + "/position/" + index + "/", 3);
                String[] left = {"(a+" + (101 + index) + ")", "e", "i"};
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
                inputs.add(new Input(index, count, "(" + coefficients[0] + "*r+" + coefficients[1] + ")*s+" + coefficients[2]));
            }
        }
        return List.copyOf(inputs);
    }

    private static List<Integer> order(String prefix, int count) {
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
        var signature = new ExactPolynomialAnalysis().normalForm(source).terms().values().stream()
            .map(Object::toString).sorted().toList();
        if (!seen.add(signature)) throw new IllegalStateException("coefficient signatures do not establish disjointness");
    }
}
