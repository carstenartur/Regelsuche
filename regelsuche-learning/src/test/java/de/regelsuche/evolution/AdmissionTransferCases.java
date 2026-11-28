package de.regelsuche.evolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import de.regelsuche.evolution.PairedTacticCases.Input;
import de.regelsuche.evolution.PairedTacticCases.Stratum;

/** Two prospectively registered generators. Only the source, bound and budget reach search. */
final class AdmissionTransferCases {
    private AdmissionTransferCases() {}

    static List<Input> panel(String family, String seed) {
        if (!seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected the complete freeze SHA");
        if (!family.equals("C") && !family.equals("D")) throw new IllegalArgumentException("unknown family");
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
            for (var stratum : ordered) {
                int i = panel.size();
                var indices = HornerTransferCases.order(seed + "/vars/" + family + "/" + i + "/",
                    family.equals("C") ? stratum.m() : 4);
                // A/B are the existing row carrier's positional labels. Archive this stream under C/D.
                if (family.equals("C")) {
                    panel.add(new Input(PairedTacticCases.Family.A, i, stratum, cascade(i, stratum, indices),
                        4 * stratum.m() + 1));
                } else {
                    panel.add(new Input(PairedTacticCases.Family.B, i, stratum, area(i, stratum, indices),
                        9 + 4 * stratum.m() - 2 * stratum.orientation()));
                }
            }
        }
        return List.copyOf(panel);
    }

    static String cascade(int i, Stratum stratum, List<Integer> order) {
        var stages = new ArrayList<String>();
        for (int j : order) {
            String gain = Character.toString("abc".charAt(j));
            if (stages.isEmpty()) gain = "(" + gain + "+" + (70_001 + i) + ")";
            char total = "efg".charAt(j), first = "jkl".charAt(j), second = "nop".charAt(j);
            String stage = stratum.orientation() == 0
                ? gain + "*(" + total + "-" + first + ")+" + gain + "*" + first
                : "(" + gain + "*((" + total + "-" + first + ")-" + second + ")+" + gain + "*" + second + ")+" + gain + "*" + first;
            stages.add("(" + stage + ")");
        }
        return String.join("*", stages);
    }

    static String area(int i, Stratum stratum, List<Integer> order) {
        String[] coords = {"p", "q", "r", "s"};
        for (int k = 0; k < stratum.m(); k++) {
            int j = order.get(k);
            String gain = Character.toString("abcd".charAt(j));
            if (k == 0) gain = "(" + gain + "+" + (90_001 + i) + ")";
            char reading = "efgh".charAt(j), adjustment = "jklm".charAt(j);
            coords[j] = k == 0 && stratum.orientation() == 1
                ? "(" + gain + "*(" + reading + "-" + adjustment + ")+" + gain + "*" + adjustment + ")"
                : "(" + reading + "*" + gain + "+" + adjustment + "*" + gain + ")";
        }
        return "(" + coords[0] + "*" + coords[1] + ")-(" + coords[2] + "*" + coords[3] + ")";
    }
}
