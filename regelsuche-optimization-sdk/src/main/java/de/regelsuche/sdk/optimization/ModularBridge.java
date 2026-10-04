package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import java.util.*;

/** Type-preserving bridge to the existing affine modular generator and independent checker. */
final class ModularBridge {
    private final ModularComputationDomain domain;
    ModularBridge(Set<SemanticAssumption> assumptions) {
        var nonnegative = new HashSet<String>(); var positive = new HashSet<String>();
        var normalized = new HashSet<ModularComputationDomain.NormalizedInput>();
        for (var assumption : assumptions) switch (assumption.kind()) {
            case NON_NEGATIVE -> nonnegative.add(assumption.subject());
            case POSITIVE -> positive.add(assumption.subject());
            case NORMALIZED_MODULAR_INPUT -> normalized.add(new ModularComputationDomain.NormalizedInput(assumption.subject(), assumption.parameter()));
            default -> { }
        }
        domain = new ModularComputationDomain(nonnegative, positive, normalized);
    }
    ModularComputationDomain.Generation generate(List<Expr> expressions, int maximum) {
        var generated = domain.generate(expressions.stream().map(ModularBridge::toModular).toList(), maximum);
        return new ModularComputationDomain.Generation(generated.rewrites().stream().map(rewrite ->
            new ModularComputationDomain.Rewrite(rewrite.rule(), rewrite.outputs().stream().map(ModularBridge::fromModular).toList())).toList(), generated.work(), generated.complete());
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
