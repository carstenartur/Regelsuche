package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Held-out generators. Only the exact source, node bound and budget reach the solver. */
final class PairedTacticCases {
    enum Family { A, B }
    record Stratum(int m, int orientation, int cap) {}
    record Input(Family family, int index, Stratum stratum, String source, int target) {
        CheckedSchemaTaskSolver.Task task() {
            return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(target), new MoveSearch.Budget(32, 32, 200_000, 512, stratum.cap()));
        }
    }

    private PairedTacticCases() {}

    static List<Input> panel(Family family, String seed) {
        if (!seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected a full freeze SHA");
        var strata = new ArrayList<Stratum>();
        for (int m : List.of(2, 3)) for (int o : List.of(0, 1))
            for (int cap : List.of(256, 768, 2048, 8192)) strata.add(new Stratum(m, o, cap));
        var panel = new ArrayList<Input>();
        for (int block = 0; block < 32; block++) {
            String prefix = seed + "/order/" + family + "/" + block + "/";
            Comparator<Stratum> order = Comparator.comparing(s -> SchematicProofPlan.hash(
                prefix + s.m() + "/" + s.orientation() + "/" + s.cap()));
            var ordered = strata.stream().sorted(order.thenComparingInt(Stratum::m)
                .thenComparingInt(Stratum::orientation).thenComparingInt(Stratum::cap)).toList();
            for (var s : ordered) {
                int i = panel.size();
                var indices = HornerTransferCases.order(seed + "/vars/" + family + "/" + i + "/", s.m());
                String source = family == Family.A ? blend(i, s, indices) : circle(i, s, indices);
                panel.add(new Input(family, i, s, source, family == Family.A ? 3 : 4 * s.m() + 1));
            }
        }
        return List.copyOf(panel);
    }

    static String blend(int i, Stratum stratum, List<Integer> indices) {
        String previous = "(a+" + (50_001 + i) + ")";
        for (int j : indices) {
            String t = Character.toString("pqr".charAt(j));
            if (stratum.orientation() != 0) t = "(" + t + "+" + "stu".charAt(j) + ")";
            previous = "(" + previous + "*(1-" + t + ")+" + previous + "*" + t + ")";
        }
        return previous;
    }

    static String circle(int i, Stratum stratum, List<Integer> indices) {
        var terms = new ArrayList<String>();
        for (int j : indices) {
            String r = Character.toString("bcd".charAt(j));
            if (terms.isEmpty()) r = "(" + r + "+" + (60_001 + i) + ")";
            char d = "efg".charAt(j);
            terms.add("((" + r + "+" + d + ")*(" + r + "-" + d + ")+" + d + "*" + d + ")");
        }
        if (stratum.orientation() != 0) Collections.reverse(terms);
        return String.join("+", terms);
    }

    static void verifySplit(List<Input> inputs, List<String> trainingSources) {
        var old = new HashSet<List<String>>();
        for (var source : trainingSources) old.add(HornerTransferCases.coefficientSignature(source));
        DistributiveCoverageCases.training().forEach(c -> old.add(HornerTransferCases.coefficientSignature(c.source())));
        DistributiveCoverageCases.gate().forEach(c -> old.add(HornerTransferCases.coefficientSignature(c.source())));
        DistributiveCoverageCases.panel().forEach(c -> old.add(HornerTransferCases.coefficientSignature(c.source())));
        HornerTransferCases.panel("05dda9fb837d53f6d37e3925744b009c3b09672d").forEach(c -> old.add(HornerTransferCases.coefficientSignature(c.source())));
        HornerTransferCases.mixedPanel(IntervalTransferCases.MIXED_SEED).forEach(c -> old.add(HornerTransferCases.coefficientSignature(c.source())));
        IntervalTransferCases.panel("15f2fb41e876df5bb13138a2b9ed87340fa85146").forEach(c -> old.add(HornerTransferCases.coefficientSignature(c.source())));
        for (var input : inputs) {
            if (!old.add(HornerTransferCases.coefficientSignature(input.source())))
                throw new IllegalStateException("coefficient overlap at " + input.family() + "/" + input.index());
        }
    }
}
