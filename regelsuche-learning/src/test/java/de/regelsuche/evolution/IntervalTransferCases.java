package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferCases.coefficientSignature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Rectangle sums with one constant height over adjacent cells. */
final class IntervalTransferCases {
    private static final String ENDPOINTS = "pqrs";
    private static final String HORNER_SEED = "05dda9fb837d53f6d37e3925744b009c3b09672d";
    static final String MIXED_SEED = "efb9439d38f8f0fa4e20ce5ffbedc34fa5248519";

    enum Orientation { L, R }
    record Stratum(int cells, Orientation orientation) {}
    record Input(int index, Stratum stratum, String source) implements TransferStudyInput {
        @Override public int variant() { return stratum.cells(); }
        @Override public int nodeBound() { return 4 * stratum.cells() + 3; }
    }

    private IntervalTransferCases() {}

    static List<Input> panel(String seed) {
        if (!seed.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("expected full freeze SHA");
        var strata = List.of(new Stratum(2, Orientation.L), new Stratum(2, Orientation.R),
            new Stratum(3, Orientation.L), new Stratum(3, Orientation.R));
        var inputs = new ArrayList<Input>();
        for (int block = 0; block < 256; block++) {
            String prefix = seed + "/order/" + block + "/";
            Comparator<Stratum> order = Comparator.comparing(s -> SchematicProofPlan.hash(prefix + s.cells() + "/" + s.orientation()));
            for (var stratum : strata.stream().sorted(order.thenComparingInt(Stratum::cells).thenComparing(Stratum::orientation)).toList()) {
                int index = inputs.size();
                var names = HornerTransferCases.order(seed + "/names/" + index + "/", stratum.cells() + 1);
                inputs.add(new Input(index, stratum, source(index, stratum, names)));
            }
        }
        return List.copyOf(inputs);
    }

    static String source(int index, Stratum stratum, List<Integer> names) {
        String height = "(a+" + (30_001 + index) + ")";
        return String.join("+", IntStream.range(0, stratum.cells()).mapToObj(i -> {
            String width = "(" + ENDPOINTS.charAt(names.get(i + 1)) + "-" + ENDPOINTS.charAt(names.get(i)) + ")";
            return stratum.orientation() == Orientation.L ? height + "*" + width : width + "*" + height;
        }).toList());
    }

    // Duplicates within old panels (e.g. neutral and negative controls) are allowed.
    // Every new coefficient multiset must be distinct from all old and other new tasks.
    static void verifySplit(List<Input> inputs) {
        Set<List<String>> signatures = new HashSet<>();
        DistributiveCoverageCases.training().forEach(c -> signatures.add(coefficientSignature(c.source())));
        DistributiveCoverageCases.gate().forEach(c -> signatures.add(coefficientSignature(c.source())));
        DistributiveCoverageCases.panel().forEach(c -> signatures.add(coefficientSignature(c.source())));
        HornerTransferCases.panel(HORNER_SEED).forEach(c -> signatures.add(coefficientSignature(c.source())));
        HornerTransferCases.mixedPanel(MIXED_SEED).forEach(c -> signatures.add(coefficientSignature(c.source())));
        for (var input : inputs) {
            if (!signatures.add(coefficientSignature(input.source()))) throw new IllegalStateException("split overlap at " + input.index());
        }
    }

}
