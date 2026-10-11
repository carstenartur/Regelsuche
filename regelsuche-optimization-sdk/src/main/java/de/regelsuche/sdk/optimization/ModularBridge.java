package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;

/** Type-preserving bridge to the existing affine modular generator and independent checker. */
final class ModularBridge {
    // The existing affine checker admits coefficients of at most 4096 bits.
    // Check the distance before allocating 2^distance, independently of Java's size gate.
    private static final int MAX_SHIFT_DISTANCE = 4095;
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
                Expr modularReplacement = rewrite.outputs().get(index);
                // Transport normalizes shifts and recognizes their inverse. Do not
                // let that proof view rewrite an unrelated, unchanged Java output.
                if (modularReplacement.equals(modular.get(index))) continue;
                Expr replacement = restore(modularReplacement, expressions.get(indices.get(index)), modular.get(index));
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
        long inspections = inspectionSize(source) + inspectionSize(target);
        var proof = domain.verifyEquivalent(List.of(toModular(source)), List.of(toModular(target)));
        return new ModularComputationDomain.Verification(proof.accepted(), inspections + proof.work());
    }

    /** Retain the original typed Java context at every unchanged branch. A
     * modular rewrite may occur beside an exact product with the same proof
     * expression as a shift; structural equality of their values must not lose
     * that distinction outside the reducing context.
     */
    private static Expr restore(Expr replacement, Expr original, Expr transported) {
        if (replacement.equals(transported)) return original;
        if (original instanceof FunctionExpr function) {
            boolean shift = JavaExpressions.operationOf(original).orElse(null) == NumericOperation.SHIFT_LEFT;
            // A zero shift vanished from the proof view. Keep its Java context
            // when the rewrite changes only the base inside that source operation.
            if (shift && (Integer) JavaExpressions.literalValue(function.arguments().get(1)) == 0)
                return JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.SHIFT_LEFT,
                        restore(replacement, function.arguments().getFirst(), transported), function.arguments().get(1));
            boolean matching = transported instanceof BinaryExpr beforeBinary && replacement instanceof BinaryExpr afterBinary
                    && beforeBinary.operator() == afterBinary.operator()
                    || transported instanceof FunctionExpr beforeFunction && replacement instanceof FunctionExpr afterFunction
                    && beforeFunction.name().equals(afterFunction.name()) && beforeFunction.arguments().size() == afterFunction.arguments().size();
            if (matching) {
                var before = modularOperands(transported); var after = modularOperands(replacement);
                if (function.arguments().size() == before.size() && (!shift || before.get(1).equals(after.get(1)))) {
                    var restored = new ArrayList<Expr>();
                    for (int i = 0; i < before.size(); i++) restored.add(restore(after.get(i), function.arguments().get(i), before.get(i)));
                    return new FunctionExpr(function.name(), restored);
                }
            }
        }
        return fromModular(replacement);
    }
    private static List<Expr> modularOperands(Expr expression) {
        return expression instanceof BinaryExpr binary ? List.of(binary.left(), binary.right()) : JavaExpressions.operands(expression);
    }
    static Expr toModular(Expr expression) {
        if (expression instanceof VariableExpr || expression instanceof NumberExpr) return expression;
        if (JavaExpressions.resultKind(expression) != NumericKind.BIG_INTEGER) throw new IllegalArgumentException("MODULAR_BIG_INTEGER_REQUIRED");
        if (JavaExpressions.operationOf(expression).orElse(null) == NumericOperation.SHIFT_LEFT) {
            var arguments = JavaExpressions.operands(expression);
            if (arguments.size() != 2 || !JavaExpressions.isLiteral(arguments.get(1))
                    || JavaExpressions.resultKind(arguments.get(1)) != NumericKind.INT)
                throw new IllegalArgumentException("MODULAR_SHIFT_REQUIRES_CONSTANT_INT_DISTANCE");
            int distance = (Integer) JavaExpressions.literalValue(arguments.get(1));
            if (distance < 0 || distance > MAX_SHIFT_DISTANCE)
                throw new IllegalArgumentException("MODULAR_SHIFT_DISTANCE_OUTSIDE_BOUND");
            Expr base = toModular(arguments.getFirst());
            // BigInteger shifts multiply signed integers, with no Java word masking.
            // The original trace and both Java magnitude proofs remain authoritative.
            return distance == 0 ? base : new BinaryExpr(base, BinaryOperator.MUL,
                    JavaExpressions.literal(BigInteger.ONE.shiftLeft(distance)));
        }
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
            case MOD -> new FunctionExpr("mod", args);
            case MOD_POW -> new FunctionExpr("modpow", args);
            case MOD_MULTIPLY -> new FunctionExpr("modmul", args);
            default -> throw new IllegalArgumentException("OUTSIDE_MODULAR_FRAGMENT");
        };
    }
    static Expr fromModular(Expr expression) {
        if (expression instanceof VariableExpr || expression instanceof NumberExpr) return expression;
        if (expression instanceof BinaryExpr b) {
            if (b.operator() == BinaryOperator.MUL && b.right() instanceof NumberExpr number && number.value().isInteger()) {
                BigInteger coefficient = number.value().numerator();
                int distance = coefficient.getLowestSetBit();
                if (coefficient.signum() > 0 && distance > 0 && distance <= MAX_SHIFT_DISTANCE && coefficient.bitCount() == 1)
                    return JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.SHIFT_LEFT,
                            fromModular(b.left()), JavaExpressions.literal(distance));
            }
            return JavaExpressions.operation(NumericKind.BIG_INTEGER, switch(b.operator()) {
                case ADD -> NumericOperation.ADD; case SUB -> NumericOperation.SUBTRACT; case MUL -> NumericOperation.MULTIPLY;
                default -> throw new IllegalArgumentException("OUTSIDE_MODULAR_FRAGMENT");
            }, fromModular(b.left()), fromModular(b.right()));
        }
        var f = (FunctionExpr)expression;
        return JavaExpressions.operation(NumericKind.BIG_INTEGER, switch(f.name()) {
            case "mod" -> NumericOperation.MOD; case "modpow" -> NumericOperation.MOD_POW; case "modmul" -> NumericOperation.MOD_MULTIPLY;
            default -> throw new IllegalArgumentException("OUTSIDE_MODULAR_FRAGMENT");
        }, f.arguments().stream().map(ModularBridge::fromModular).toArray(Expr[]::new));
    }
}
