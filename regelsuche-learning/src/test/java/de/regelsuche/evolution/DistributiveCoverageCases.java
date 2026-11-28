package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.ArrayList;
import java.util.List;

/** The published generator is only a source fixture. No labels are attached to solver inputs. */
final class DistributiveCoverageCases {
    static final int REFERENCE = 200_000;
    static final List<Integer> WORK = List.of(64, 96, 128, 192, 256, 384, 512, 768, 1024,
        1536, 2048, 3072, 4096, 6144, 8192, 12288, REFERENCE);

    record Case(String family, int n, String source, int nodes, int depth) {
        Case(String family, int n, String source, int nodes) { this(family, n, source, nodes, 32); }
        CheckedSchemaTaskSolver.Task task(int work) {
            return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
                CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), new MoveSearch.Budget(32, depth, 200_000, 512, work));
        }
    }
    private DistributiveCoverageCases() { }
    private static String f(String a, String b, String c, String d) {
        return "((("+a+")*((("+b+")-("+c+"))-("+d+"))+("+a+")*("+d+"))+("+a+")*("+c+"))";
    }
    private static String r(String a, String b, String c, String d) {
        return "((((("+b+")-("+c+"))-("+d+"))*("+a+")+("+d+")*("+a+"))+("+c+")*("+a+"))";
    }
    private static String j(String a, String b, String c, String d) {
        return "((("+a+")*((("+b+")-("+c+"))-("+d+"))+("+a+")*("+d+"))+("+a+")*(("+c+")+0))";
    }
    static List<Case> training() {
        return List.of(new Case("train-base", 0, f("a","b","c","d"), 3),
            new Case("train-sum", 0, f("e+f","g","h","i"), 5));
    }
    static List<Case> gate() {
        return List.of(new Case("gate-constant", 0, f("r","3","s","t"), 3),
            new Case("gate-offset", 0, f("x-7","y","z","w"), 5),
            new Case("gate-depth-two", 0, f("p","q+11","u","v"), 5, 2));
    }
    static List<Case> panel() {
        var cases = new ArrayList<Case>();
        for (int n : List.of(2,3,5,7)) {
            cases.add(new Case("parallel", n, f("x+"+n,"y","z","w")+"-"+f("u","v","t","g"), 9));
            cases.add(new Case("serial", n, f("2",f("x","y+"+n,"s","t"),"u","v"), 7));
            cases.add(new Case("normalize", n, j("x","y+"+n,"z","w")+"^2", 7));
            cases.add(new Case("mirrored", n, r("x+"+n,"y","s","t")+"/"+n, 7));
            cases.add(new Case("neutral", n, "((x+"+n+")+0)*1", 3));
            cases.add(new Case("negative", n, "x+"+n, 1));
        }
        return List.copyOf(cases);
    }
}
