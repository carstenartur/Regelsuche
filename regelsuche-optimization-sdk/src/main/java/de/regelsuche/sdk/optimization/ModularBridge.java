package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;

/** Type-preserving bridge to the existing affine modular generator and independent checker. */
final class ModularBridge {
    private final ModularComputationDomain domain;
    ModularBridge(Set<SemanticAssumption> assumptions) {
        var nonnegative = new HashSet<String>(); var positive = new HashSet<String>();
        var normalized = new HashSet<ModularComputationDomain.NormalizedInput>();
        for (var assumption : assumptions) switch (assumption.kind()) {
            case NON_NEGATIVE, NON_NEGATIVE_UPPER_BOUND -> nonnegative.add(assumption.subject());
            case POSITIVE -> positive.add(assumption.subject());
            case NORMALIZED_MODULAR_INPUT -> normalized.add(new ModularComputationDomain.NormalizedInput(assumption.subject(), assumption.parameter()));
            default -> { }
        }
        domain = new ModularComputationDomain(nonnegative, positive, normalized);
    }
    ModularComputationDomain.Generation generate(List<Expr> expressions, int maximum) {
        // An unrelated numeric output must not disable modular optimization.
        // Project convertible outputs, then restore their original positions.
        var indices = new ArrayList<Integer>();
        var modular = new ArrayList<Expr>();
        long inspections = 1;
        for (int index = 0; index < expressions.size(); index++) {
            inspections += inspectionSize(expressions.get(index));
            try {
                modular.add(toModular(expressions.get(index)));
                indices.add(index);
            } catch (IllegalArgumentException unsupportedOutput) {
                // Preserve this output verbatim; the whole plan is still verified.
            }
        }
        if (modular.isEmpty()) return new ModularComputationDomain.Generation(List.of(), inspections, true);
        var generated = domain.generate(modular, maximum);
        var rewrites = new ArrayList<ModularComputationDomain.Rewrite>();
        for (var rewrite : generated.rewrites()) {
            var restored = new ArrayList<>(expressions);
            for (int index = 0; index < indices.size(); index++) {
                Expr replacement = fromModular(rewrite.outputs().get(index));
                inspections += inspectionSize(replacement);
                restored.set(indices.get(index), replacement);
            }
            rewrites.add(new ModularComputationDomain.Rewrite(rewrite.rule(), restored));
        }
        return new ModularComputationDomain.Generation(rewrites, inspections + generated.work(), generated.complete());
    }
    private static long inspectionSize(Expr expression) {
        var pending = new ArrayDeque<Expr>();
        pending.add(expression);
        long count = 0;
        while (!pending.isEmpty()) {
            Expr current = pending.removeLast();
            if (++count > JointComputationPlan.MAX_NODES)
                throw new IllegalArgumentException("MODULAR_TRANSPORT_STRUCTURAL_BOUND");
            if (current instanceof FunctionExpr function) pending.addAll(function.arguments());
            else if (current instanceof BinaryExpr binary) { pending.add(binary.left()); pending.add(binary.right()); }
        }
        return count;
    }
    ModularComputationDomain.Verification verify(Expr source, Expr target) {
        return domain.verifyEquivalent(List.of(toModular(source)), List.of(toModular(target)));
    }
    static Expr toModular(Expr expression) {
        if (expression instanceof VariableExpr || expression instanceof NumberExpr) return expression;
        if (JavaExpressions.resultKind(expression) != NumericKind.BIG_INTEGER) throw new IllegalArgumentException("MODULAR_BIG_INTEGER_REQUIRED");
        // Ordinary Java emission spells the existing fused modmul as a.multiply(b).mod(m).
        // The modular checker still independently establishes positivity and matching modulus.
        if (JavaExpressions.operationOf(expression).orElse(null)==NumericOperation.MOD) {
            var original=JavaExpressions.operands(expression);
            if(original.size()==2 && JavaExpressions.operationOf(original.getFirst()).orElse(null)==NumericOperation.MULTIPLY) {
                var product=JavaExpressions.operands(original.getFirst());
                return new FunctionExpr("modmul",List.of(toModular(product.getFirst()),toModular(product.get(1)),toModular(original.get(1))));
            }
        }
        var args = JavaExpressions.operands(expression).stream().map(ModularBridge::toModular).toList();
        return switch (JavaExpressions.operationOf(expression).orElseThrow()) {
            case ADD -> new BinaryExpr(args.getFirst(), BinaryOperator.ADD, args.get(1));
            case SUBTRACT -> new BinaryExpr(args.getFirst(), BinaryOperator.SUB, args.get(1));
            case MULTIPLY -> new BinaryExpr(args.getFirst(), BinaryOperator.MUL, args.get(1));
            case MOD_POW -> new FunctionExpr("modpow", args);
            case MOD_MULTIPLY -> new FunctionExpr("modmul", args);
            default -> throw new IllegalArgumentException("OUTSIDE_MODULAR_FRAGMENT");
        };
    }
    static Expr fromModular(Expr expression) {
        if (expression instanceof VariableExpr || expression instanceof NumberExpr) return expression;
        if (expression instanceof BinaryExpr b) return JavaExpressions.operation(NumericKind.BIG_INTEGER, switch(b.operator()) {
            case ADD -> NumericOperation.ADD; case SUB -> NumericOperation.SUBTRACT; case MUL -> NumericOperation.MULTIPLY;
            default -> throw new IllegalArgumentException("OUTSIDE_MODULAR_FRAGMENT");
        }, fromModular(b.left()), fromModular(b.right()));
        var f = (FunctionExpr)expression;
        return JavaExpressions.operation(NumericKind.BIG_INTEGER, switch(f.name()) {
            case "modpow" -> NumericOperation.MOD_POW; case "modmul" -> NumericOperation.MOD_MULTIPLY;
            default -> throw new IllegalArgumentException("OUTSIDE_MODULAR_FRAGMENT");
        }, f.arguments().stream().map(ModularBridge::fromModular).toArray(Expr[]::new));
    }
}
