package de.regelsuche.example.modular;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.search.program.*;
import java.math.BigInteger;
import java.util.*;

/** Wiring only: the general plan/frontier is in search and modular semantics in math-algorithms. */
public final class ModularJointPlans implements ComputationBackend, JointPlanSearch.Domain {
    public static final Type INTEGER = new Type("integer", BigInteger.class);
    private final ModularComputationDomain semantics;
    public ModularJointPlans(ModularComputationDomain semantics) { this.semantics = Objects.requireNonNull(semantics); }
    public JointComputationPlan plan(Map<String, Expr> outputs, Set<String> inputNames) {
        var inputs = new LinkedHashMap<String, Type>();
        inputNames.stream().sorted().forEach(name -> inputs.put(name, INTEGER));
        return new JointComputationPlan(inputs, Map.of(), outputs.entrySet().stream()
            .map(entry -> new JointComputationPlan.Output(entry.getKey(), INTEGER, entry.getValue())).toList());
    }
    public JointPlanSearch optimizer(int maximumCandidates) {
        return new JointPlanSearch(this, this, JointPlanSearch.Weights.DEFAULT, maximumCandidates);
    }
    @Override public Type literalType(NumberExpr literal) {
        if (!literal.value().isInteger()) throw new IllegalArgumentException("integer literal required");
        return INTEGER;
    }
    @Override public Object literal(NumberExpr literal) { literalType(literal); return literal.value().numerator(); }
    @Override public Operation operation(Expr expression) {
        String id;
        long work = 1;
        int arity = 2;
        if (expression instanceof BinaryExpr binary) id = switch (binary.operator()) {
            case ADD -> "add"; case SUB -> "sub"; case MUL -> "mul";
            default -> throw new IllegalArgumentException("unsupported integer operation");
        };
        else if (expression instanceof FunctionExpr function && (function.name().equals("modpow") || function.name().equals("modmul"))) {
            id = function.name(); arity = 3;
            // Declared static estimates, not timing claims. Small literal exponents have a bounded bit-cost.
            work = id.equals("modmul") ? 10 : 1_000;
            if (id.equals("modpow") && function.arguments().size() == 3 && function.arguments().get(1) instanceof NumberExpr n
                    && n.value().isInteger() && n.value().signum() >= 0) work = Math.max(1, 2L * n.value().numerator().bitLength());
        } else throw new IllegalArgumentException("unsupported modular operation");
        return new Operation(id, Collections.nCopies(arity, INTEGER), INTEGER, work, 1);
    }
    @Override public Object apply(Operation operation, List<Object> arguments) {
        return semantics.evaluate(operation.id(), arguments.stream().map(BigInteger.class::cast).toList());
    }
    @Override public void validateInputs(Map<String, Object> inputs) { semantics.validateInputs(inputs); }
    @Override public String revision() { return ModularComputationDomain.REVISION; }
    @Override public JointPlanSearch.Generation generate(JointComputationPlan source, int maximumCandidates) {
        var resolved = source.resolveOutputs();
        var generated = semantics.generate(resolved.expressions(), maximumCandidates);
        long work = Math.addExact(resolved.work(), generated.work());
        var proposals = new ArrayList<JointPlanSearch.Proposal>();
        for (var rewrite : generated.rewrites()) {
            var target = source.withOutputs(rewrite.outputs()).searchExpression();
            work = Math.addExact(work, target.work());
            proposals.add(new JointPlanSearch.Proposal(rewrite.rule(), target.expression()));
        }
        return new JointPlanSearch.Generation(proposals, work, generated.complete());
    }
    @Override public JointPlanSearch.Verification verify(JointComputationPlan source, JointComputationPlan target) {
        var left = source.resolveOutputs();
        var right = target.resolveOutputs();
        var verified = semantics.verifyEquivalent(left.expressions(), right.expressions());
        return new JointPlanSearch.Verification(verified.accepted(), Math.addExact(verified.work(), Math.addExact(left.work(), right.work())));
    }
}
