package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointPlanSearch;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Representation adapter to the ordinary Regelsuche algebra rule engine. It
 * contains no mathematical rewrite, constructor recognition or target formula.
 * Rational-algebra proposals are never proof of Java arithmetic equivalence:
 * every edge and the final plan still go through SemanticChecker.
 */
final class JavaAlgebraCandidates {
    private final VerificationWork work;
    private final List<RewriteRule> rules;
    private final long expansionAllowance;
    private long expansionWork;
    private boolean exhausted;
    private static final class ExpansionLimit extends RuntimeException { }
    private final Map<Expr, View> views = new IdentityHashMap<>();

    JavaAlgebraCandidates(VerificationWork work, long maximumWork) {
        this(work, AstRewriteTransformationEngine.defaultRules(), maximumWork);
    }

    JavaAlgebraCandidates(VerificationWork work, List<RewriteRule> mathematicalRules, long maximumWork) {
        this.work = work;
        // Reserve most of the request allowance for frontier bookkeeping, all
        // independent checks and final preparation. Exhaustion is not a proof.
        this.expansionAllowance = Math.max(1, maximumWork / 8);
        this.rules = mathematicalRules.stream().<RewriteRule>map(rule -> new LiftedRule(rule)).toList();
    }

    JointPlanSearch.Generation generate(JointComputationPlan plan, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("POSITIVE_CANDIDATE_LIMIT_REQUIRED");
        long before = work.used();
        work.charge(1);
        if (exhausted) return new JointPlanSearch.Generation(List.of(), 1, false);
        views.clear();
        List<JointPlanSearch.Proposal> proposals = new ArrayList<>();
        boolean complete;
        try (var observation = RetainedOperation.open(new RetainedOperation.Sink() {
            @Override public void executionWork(long units) { expansionCharge(units); }
            @Override public void validationWork(long units) { expansionCharge(units); }
            @Override public void checkpoint() { work.charge(0); }
            @Override public long observedWork() { return work.used(); }
            @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        })) {
            var engine = new AstRewriteTransport(rules, 12, maximum + 1);
            var steps = engine.generate(plan.expression());
            for (var step : steps) {
                if (proposals.size() == maximum) break;
                proposals.add(new JointPlanSearch.Proposal("core-algebra/" + step.rule(), step.target()));
            }
            complete = steps.size() <= maximum;
        } catch (ExpansionLimit limited) {
            proposals = List.of();
            complete = false;
        } finally {
            views.clear();
        }
        // Closing the native observation scope also consumes work.
        return new JointPlanSearch.Generation(proposals, Math.max(1, work.used() - before), complete);
    }

    private void expansionCharge(long units) {
        work.charge(units);
        expansionWork += units;
        if (!exhausted && expansionWork > expansionAllowance) {
            exhausted = true;
            throw new ExpansionLimit();
        }
    }

    /** Lift one existing mathematical rule, not its implementation, to typed values. */
    private final class LiftedRule implements RewriteRule {
        private final RewriteRule mathematical;
        private Expr matched;
        private Expr replacement;
        LiftedRule(RewriteRule mathematical) { this.mathematical = mathematical; }
        @Override public String id() { return mathematical.id(); }
        @Override public RewriteKind kind() { return mathematical.kind(); }
        @Override public boolean mayIncreaseComplexity() { return mathematical.mayIncreaseComplexity(); }
        @Override public int estimatedCostDelta() { return mathematical.estimatedCostDelta(); }
        @Override public boolean isEquivalencePreservingByConstruction() { return false; }
        @Override public RuleDescriptor descriptor() { return mathematical.descriptor(); }
        @Override public boolean matches(Expr expression) {
            work.charge(1);
            matched = null; replacement = null;
            if (!(expression instanceof FunctionExpr f) || !f.name().startsWith("j_")) return false;
            try {
                var kind = JavaExpressions.resultKind(expression);
                if (kind != NumericKind.INT && kind != NumericKind.LONG && kind != NumericKind.BIG_INTEGER) return false;
                if (!algebraic(expression)) return false;
                var view = views.computeIfAbsent(expression, node -> new View(kind, node));
                if (!mathematical.matches(view.expression)) return false;
                // A source-side fact is never silently added to the Java request.
                // Conditional mathematical rules need an explicit discharge API.
                if (!mathematical.assumptions(view.expression).isEmpty()) return false;
                Expr result = view.decode(mathematical.apply(view.expression), 0);
                if (result.equals(expression)) return false;
                matched = expression; replacement = result;
                return true;
            } catch (IllegalArgumentException | ArithmeticException outsideRepresentableAlgebra) {
                return false;
            }
        }
        @Override public Expr apply(Expr expression) {
            work.charge(1);
            if (matched != expression || replacement == null) throw new IllegalArgumentException("RULE_NOT_MATCHED");
            Expr result = replacement; matched = null; replacement = null;
            return result;
        }
    }

    private static boolean algebraic(Expr expression) {
        var op = JavaExpressions.operationOf(expression).orElse(null);
        return op == NumericOperation.ADD || op == NumericOperation.SUBTRACT
                || op == NumericOperation.MULTIPLY || op == NumericOperation.DIVIDE || op == NumericOperation.NEGATE;
    }

    /** The kind is attached to an entire arithmetic island. Casts and other operations are atoms. */
    private final class View {
        private final NumericKind kind;
        private final Map<Expr, VariableExpr> atoms = new LinkedHashMap<>();
        private final Map<String, Expr> values = new LinkedHashMap<>();
        private final Expr expression;
        View(NumericKind kind, Expr source) { this.kind = kind; this.expression = encode(source, 0); }

        private Expr encode(Expr source, int depth) {
            bounded(depth);
            if (!(source instanceof VariableExpr) && JavaExpressions.resultKind(source) == kind) {
                if (JavaExpressions.isLiteral(source)) {
                    Object value = JavaExpressions.literalValue(source);
                    BigInteger integer = value instanceof BigInteger b ? b : BigInteger.valueOf(((Number)value).longValue());
                    if (integer.abs().bitLength() > 4096) throw new IllegalArgumentException("ALGEBRA_LITERAL_BOUND");
                    return new NumberExpr(ExactRational.integer(integer));
                }
                if (algebraic(source)) {
                    var arguments = JavaExpressions.operands(source);
                    var operation = JavaExpressions.operationOf(source).orElseThrow();
                    if (operation == NumericOperation.NEGATE)
                        return new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, encode(arguments.getFirst(), depth + 1));
                    var operator = switch (operation) {
                        case ADD -> BinaryOperator.ADD;
                        case SUBTRACT -> BinaryOperator.SUB;
                        case MULTIPLY -> BinaryOperator.MUL;
                        case DIVIDE -> BinaryOperator.DIV;
                        default -> throw new IllegalArgumentException("UNSUPPORTED_ALGEBRA_OPERATOR");
                    };
                    return new BinaryExpr(encode(arguments.getFirst(), depth + 1), operator, encode(arguments.get(1), depth + 1));
                }
            }
            return atoms.computeIfAbsent(source, value -> {
                String name = "atom" + atoms.size();
                values.put(name, value);
                return new VariableExpr(name);
            });
        }

        private Expr decode(Expr target, int depth) {
            bounded(depth);
            if (target instanceof VariableExpr variable) {
                Expr value = values.get(variable.name());
                if (value == null) throw new IllegalArgumentException("UNBOUND_ALGEBRA_ATOM");
                return value;
            }
            if (target instanceof NumberExpr number) {
                if (!number.value().isInteger() || number.value().numerator().abs().bitLength() > 4096)
                    throw new IllegalArgumentException("UNREPRESENTABLE_ALGEBRA_LITERAL");
                BigInteger value = number.value().numerator();
                return switch (kind) {
                    case INT -> JavaExpressions.literal(value.intValueExact());
                    case LONG -> JavaExpressions.literal(value.longValueExact());
                    case BIG_INTEGER -> JavaExpressions.literal(value);
                    default -> throw new IllegalArgumentException("UNSUPPORTED_ALGEBRA_KIND");
                };
            }
            if (!(target instanceof BinaryExpr binary)) throw new IllegalArgumentException("UNSUPPORTED_ALGEBRA_NODE");
            if (binary.operator() == BinaryOperator.POW) {
                if (!(binary.right() instanceof NumberExpr n) || !n.value().isInteger()
                        || n.value().signum() < 0 || n.value().numerator().compareTo(BigInteger.valueOf(64)) > 0)
                    throw new IllegalArgumentException("UNSUPPORTED_ALGEBRA_POWER");
                Expr factor = decode(binary.left(), depth + 1);
                int exponent = n.value().numerator().intValueExact();
                Expr result = null;
                // Lower a nonnegative integer power into the same Java multiplication domain.
                while (exponent > 0) {
                    work.charge(1);
                    if ((exponent & 1) != 0) result = result == null ? factor
                            : JavaExpressions.operation(kind, NumericOperation.MULTIPLY, result, factor);
                    exponent >>>= 1;
                    if (exponent > 0) factor = JavaExpressions.operation(kind, NumericOperation.MULTIPLY, factor, factor);
                }
                return result == null ? decode(new NumberExpr(1), depth + 1) : result;
            }
            var operation = switch (binary.operator()) {
                case ADD -> NumericOperation.ADD;
                case SUB -> NumericOperation.SUBTRACT;
                case MUL -> NumericOperation.MULTIPLY;
                case DIV -> NumericOperation.DIVIDE;
                default -> throw new IllegalArgumentException("UNSUPPORTED_ALGEBRA_OPERATOR");
            };
            return JavaExpressions.operation(kind, operation, decode(binary.left(), depth + 1), decode(binary.right(), depth + 1));
        }
        private void bounded(int depth) {
            work.charge(1);
            if (depth > 64) throw new IllegalArgumentException("ALGEBRA_VIEW_DEPTH_BOUND");
        }
    }
}
