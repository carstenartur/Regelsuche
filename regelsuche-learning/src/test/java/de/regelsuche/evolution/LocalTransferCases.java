package de.regelsuche.evolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Prospective weighted prediction residuals. The solving API receives no case or family metadata. */
final class LocalTransferCases {
    record Input(int index, String source, int target, int cap, int stages, int variant) {}
    private record Stratum(int stages, int variant, int cap) {}

    private LocalTransferCases() {}

    static List<Input> panel(String seed) {
        if (!seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected complete public freeze SHA");
        var strata = new ArrayList<Stratum>();
        for (int stages : List.of(2, 3)) {
            for (int variant : List.of(0, 1)) {
                for (int cap : List.of(256, 768, 2048, 8192)) strata.add(new Stratum(stages, variant, cap));
            }
        }
        var result = new ArrayList<Input>();
        for (int block = 0; block < 16; block++) {
            String prefix = seed + "/order/E/" + block + "/";
            Comparator<Stratum> order = Comparator.comparing(s -> SchematicProofPlan.hash(
                prefix + s.stages() + "/" + s.variant() + "/" + s.cap()));
            for (var s : strata.stream().sorted(order.thenComparingInt(Stratum::stages)
                    .thenComparingInt(Stratum::variant).thenComparingInt(Stratum::cap)).toList()) {
                int i = result.size();
                var permutation = HornerTransferCases.order(seed + "/vars/E/" + i + "/", s.stages());
                result.add(new Input(i, residual(i, s.variant(), permutation), 8*s.stages() + 5 - 2*s.variant(),
                    s.cap(), s.stages(), s.variant()));
            }
        }
        return List.copyOf(result);
    }

    static String residual(int index, int variant, List<Integer> order) {
        var weighted = new ArrayList<String>();
        for (int j : order) {
            char loading = "abc".charAt(j), gain = "pqr".charAt(j), adjustment = "stu".charAt(j);
            String component;
            if (weighted.isEmpty() && variant == 1) {
                component = "(" + gain + "+" + adjustment + ")*(" + gain + "-" + adjustment + ")+" + adjustment + "*" + adjustment;
            } else {
                component = "efg".charAt(j) + "*" + gain + "+" + "jkl".charAt(j) + "*" + gain;
            }
            weighted.add(loading + "*(" + component + ")");
        }
        return "((h+" + (110_001 + index) + ")-(" + String.join("+", weighted) + "))^2";
    }
}
