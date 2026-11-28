package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.ArrayList;
import java.util.List;

/** Inputs and node-count goals transcribed from the prospective public protocol. */
final class LocalTraceFamilyCases {
    static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(32, 32, 200_000, 512, 200_000);

    record Case(String family, int n, String source, int nodes) {
        CheckedSchemaTaskSolver.Task task() {
            return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), BUDGET);
        }
    }

    private LocalTraceFamilyCases() { }

    private static String energy(String a, String b) {
        return "(((" + a + ")+(" + b + "))*((" + a + ")-(" + b + "))+(" + b + ")*(" + b + "))";
    }

    static List<Case> train() {
        return List.of(new Case("offset", 0, energy("a", "b") + "+(c+0)", 5),
            new Case("scale", 0, "(d*1)*" + energy("e", "f"), 5),
            new Case("subtrahend", 0, energy("g", "h") + "-(k*1)", 5),
            new Case("minuend", 0, "(m+0)-" + energy("p", "q"), 5));
    }

    static List<Case> gate() {
        return List.of(new Case("shifted-product", 0, energy("x*y", "z") + "+9", 7),
            new Case("scaled-sum", 0, "13*" + energy("r+s", "t"), 7),
            new Case("single", 0, energy("u", "v"), 3), new Case("unreachable", 0, "j+19", 1));
    }

    static List<Case> finalCases() {
        var cases = new ArrayList<Case>();
        for (int n : List.of(2, 3, 5, 7)) {
            cases.add(new Case("two-site-energy", n, energy("x*y", "z") + "+" + energy("y+" + n, "w"), 11));
            cases.add(new Case("squared-compensated-product", n, "(" + n + "-" + energy("x*y", "z") + ")^2", 9));
            cases.add(new Case("rational-quotient", n, energy("x-" + n, "y") + "/" + n, 7));
            cases.add(new Case("common-factor", n, "(x+" + n + ")*y+(x+" + n + ")*z", 7));
            cases.add(new Case("residual-near-miss", n, "((x+y)*(x-y)+y*(y+" + n + "))", 3));
            cases.add(new Case("irreducible", n, "x+" + n, 1));
        }
        return List.copyOf(cases);
    }
}
