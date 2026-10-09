package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointPlanSearch;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed bridge to the existing general algebra catalog, not a second solver.
 * Rules supply proposals at arbitrary graph positions. JointPlanSearch searches
 * their compositions and SemanticChecker independently checks Java semantics.
 * No Java class, method, variable name or target formula selects a task.
 */
final class CoreAlgebraCandidates {
    private static final int MAX_ISLAND_NODES = 64;
    private static final int MAX_RESULT_NODES = 256;
    private final OptimizationRequest request;
    private final VerificationWork work;
    private final List<RewriteRule> rules;

    CoreAlgebraCandidates(OptimizationRequest request, VerificationWork work) {
        this(request, work, AstRewriteTransformationEngine.defaultRules());
    }

    CoreAlgebraCandidates(OptimizationRequest request, VerificationWork work, List<RewriteRule> rules) {
        this.request = request;
        this.work = work;
        this.rules = List.copyOf(rules);
    }

    JointPlanSearch.Generation generate(JointComputationPlan source, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("POSITIVE_CANDIDATE_LIMIT_REQUIRED");
        long start = work.used();
        List<Expr> outputs = source.outputExpressions();
        var sites = new ArrayList<Expr>();
        Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Expr output : outputs) collect(output, sites, visited, 0);
        var proposals = new ArrayList<JointPlanSearch.Proposal>();
        var seen = new HashSet<List<Expr>>();
        for (Expr site : sites) {
            NumericKind kind = JavaExpressions.kindOf(site, source.inputs());
            if (kind != NumericKind.INT && kind != NumericKind.LONG && kind != NumericKind.BIG_INTEGER) continue;
            var bridge = new Bridge(kind);
            Expr algebra;
            try {
                algebra = bridge.lift(site, 0);
            } catch (OutsideBridge unsupported) {
                continue;
            }
            // An opaque operation is not reinterpreted as arithmetic over a field.
            if (!(algebra instanceof BinaryExpr)) continue;
            for (RewriteRule rule : rules) {
                work.charge(Math.max(1, bridge.nodes));
                Expr changedSite;
                try {
                    if (!rule.matches(algebra) || !rule.assumptions(algebra).isEmpty()) continue;
                    Expr transformed = rule.apply(algebra);
                    if (transformed.equals(algebra)) continue;
                    bridge.loweredNodes = 0;
                    changedSite = bridge.lower(transformed, 0);
                } catch (OutsideBridge | IllegalArgumentException | ArithmeticException unsupported) {
                    continue;
                }
                if (changedSite.equals(site)) continue;
                Map<Expr, Expr> memo = new IdentityHashMap<>();
                var changed = new ArrayList<Expr>();
                for (Expr output : outputs) changed.add(replace(output, site, changedSite, memo, 0));
                if (!seen.add(changed) || changed.equals(outputs)) continue;
                if (proposals.size() == maximum)
                    return new JointPlanSearch.Generation(proposals, Math.max(1, work.used() - start), false);
                proposals.add(new JointPlanSearch.Proposal("core-algebra:" + rule.id(), source.withOutputs(changed).expression()));
            }
        }
        return new JointPlanSearch.Generation(proposals, Math.max(1, work.used() - start), true);
    }

    private void collect(Expr expression, List<Expr> sites, Set<Expr> visited, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("GENERATION_STRUCTURAL_BOUND");
        if (!visited.add(expression) || expression instanceof VariableExpr || JavaExpressions.isLiteral(expression)) return;
        // Outer algebra often exposes cancellations that a leaf-only simplifier misses.
        sites.add(expression);
        for (Expr child : JavaExpressions.operands(expression)) collect(child, sites, visited, depth + 1);
    }

    private Expr replace(Expr expression, Expr original, Expr replacement, Map<Expr, Expr> memo, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("GENERATION_STRUCTURAL_BOUND");
        Expr known = memo.get(expression);
        if (known != null) return known;
        Expr result;
        if (expression.equals(original)) result = replacement;
        else if (expression instanceof VariableExpr || JavaExpressions.isLiteral(expression)) result = expression;
        else {
            var children = new ArrayList<Expr>();
            for (Expr child : JavaExpressions.operands(expression)) children.add(replace(child, original, replacement, memo, depth + 1));
            result = new FunctionExpr(((FunctionExpr) expression).name(), children);
        }
        memo.put(expression, result);
        return result;
    }

    private final class Bridge {
        private final NumericKind kind;
        private final Map<Expr, VariableExpr> atoms = new LinkedHashMap<>();
        private final Map<String, Expr> originals = new HashMap<>();
        private int nodes;
        private int loweredNodes;

        Bridge(NumericKind kind) { this.kind = kind; }

        Expr lift(Expr expression, int depth) {
            work.charge(1);
            if (++nodes > MAX_ISLAND_NODES || depth > 64) throw new OutsideBridge();
            if (JavaExpressions.kindOf(expression, request.plan().inputs()) != kind) return atom(expression);
            if (JavaExpressions.isLiteral(expression)) {
                Object value = JavaExpressions.literalValue(expression);
                BigInteger number = value instanceof BigInteger integer ? integer : BigInteger.valueOf(((Number) value).longValue());
                if (number.abs().bitLength() > 4096) throw new OutsideBridge();
                return new NumberExpr(ExactRational.integer(number));
            }
            NumericOperation operation = JavaExpressions.operationOf(expression).orElse(null);
            if (operation == NumericOperation.NEGATE)
                return new BinaryExpr(new NumberExpr(ExactRational.ZERO), BinaryOperator.SUB,
                        lift(JavaExpressions.operands(expression).getFirst(), depth + 1));
            BinaryOperator operator = operation == NumericOperation.ADD ? BinaryOperator.ADD
                    : operation == NumericOperation.SUBTRACT ? BinaryOperator.SUB
                    : operation == NumericOperation.MULTIPLY ? BinaryOperator.MUL : null;
            if (operator == null) return atom(expression);
            var operands = JavaExpressions.operands(expression);
            return new BinaryExpr(lift(operands.getFirst(), depth + 1), operator, lift(operands.get(1), depth + 1));
        }

        private Expr atom(Expr expression) {
            return atoms.computeIfAbsent(expression, value -> {
                String id = "atom" + atoms.size();
                originals.put(id, value);
                return new VariableExpr(id);
            });
        }

        Expr lower(Expr expression, int depth) {
            work.charge(1);
            if (++loweredNodes > MAX_RESULT_NODES || depth > 64) throw new OutsideBridge();
            if (expression instanceof VariableExpr variable) {
                Expr original = originals.get(variable.name());
                if (original == null) throw new OutsideBridge();
                return original;
            }
            if (expression instanceof NumberExpr number) {
                if (!number.value().denominator().equals(BigInteger.ONE)) throw new OutsideBridge();
                return literal(number.value().numerator());
            }
            if (!(expression instanceof BinaryExpr binary)) throw new OutsideBridge();
            NumericOperation operation = switch (binary.operator()) {
                case ADD -> NumericOperation.ADD;
                case SUB -> NumericOperation.SUBTRACT;
                case MUL -> NumericOperation.MULTIPLY;
                default -> null;
            };
            if (operation != null) return JavaExpressions.operation(kind, operation,
                    lower(binary.left(), depth + 1), lower(binary.right(), depth + 1));
            // This is numeric representation lowering, not a target rewrite recipe.
            if (binary.operator() == BinaryOperator.POW && binary.right() instanceof NumberExpr exponent
                    && exponent.value().denominator().equals(BigInteger.ONE)
                    && exponent.value().numerator().signum() >= 0
                    && exponent.value().numerator().compareTo(BigInteger.valueOf(32)) <= 0) {
                int power = exponent.value().numerator().intValueExact();
                Expr factor = lower(binary.left(), depth + 1);
                Expr result = literal(BigInteger.ONE);
                while (power > 0) {
                    work.charge(1);
                    if ((power & 1) != 0) result = JavaExpressions.operation(kind, NumericOperation.MULTIPLY, result, factor);
                    power >>>= 1;
                    if (power > 0) factor = JavaExpressions.operation(kind, NumericOperation.MULTIPLY, factor, factor);
                }
                return result;
            }
            throw new OutsideBridge();
        }

        private Expr literal(BigInteger value) {
            if (value.abs().bitLength() > 4096) throw new OutsideBridge();
            return switch (kind) {
                case INT -> JavaExpressions.literal(value.intValue());
                case LONG -> JavaExpressions.literal(value.longValue());
                case BIG_INTEGER -> JavaExpressions.literal(value);
                default -> throw new OutsideBridge();
            };
        }
    }

    private static final class OutsideBridge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
