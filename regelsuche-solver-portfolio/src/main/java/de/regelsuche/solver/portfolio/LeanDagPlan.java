package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.ScopedProofDag.Local;
import de.regelsuche.solver.ir.ScopedProofDag.Node;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.util.ArrayList;
import java.util.List;

/** Elaborates only fixed constructors and checked local slots. No incoming Lean source is used. */
record LeanDagPlan(ScopedProofDag dag) implements LeanProofPlan {
    static final String FILE = "scoped-proof-dag.txt";
    @Override public String fileName() { return FILE; }
    @Override public String material() { return dag.canonical(); }
    @Override public boolean accepts(Obligation obligation) { return dag.obligation().equals(obligation); }
    @Override public String proof() {
        var parameters = new ArrayList<String>();
        dag.obligation().declarations().forEach(d -> parameters.add("rs_" + d.name()));
        for (int i = 0; i < dag.obligation().assumptions().size(); i++) parameters.add("h" + i);
        String lambda = parameters.isEmpty() ? "" : "fun " + String.join(" ", parameters) + " => ";
        return "\n  -- scoped proof DAG " + hash() + "\n  " + lambda + dag.fold(this::term) + "\n";
    }
    private String term(Node node, List<Local> scope, List<String> children) {
        String next = "l" + scope.size();
        return switch (node.rule()) {
            case DECLARATION -> declaration(node);
            case PREMISE -> "h" + node.operand();
            case LOCAL -> local(scope, node.operand());
            case ZERO -> "(0 : Nat)";
            case SUCC -> "(Nat.succ " + children.getFirst() + ")";
            case INTRO -> "(fun " + next + " => " + children.getFirst() + ")";
            case APPLY, SPECIALIZE -> "(" + children.getFirst() + " " + children.getLast() + ")";
            case LET, HAVE -> "(let " + next + " := " + children.getFirst() + "; " + children.getLast() + ")";
            case UNFOLD -> "(by dsimp only [" + local(scope,node.operand()) + "] at * <;> exact " + children.getFirst() + ")";
            case REWRITE -> "(" + children.getFirst() + " ▸ " + children.getLast() + ")";
            case PAIR -> "(And.intro " + children.getFirst() + " " + children.getLast() + ")";
            case WITNESS -> "(Exists.intro " + children.getFirst() + " " + children.getLast() + ")";
            case CASES -> "(Or.elim " + children.getFirst() + " (fun " + next + " => " + children.get(1)
                + ") (fun " + next + " => " + children.get(2) + "))";
            case BY_CONTRADICTION -> "(Classical.byContradiction (fun " + next + " => " + children.getFirst() + " " + children.getLast() + "))";
            case INDUCTION -> "(Nat.rec (motive := " + declaration(node) + ") " + children.getFirst() + " " + children.getLast() + ")";
            case STRONG_INDUCTION -> "(fun n => Nat.strongRecOn n (fun " + next + " l" + (scope.size()+1) + " => " + children.getFirst() + "))";
            case DECREASE -> "(" + local(scope,scope.indexOf(Local.SMALLER_CASES)) + " " + children.getFirst() + " " + children.getLast() + ")";
            case NORMALIZE -> "(by rfl)";
            case SOLVE_LEAF -> solveLeaf(scope);
        };
    }
    private static String solveLeaf(List<Local> scope) {
        var script = new StringBuilder("(by ");
        for (int i=0;i<scope.size();i++) {
            if (scope.get(i)==Local.SMALLER_CASES) script.append("clear ").append(local(scope,i)).append("; ");
        }
        return script.append("first | assumption | ring | norm_num | omega)").toString();
    }
    private String declaration(Node node) { return "rs_" + dag.obligation().declarations().get(node.operand()).name(); }
    private static String local(List<Local> scope, int index) { return "l" + (scope.size() - 1 - index); }
}
