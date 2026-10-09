package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import java.math.BigInteger;
import java.util.*;

/** Bounded proposals only. SemanticChecker is the authority, not rule labels or this generator. */
final class JavaCandidateGenerator {
    static final String REVISION = "java-general-algebra-proposals/v8";
    private final OptimizationRequest request;
    private final VerificationWork work;
    private final JavaNumericBackend backend;
    private final CoreAlgebraCandidates algebra;
    private final RuntimeInputScope runtime;
    private boolean skippedConstantFold;
    boolean skippedConstantFold() { return skippedConstantFold; }
    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work) {
        this(request,work,false);
    }
    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work, boolean runtimeOnly) {
        this.request = request; this.work = work; backend = new JavaNumericBackend(request.plan().inputs());
        runtime = runtimeOnly ? new RuntimeInputScope(work) : null;
        algebra = new CoreAlgebraCandidates(request, work, runtimeOnly);
    }
    JointPlanSearch.Generation generate(JointComputationPlan source, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("POSITIVE_CANDIDATE_LIMIT_REQUIRED");
        long start = work.used(); work.charge(1);
        var outputs = source.outputExpressions();
        var proposals = new ArrayList<JointPlanSearch.Proposal>();
        var seen = new HashSet<List<Expr>>();
        var simplified = outputs.stream().map(expression -> simplify(expression, 0)).toList();
        addProposal(source, outputs, simplified, "typed-local-algebra", proposals, seen);

        // Local numeric rules are additional proposals, not the complete math engine.
        var nodes = new ArrayList<Expr>();
        Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Expr output : outputs) collect(output, nodes, visited, 0);
        boolean complete = true;
        int localLimit = Math.max(1, maximum / 2);
        for (Expr node : nodes) {
            Expr local = simplifyHere(node);
            if (node.equals(local)) continue;
            var memo = new IdentityHashMap<Expr, Expr>();
            var changed = outputs.stream().map(output -> replace(output, node, local, memo, 0)).toList();
            if (seen.contains(changed)) continue;
            if (proposals.size() >= localLimit) { complete = false; break; }
            addProposal(source, outputs, changed, "typed-local-algebra-at-subexpression", proposals, seen);
        }
        if (proposals.size() < maximum) {
            var generated = algebra.generate(source, maximum - proposals.size());
            complete &= generated.complete();
            for (var proposal : generated.proposals()) {
                var candidate = source.withExpression(proposal.expression());
                addProposal(source, outputs, candidate.outputExpressions(), proposal.rule(), proposals, seen);
            }
        } else complete = false;
        if (proposals.size() < maximum) {
            try {
                var modular = new ModularBridge(request.assumptions()).generate(outputs, maximum - proposals.size());
                work.charge(modular.work()); complete &= modular.complete();
                for (var rewrite : modular.rewrites())
                    addProposal(source, outputs, rewrite.outputs(), rewrite.rule(), proposals, seen);
            } catch (IllegalArgumentException outsideModularFragment) { /* Other mathematical proposals remain available. */ }
        } else complete = false;
        return new JointPlanSearch.Generation(proposals, Math.max(1, work.used() - start), complete);
    }
    private void addProposal(JointComputationPlan source, List<Expr> original, List<Expr> changed, String rule,
                             List<JointPlanSearch.Proposal> proposals, Set<List<Expr>> seen) {
        work.charge(1);
        if (runtime != null) {
            var retained = new ArrayList<>(changed);
            for (int i=0;i<original.size();i++)
                if (!runtime.depends(original.get(i))) retained.set(i,original.get(i));
            changed = retained;
        }
        if (!changed.equals(original) && seen.add(changed))
            proposals.add(new JointPlanSearch.Proposal(rule, source.withOutputs(changed).expression()));
    }
    private void collect(Expr expression, List<Expr> nodes, Set<Expr> visited, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("GENERATION_STRUCTURAL_BOUND");
        if (!visited.add(expression) || JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) return;
        for (Expr child : JavaExpressions.operands(expression)) collect(child, nodes, visited, depth + 1);
        nodes.add(expression);
    }
    private Expr replace(Expr expression, Expr original, Expr replacement, Map<Expr, Expr> memo, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("GENERATION_STRUCTURAL_BOUND");
        Expr cached = memo.get(expression);
        if (cached != null) return cached;
        Expr result;
        if (expression.equals(original)) result = replacement;
        else if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) result = expression;
        else result = new FunctionExpr(((FunctionExpr) expression).name(), JavaExpressions.operands(expression).stream()
                .map(child -> replace(child, original, replacement, memo, depth + 1)).toList());
        memo.put(expression, result);
        return result;
    }
    private Expr simplify(Expr expression, int depth) {
        work.charge(1);
        if (runtime != null && !runtime.depends(expression)) return expression;
        if (depth > 128) throw new IllegalArgumentException("GENERATION_STRUCTURAL_BOUND");
        if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) return expression;
        var arguments = JavaExpressions.operands(expression).stream().map(child -> simplify(child, depth + 1)).toList();
        return simplifyHere(new FunctionExpr(((FunctionExpr) expression).name(), arguments));
    }
    private Expr simplifyHere(Expr result) {
        work.charge(1);
        if (runtime != null && !runtime.depends(result)) return result;
        if (JavaExpressions.isLiteral(result) || result instanceof VariableExpr) return result;
        var arguments = JavaExpressions.operands(result);
        var optional = JavaExpressions.operationOf(result);
        if (optional.isEmpty()) return result;
        NumericOperation op = optional.get(); NumericKind kind = JavaExpressions.resultKind(result);
        if (op.name().endsWith("EXACT")) return result;
        if (kind.floatingPoint() && request.safetyProfile() == SafetyProfile.PRESERVE_JAVA) return floating(result, op, arguments);
        return simplifyAdditive(result, op, kind, arguments);
    }
    private Expr simplifyAdditive(Expr result, NumericOperation op, NumericKind kind, List<Expr> arguments) {
        Expr left = arguments.getFirst(); Expr right = arguments.size() > 1 ? arguments.get(1) : null;
        if (op == NumericOperation.NEGATE && operation(left) == NumericOperation.NEGATE) return children(left).getFirst();
        if (op == NumericOperation.ADD) {
            if (number(right, 0)) return left; if (number(left, 0)) return right;
            if (operation(left) == NumericOperation.SUBTRACT && children(left).get(1).equals(right)) return children(left).getFirst();
            if (operation(right) == NumericOperation.SUBTRACT && children(right).get(1).equals(left)) return children(right).getFirst();
            Expr factored = factor(kind, left, right); if (factored != null) return factored;
        }
        if (op == NumericOperation.SUBTRACT) {
            if (number(right, 0)) return left; if (left.equals(right)) return constant(kind, 0);
            if (operation(left) == NumericOperation.ADD) {
                if (children(left).get(1).equals(right)) return children(left).getFirst();
                if (children(left).getFirst().equals(right)) return children(left).get(1);
            }
        }
        return simplifyProductsAndBitwise(result, op, kind, arguments, left, right);
    }
    private Expr simplifyProductsAndBitwise(Expr result, NumericOperation op, NumericKind kind,
                                           List<Expr> arguments, Expr left, Expr right) {
        if (op == NumericOperation.MULTIPLY) {
            if (number(right, 1)) return left; if (number(left, 1)) return right;
            if (number(right, 0) || number(left, 0)) return constant(kind, 0);
        }
        if (op == NumericOperation.DIVIDE) {
            if (number(right, 1)) return left;
            if (operation(left) == NumericOperation.MULTIPLY && JavaExpressions.isLiteral(right) && !number(right, 0)) {
                if (children(left).get(1).equals(right)) return children(left).getFirst();
                if (children(left).getFirst().equals(right)) return children(left).get(1);
            }
        }
        if ((op == NumericOperation.XOR || op == NumericOperation.OR) && number(right, 0)) return left;
        if (op == NumericOperation.XOR && left.equals(right)) return constant(kind, 0);
        if ((op == NumericOperation.OR || op == NumericOperation.AND) && left.equals(right)) return left;
        if (op == NumericOperation.OR || op == NumericOperation.XOR) {
            work.charge(8);
            Expr bitwise = JavaBitwiseCandidates.simplify(result, op, kind, arguments);
            if (!bitwise.equals(result)) return bitwise;
        }
        return foldConstant(result, op, kind, arguments);
    }
    private Expr foldConstant(Expr result, NumericOperation op, NumericKind kind, List<Expr> arguments) {
        if (arguments.stream().allMatch(JavaExpressions::isLiteral)) {
            if (!boundedConstantFold(kind, op, arguments)) { skippedConstantFold = true; return result; }
            work.charge(Math.max(1, arguments.size()));
            try { return literalValue(kind, backend.apply(backend.operation(result), arguments.stream().map(JavaExpressions::literalValue).toList())); }
            catch (ArithmeticException | IllegalArgumentException unsupported) { return result; }
        }
        return result;
    }
    private static boolean boundedConstantFold(NumericKind kind, NumericOperation operation, List<Expr> arguments) {
        if (kind != NumericKind.BIG_INTEGER) return true;
        if (EnumSet.of(NumericOperation.POW, NumericOperation.MOD_POW, NumericOperation.MOD_MULTIPLY,
                NumericOperation.SHIFT_LEFT, NumericOperation.SHIFT_RIGHT).contains(operation)) return false;
        return arguments.stream().map(JavaExpressions::literalValue)
            .allMatch(value -> value instanceof BigInteger integer && integer.abs().bitLength() <= 256);
    }
    private static Expr floating(Expr result, NumericOperation op, List<Expr> args) {
        Expr left = args.getFirst();
        if (op == NumericOperation.NEGATE && operation(left) == NumericOperation.NEGATE) return children(left).getFirst();
        if (op == NumericOperation.MULTIPLY || op == NumericOperation.DIVIDE) {
            if (StrictFloatingProof.isNumber(args.get(1), 1.0, false)) return left;
            if (op == NumericOperation.MULTIPLY && StrictFloatingProof.isNumber(left, 1.0, false)) return args.get(1);
        }
        if (op == NumericOperation.SUBTRACT && StrictFloatingProof.isNumber(args.get(1), 0.0, false)) return left;
        if (op == NumericOperation.ADD) {
            if (StrictFloatingProof.isNumber(args.get(1), 0.0, true)) return left;
            if (StrictFloatingProof.isNumber(left, 0.0, true)) return args.get(1);
        }
        return result;
    }
    private static Expr factor(NumericKind kind, Expr left, Expr right) {
        if (operation(left) != NumericOperation.MULTIPLY || operation(right) != NumericOperation.MULTIPLY) return null;
        var a = children(left); var b = children(right);
        for (int i=0;i<2;i++) for (int j=0;j<2;j++) if (a.get(i).equals(b.get(j)))
            return JavaExpressions.operation(kind, NumericOperation.MULTIPLY, a.get(i),
                JavaExpressions.operation(kind, NumericOperation.ADD, a.get(1-i), b.get(1-j)));
        return null;
    }
    private static List<Expr> children(Expr expression) { return JavaExpressions.operands(expression); }
    private static NumericOperation operation(Expr expression) { return JavaExpressions.operationOf(expression).orElse(null); }
    private static boolean number(Expr expression, long value) {
        if (expression == null || !JavaExpressions.isLiteral(expression)) return false;
        Object literal = JavaExpressions.literalValue(expression);
        if (literal instanceof BigInteger b) return b.equals(BigInteger.valueOf(value));
        return literal instanceof Number n && n.doubleValue() == value;
    }
    private static Expr constant(NumericKind kind, long value) { return literalValue(kind, switch(kind) {
        case BIG_INTEGER -> BigInteger.valueOf(value); case FLOAT -> (float)value; case DOUBLE -> (double)value;
        case LONG -> value; default -> (int)value;
    }); }
    private static Expr literalValue(NumericKind kind, Object value) {
        return switch(kind) {
            case BIG_INTEGER -> JavaExpressions.literal((BigInteger)value); case INT -> JavaExpressions.literal(((Number)value).intValue());
            case LONG -> JavaExpressions.literal(((Number)value).longValue()); case FLOAT -> JavaExpressions.literal(((Number)value).floatValue());
            case DOUBLE -> JavaExpressions.literal(((Number)value).doubleValue());
            default -> throw new IllegalArgumentException("PROMOTED_LITERAL_REQUIRED");
        };
    }
}
