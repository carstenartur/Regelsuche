package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;

/** Separate killable JVM fixture: a recursive hash regression must not stall the test worker. */
public final class JointComputationDagProbe {
    static final ComputationBackend.Type INTEGER = new ComputationBackend.Type("integer", BigInteger.class);
    static final ComputationBackend BACKEND = new ComputationBackend() {
        @Override public Type literalType(NumberExpr literal) { return INTEGER; }
        @Override public Object literal(NumberExpr literal) { return literal.value().numerator(); }
        @Override public Operation operation(Expr expression) { return new Operation("add", List.of(INTEGER, INTEGER), INTEGER, 1, 1); }
        @Override public Object apply(Operation operation, List<Object> arguments) {
            return ((BigInteger) arguments.getFirst()).add((BigInteger) arguments.get(1));
        }
    };
    static JointComputationPlan plan(Map<String, Expr> definitions, Expr first, Expr second) {
        return new JointComputationPlan(Map.of("x", INTEGER), definitions, List.of(
            new JointComputationPlan.Output("first", INTEGER, first), new JointComputationPlan.Output("second", INTEGER, second)));
    }
    static JointComputationPlan namedPlan(int depth) {
        var definitions = new LinkedHashMap<String, Expr>();
        for (String prefix : List.of("left", "right")) {
            definitions.put(prefix + "0", new VariableExpr("x"));
            for (int i = 1; i <= depth; i++) definitions.put(prefix + i, new FunctionExpr("add", List.of(
                new VariableExpr(prefix + (i - 1)), new VariableExpr(prefix + (i - 1)))));
        }
        return plan(definitions, new VariableExpr("left" + depth), new VariableExpr("right" + depth));
    }
    public static void main(String[] ignored) {
        var prepared = namedPlan(48).prepare(BACKEND);
        var values = prepared.execute(Map.of("x", BigInteger.ONE));
        if (prepared.cost().operationCount() != 48 || prepared.cost().retainedOutputStorage() != 1
                || !values.get("first").equals(BigInteger.ONE.shiftLeft(48)) || !values.get("first").equals(values.get("second"))
                || prepared.cost().inspectionWork() >= 3_000) throw new AssertionError("shared DAG result/cost differs");
        System.out.println("operations=" + prepared.cost().operationCount() + ", inspections=" + prepared.cost().inspectionWork());
    }
}
