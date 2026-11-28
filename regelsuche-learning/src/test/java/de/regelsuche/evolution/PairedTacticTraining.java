package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;

/** Public development sources. Only the source and its declared size goal enter the solver. */
final class PairedTacticTraining {
    private PairedTacticTraining() {}

    static List<CheckedSchemaTaskSolver.Task> lessons() {
        var cases = new java.util.ArrayList<>(List.of(task("((x+0)*1)+(a+0)",3), task("((y+0)*1)-(b+0)",3),
            task("((a*((b-c)-d)+a*d)+a*c)",3), task("(((p+8)*((q-r)-s)+(p+8)*s)+(p+8)*r)",5)));
        cases.addAll(compositions(101));
        return List.copyOf(cases);
    }

    static List<CheckedSchemaTaskSolver.Task> gates() {
        var cases = new java.util.ArrayList<>(List.of(task("((3*c+0)*1)",3,1), task("(((r+9)*((t-u)-v)+(r+9)*v)+(r+9)*u)",5,2)));
        cases.addAll(compositions(501));
        return List.copyOf(cases);
    }

    static List<CheckedSchemaTaskSolver.Task> calibration() {
        var cases = new java.util.ArrayList<>(List.of(task("((a+12)+0)*1",3,1), task("((b*13)+0)*1",3,1),
            task("((a+14)*((b-c)-d)+(a+14)*d)+(a+14)*c",5,2),
            task("((a+15)*((b-c)-d)+(a+15)*d)+(a+15)*c",5),
            task("((a+16)*(b-c))+(a+16)*c",5,1), task("(d*(a-17))+d*17",3,1),
            task("((u+18)+0)*(v+1)",7), task("((w+19)*1)-(t+2)",7),
            task("(r+20)*(r-20)",5), task("(s+21)*(s-21)",3),
            task("(x+22)*a+(x+22)*b",7), task("a*(y+23)+b*(y+23)",7),
            task("x+(y*24)",1), task("z*(a+25)",1)));
        cases.addAll(compositions(901));
        // Pair ordinary searches with one-step frontier limits on a fresh set of the same
        // inventory-generated compositions. Both successful and failed probes are charged.
        for (var sample : compositions(1201)) {
            var b = sample.budget();
            cases.add(new CheckedSchemaTaskSolver.Task(sample.source(),sample.assumptions(),sample.goal(),
                new MoveSearch.Budget(b.maxPrimitiveSteps(),1,b.maxTheoryWork(),b.maxStates(),b.totalWork())));
        }
        return List.copyOf(cases);
    }

    /** Compose cyclic neighbours of the registered primitive inventory. The generated target
     * sets only a size limit. Actual solutions and their ordered steps come from primitive search. */
    private static List<CheckedSchemaTaskSolver.Task> compositions(int first) {
        var genes = TraceStrategyTransferExample.inventory().rewrites();
        var cases = new java.util.ArrayList<CheckedSchemaTaskSolver.Task>();
        for (int i = 0; i < genes.size(); i++) {
            var outer = genes.get(i);
            var inner = genes.get((i + 1) % genes.size());
            for (int variant = 0; variant < 2; variant++) {
                int n = first + 2 * i + variant;
                String a = variant == 0 ? "(x+y)" : "(x*z)";
                String from = instantiate(inner.sourcePattern(),a,"(z+" + n + ")","y");
                String to = instantiate(inner.targetPattern(),a,"(z+" + n + ")","y");
                String source = "(" + instantiate(outer.sourcePattern(),from,"z","(x+1)") + ")+" + n;
                String reduced = "(" + instantiate(outer.targetPattern(),to,"z","(x+1)") + ")+" + n;
                int nodes = count(new ExpressionParser().parseExactTerm(reduced).expression());
                if (nodes < count(new ExpressionParser().parseExactTerm(source).expression())) cases.add(task(source,nodes));
            }
        }
        return List.copyOf(cases);
    }

    private static String instantiate(String pattern, String a, String b, String c) {
        return pattern.replace("?A","(" + a + ")").replace("?B","(" + b + ")").replace("?C","(" + c + ")");
    }

    private static int count(de.regelsuche.ast.Expr expression) {
        return expression instanceof de.regelsuche.ast.BinaryExpr b ? 1 + count(b.left()) + count(b.right()) : 1;
    }

    private static CheckedSchemaTaskSolver.Task task(String source, int nodes) { return task(source,nodes,12); }

    private static CheckedSchemaTaskSolver.Task task(String source, int nodes, int depth) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), new MoveSearch.Budget(32,depth,200_000,512,8192));
    }
}
