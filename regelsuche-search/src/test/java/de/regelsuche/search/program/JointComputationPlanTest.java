package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class JointComputationPlanTest {
    private static final ComputationBackend.Type INTEGER = new ComputationBackend.Type("integer", BigInteger.class);
    private static final ComputationBackend.Type TEXT = new ComputationBackend.Type("text", String.class);

    // Removing structural interning must execute expensive twice and fail this test.
    @Test void sharedOperationsExecuteOnceAcrossExplicitlyNamedOutputs() {
        var backend = new Arithmetic();
        Expr square = call("square", variable("x"));
        var plan = new JointComputationPlan(Map.of("x", INTEGER), Map.of(), List.of(
            new JointComputationPlan.Output("first", INTEGER, square),
            new JointComputationPlan.Output("second", INTEGER, call("add", square, variable("x"))),
            new JointComputationPlan.Output("label", TEXT, call("text", square))));
        var prepared = plan.prepare(backend);
        assertEquals(Map.of("first", BigInteger.valueOf(9), "second", BigInteger.valueOf(12), "label", "9"),
            prepared.execute(Map.of("x", BigInteger.valueOf(3))));
        assertEquals(1, backend.squares);
        assertEquals(3, prepared.cost().operationCount());
        assertEquals(12, prepared.cost().operationWork());
        assertEquals(3, prepared.cost().outputBindings());
        assertEquals(3, prepared.cost().retainedOutputStorage());
        assertEquals(3, prepared.cost().peakLiveStorage());
        assertEquals(9, prepared.execute(Map.of("x", BigInteger.valueOf(3))).get("first") instanceof BigInteger n ? n.intValue() : -1);
        assertEquals(2, backend.squares, "sharing is per execution, not a stale value cache");
    }

    @Test void outputsKeepValuesAliveWhileDeadIntermediatesAreReleased() {
        var backend = new Arithmetic();
        Expr square = call("square", variable("x"));
        Expr twice = call("add", square, square);
        var prepared = new JointComputationPlan(Map.of("x", INTEGER), Map.of(), List.of(
            new JointComputationPlan.Output("answer", INTEGER, call("textlength", call("text", twice))))).prepare(backend);
        assertEquals(2, prepared.cost().peakLiveStorage());
        assertEquals(1, prepared.cost().retainedOutputStorage());
        assertEquals(BigInteger.valueOf(2), prepared.execute(Map.of("x", BigInteger.valueOf(3))).get("answer"));
    }

    @Test void unboundOutputsCyclesAndTypeMismatchesCannotBePrepared() {
        var backend = new Arithmetic();
        assertThrows(IllegalArgumentException.class, () -> plan(variable("missing")).prepare(backend));
        assertThrows(IllegalArgumentException.class, () -> plan(call("add", call("text", variable("x")), variable("x"))).prepare(backend));
        var cyclic = new JointComputationPlan(Map.of("x", INTEGER), Map.of("a", variable("b"), "b", variable("a")),
            List.of(new JointComputationPlan.Output("out", INTEGER, variable("a"))));
        assertThrows(IllegalArgumentException.class, () -> cyclic.prepare(backend));
        var wrongOutput = new JointComputationPlan(Map.of("x", INTEGER), Map.of(),
            List.of(new JointComputationPlan.Output("out", TEXT, variable("x"))));
        assertThrows(IllegalArgumentException.class, () -> wrongOutput.prepare(backend));
        assertThrows(IllegalArgumentException.class, () -> plan(variable("x")).prepare(backend).execute(Map.of("x", "wrong")));
    }

    @Test void namedDefinitionsAreResolvedAndThePreparedPlanIsImmutable() {
        var definitions = new LinkedHashMap<String, Expr>();
        definitions.put("t", call("square", variable("x")));
        var plan = new JointComputationPlan(Map.of("x", INTEGER), definitions, List.of(
            new JointComputationPlan.Output("first", INTEGER, variable("t")),
            new JointComputationPlan.Output("second", INTEGER, call("add", variable("t"), variable("t")))));
        definitions.put("t", new NumberExpr(0));
        assertEquals(plan.expression(), plan.withExpression(plan.expression()).expression());
        var backend = new Arithmetic();
        assertEquals(Map.of("first", BigInteger.valueOf(16), "second", BigInteger.valueOf(32)),
            plan.prepare(backend).execute(Map.of("x", BigInteger.valueOf(4))));
        assertEquals(1, backend.squares);
        assertThrows(UnsupportedOperationException.class, () -> plan.outputs().clear());
        assertThrows(IllegalArgumentException.class, () -> plan.withExpression(call("jointoutputs",
            call("jointoutput", variable("renamed"), variable("x")))));
    }

    private static JointComputationPlan plan(Expr expression) {
        return new JointComputationPlan(Map.of("x", INTEGER), Map.of(), List.of(new JointComputationPlan.Output("out", INTEGER, expression)));
    }
    private static VariableExpr variable(String name) { return new VariableExpr(name); }
    private static FunctionExpr call(String name, Expr... arguments) { return new FunctionExpr(name, List.of(arguments)); }

    private static final class Arithmetic implements ComputationBackend {
        private int squares;
        @Override public Type literalType(NumberExpr literal) { return INTEGER; }
        @Override public Object literal(NumberExpr literal) { return literal.value().numerator(); }
        @Override public Operation operation(Expr expression) {
            var f = (FunctionExpr) expression;
            return switch (f.name()) {
                case "square" -> new Operation("square", List.of(INTEGER), INTEGER, 10, 1);
                case "add" -> new Operation("add", List.of(INTEGER, INTEGER), INTEGER, 1, 1);
                case "text" -> new Operation("text", List.of(INTEGER), TEXT, 1, 1);
                case "textlength" -> new Operation("textlength", List.of(TEXT), INTEGER, 1, 1);
                default -> throw new IllegalArgumentException("unsupported operation");
            };
        }
        @Override public Object apply(Operation operation, List<Object> arguments) {
            return switch (operation.id()) {
                case "square" -> { squares++; yield ((BigInteger) arguments.getFirst()).pow(2); }
                case "add" -> ((BigInteger) arguments.getFirst()).add((BigInteger) arguments.get(1));
                case "text" -> arguments.getFirst().toString();
                case "textlength" -> BigInteger.valueOf(((String) arguments.getFirst()).length());
                default -> throw new IllegalArgumentException("unsupported operation");
            };
        }
    }
}
