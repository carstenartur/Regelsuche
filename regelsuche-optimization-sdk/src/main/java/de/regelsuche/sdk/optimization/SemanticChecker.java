package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import java.math.BigInteger;
import java.util.*;

/** Independent checker. No candidate generator, rewrite name, hash or sampled success is a proof. */
final class SemanticChecker {
    static final String REVISION = "java-numeric-independent/v3";
    record Proof(boolean accepted, List<String> methods, long work) { Proof { methods = List.copyOf(methods); } }
    private final OptimizationRequest request;
    private final VerificationWork work;
    SemanticChecker(OptimizationRequest request, VerificationWork work) { this.request = request; this.work = work; }
    void validate() {
        var bounds = new BigIntegerBounds(request, work);
        work.charge(1);
        if (!ComputationOptimizer.SEMANTICS_REVISION.equals(request.semanticsRevision())) throw new IllegalArgumentException("UNKNOWN_SEMANTICS_REVISION");
        if (!CheckedPolicy.REVISION.equals(request.checkedPolicy().revision())) throw new IllegalArgumentException("UNKNOWN_CHECKED_POLICY_REVISION");
        if (request.checkedPolicy().checkTinyInexactUnderflow()) throw new IllegalArgumentException("TINY_INEXACT_UNDERFLOW_UNSUPPORTED");
        if (request.safetyProfile() == SafetyProfile.CHECKED_THROW && !request.checkedPolicy().checkIntegralRange()
                && request.selectedKinds().stream().anyMatch(NumericKind::integral)) throw new IllegalArgumentException("CHECKED_INTEGRAL_RANGE_POLICY_REQUIRED");
        var plan = request.plan();
        if (plan.outputs().stream().anyMatch(output -> !request.selectedKinds().contains(NumericKind.fromType(output.type()))))
            throw new IllegalArgumentException("EXCLUDED_NUMERIC_KIND");
        var prepared = plan.prepare(new JavaNumericBackend(plan.inputs()));
        work.charge(prepared.cost().inspectionWork());
        if (request.sourceTrace().occurrences().size() > JointComputationPlan.MAX_NODES) throw new IllegalArgumentException("TRACE_STRUCTURAL_BOUND");
        var seen = new HashSet<Expr>(); var sourceIds = new HashSet<String>();
        for (var occurrence : request.sourceTrace().occurrences()) {
            work.charge(1);
            if (!sourceIds.add(occurrence.sourceId())) throw new IllegalArgumentException("DUPLICATE_SOURCE_OCCURRENCE_ID");
            Expr expression = occurrence.expression();
            if (JavaExpressions.resultKind(expression) != occurrence.evaluatedKind()) throw new IllegalArgumentException("TRACE_OPERATION_TYPE_DIFFERS");
            if (!request.selectedKinds().contains(occurrence.evaluatedKind()) || !request.selectedKinds().contains(occurrence.declaredKind()))
                throw new IllegalArgumentException("EXCLUDED_NUMERIC_KIND");
            for (Expr operand : JavaExpressions.operands(expression))
                if (!JavaExpressions.isLiteral(operand) && !(operand instanceof VariableExpr) && !seen.contains(operand))
                    throw new IllegalArgumentException("TRACE_EVALUATION_ORDER_OR_OPERATION_MISSING");
            // A trace entry need not have an output's type (e.g. an int exponent in BigInteger.pow).
            var tracePlan = new JointComputationPlan(plan.inputs(), Map.of(), List.of(
                new JointComputationPlan.Output("trace", occurrence.evaluatedKind().type(), expression)));
            work.charge(tracePlan.prepare(new JavaNumericBackend(plan.inputs())).cost().inspectionWork());
            requireTotal(expression);
            bounds.require(expression);
            seen.add(expression);
        }
        for (var node : prepared.nodes()) {
            if (node.operation() != null && !JavaExpressions.isLiteral(node.expression()) && !seen.contains(node.expression()))
                throw new IllegalArgumentException("SOURCE_EVALUATION_TRACE_INCOMPLETE");
        }
        for (var input : plan.inputs().entrySet()) {
            var kind = NumericKind.fromType(input.getValue());
            if (kind == NumericKind.BIG_INTEGER && !has(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, input.getKey()))
                throw new IllegalArgumentException("BIG_INTEGER_VALUE_CONTRACT_REQUIRED");
        }
        boolean fp = prepared.nodes().stream().anyMatch(node -> NumericKind.fromType(node.type()).floatingPoint());
        if (fp && request.safetyProfile()==SafetyProfile.PRESERVE_JAVA &&
                request.assumptions().stream().noneMatch(a -> a.kind() == SemanticAssumption.Kind.NO_NAN_PAYLOAD_OBSERVATION))
            throw new IllegalArgumentException("NAN_PAYLOAD_OBSERVATION_UNSUPPORTED");
        if (fp && request.safetyProfile() == SafetyProfile.CHECKED_THROW &&
                (!request.checkedPolicy().requireFinite() || !request.checkedPolicy().compareFloatingPointBits()))
            throw new IllegalArgumentException("CHECKED_FINITE_AND_BITWISE_COMPARISON_REQUIRED");
        for (var assumption : request.assumptions()) {
            if (assumption.kind() != SemanticAssumption.Kind.NO_NAN_PAYLOAD_OBSERVATION && !plan.inputs().containsKey(assumption.subject()))
                throw new IllegalArgumentException("ASSUMPTION_SUBJECT_NOT_INPUT");
            if (assumption.kind() == SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS &&
                    !NumericKind.BIG_INTEGER.type().equals(plan.inputs().get(assumption.subject())))
                throw new IllegalArgumentException("ASSUMPTION_NUMERIC_KIND_DIFFERS");
            if (assumption.kind() == SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND) {
                if (!NumericKind.BIG_INTEGER.type().equals(plan.inputs().get(assumption.subject())))
                    throw new IllegalArgumentException("ASSUMPTION_NUMERIC_KIND_DIFFERS");
                if (BigIntegerBounds.parameter(assumption.parameter()) > BigIntegerBounds.MAX_BITS)
                    throw new IllegalArgumentException("BIG_INTEGER_SUPPORTED_RANGE_NOT_PROVED");
            }
            if (assumption.kind() == SemanticAssumption.Kind.NON_NEGATIVE_UPPER_BOUND) {
                if (NumericKind.fromType(plan.inputs().get(assumption.subject())).floatingPoint())
                    throw new IllegalArgumentException("ASSUMPTION_NUMERIC_KIND_DIFFERS");
                BigIntegerBounds.parameter(assumption.parameter());
            }
            if (assumption.kind() == SemanticAssumption.Kind.NORMALIZED_MODULAR_INPUT &&
                    (!plan.inputs().containsKey(assumption.parameter()) || !has(SemanticAssumption.Kind.POSITIVE, assumption.parameter())))
                throw new IllegalArgumentException("NORMALIZATION_REQUIRES_POSITIVE_MODULUS");
        }
    }
    Proof check(JointComputationPlan source, JointComputationPlan target) {
        var bounds = new BigIntegerBounds(request, work);
        long initial = work.used(); work.charge(1);
        if (!source.inputs().equals(target.inputs()) || !bindings(source).equals(bindings(target))) return new Proof(false, List.of("BINDINGS_DIFFER"), work.used()-initial);
        var targetPrepared = target.prepare(new JavaNumericBackend(target.inputs()));
        work.charge(targetPrepared.cost().inspectionWork());
        for (var node : targetPrepared.nodes()) if (node.operation()!=null && !JavaExpressions.isLiteral(node.expression())) {
            if (!request.selectedKinds().contains(NumericKind.fromType(node.type()))) throw new IllegalArgumentException("EXCLUDED_NUMERIC_KIND");
            requireTotal(node.expression());
            bounds.require(node.expression());
        }
        var left = source.outputExpressions(); var right = target.outputExpressions();
        var methods = new LinkedHashSet<String>();
        for (int i=0;i<left.size();i++) {
            work.charge(1); Expr a=left.get(i), b=right.get(i);
            var kind=NumericKind.fromType(source.outputs().get(i).type());
            if (a.equals(b)) { methods.add("TYPED_STRUCTURAL_IDENTITY"); continue; }
            if (kind!=NumericKind.BIG_INTEGER) {
                var literalLeft=primitiveLiteralValue(a); var literalRight=primitiveLiteralValue(b);
                if (literalLeft.isPresent() && literalRight.isPresent() && RuntimeChecks.same(literalLeft.get(),literalRight.get())) {
                    methods.add("EXACT_PRIMITIVE_LITERAL_CAST"); continue;
                }
            }
            if (a instanceof FunctionExpr af && b instanceof FunctionExpr bf && af.name().equals(bf.name())
                    && af.arguments().size()==bf.arguments().size() && !JavaExpressions.isLiteral(a)) {
                boolean congruent=true;
                for (int argument=0;argument<af.arguments().size();argument++) {
                    Expr child=af.arguments().get(argument), replacement=bf.arguments().get(argument);
                    var childKind=JavaExpressions.kindOf(child,source.inputs());
                    var childSource=new JointComputationPlan(source.inputs(),Map.of(),List.of(new JointComputationPlan.Output("congruence",childKind.type(),child)));
                    var childTarget=new JointComputationPlan(source.inputs(),Map.of(),List.of(new JointComputationPlan.Output("congruence",childKind.type(),replacement)));
                    var childProof=check(childSource,childTarget);
                    if(!childProof.accepted()) { congruent=false; break; }
                    methods.addAll(childProof.methods());
                }
                if(congruent) { methods.add("TYPED_OPERATION_CONGRUENCE"); continue; }
            }
            if (kind.floatingPoint()) {
                if (new StrictFloatingProof(work).equivalent(a,b)) { methods.add("STRICT_IEEE_IDENTITY"); continue; }
                if (request.safetyProfile()!=SafetyProfile.PRESERVE_JAVA) {
                    methods.add("RUNTIME_FINITE_AND_RAW_BITS_GATE"); continue;
                }
                return new Proof(false,List.copyOf(methods),work.used()-initial);
            }
            boolean exact = kind==NumericKind.BIG_INTEGER || request.safetyProfile()!=SafetyProfile.PRESERVE_JAVA;
            try {
                if (new PolynomialProof(kind,exact,false,work).equivalent(a,b)) {
                    methods.add(kind==NumericKind.BIG_INTEGER ? "INTEGER_POLYNOMIAL_NORMAL_FORM" : exact ? "INTEGER_NORMAL_FORM_WITH_BOTH_TRACE_RANGE_GATE" : "BITVECTOR_POLYNOMIAL_MOD_2_"+kind.bits());
                    continue;
                }
            } catch (PolynomialProof.OutsideFragment bounded) { /* A larger polynomial is unknown, never proved. */ }
            if (kind==NumericKind.BIG_INTEGER) {
                // This is an exact typed expansion, not equality modulo an unspecified modulus.
                // Both traces and every intermediate range have already been checked above.
                if (expandModularProduct(a).equals(expandModularProduct(b))) {
                    methods.add("TYPED_MODULAR_PRODUCT_EXPANSION"); continue;
                }
                try {
                    var proof = new ModularBridge(request.assumptions()).verify(a,b); work.charge(proof.work());
                    if (proof.accepted()) { methods.add("AFFINE_MODULAR_NORMAL_FORM"); continue; }
                } catch (IllegalArgumentException outsideModular) { /* Different exact fragments are not interchangeable. */ }
            }
            if (kind.integral() && new BitwiseProof(work).equivalent(a,b,kind)) { methods.add("BITVECTOR_TRUTH_TABLE"); continue; }
            return new Proof(false,List.copyOf(methods),work.used()-initial);
        }
        return new Proof(true,List.copyOf(methods),Math.max(1,work.used()-initial));
    }
    private Expr expandModularProduct(Expr expression) {
        work.charge(1);
        if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) return expression;
        var args=JavaExpressions.operands(expression).stream().map(this::expandModularProduct).toList();
        if (JavaExpressions.operationOf(expression).orElse(null)==NumericOperation.MOD_MULTIPLY)
            return JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MOD,
                JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MULTIPLY,args.getFirst(),args.get(1)),args.get(2));
        return new FunctionExpr(((FunctionExpr)expression).name(),args);
    }
    /** Closed primitive literal casts have one exact Java value; this evaluates no input or arithmetic. */
    private Optional<Object> primitiveLiteralValue(Expr expression) {
        work.charge(1);
        if (JavaExpressions.isLiteral(expression)) return Optional.of(JavaExpressions.literalValue(expression));
        if (JavaExpressions.castSourceKind(expression).isEmpty()) return Optional.empty();
        var value=primitiveLiteralValue(JavaExpressions.operands(expression).getFirst());
        if (value.isEmpty()) return Optional.empty();
        var backend=new JavaNumericBackend(request.plan().inputs());
        return Optional.of(backend.apply(backend.operation(expression),List.of(value.get())));
    }
    private static List<List<Object>> bindings(JointComputationPlan plan) {
        return plan.outputs().stream().map(output -> List.<Object>of(output.name(),output.type())).toList();
    }
    private boolean has(SemanticAssumption.Kind kind,String name) { return request.assumptions().stream().anyMatch(a ->
        (a.kind()==kind || kind==SemanticAssumption.Kind.NON_NEGATIVE && a.kind()==SemanticAssumption.Kind.NON_NEGATIVE_UPPER_BOUND)
            && a.subject().equals(name)); }
    private boolean positive(Expr expression) {
        if (expression instanceof VariableExpr v) return has(SemanticAssumption.Kind.POSITIVE,v.name());
        return JavaExpressions.isLiteral(expression) && integer(JavaExpressions.literalValue(expression)).signum()>0;
    }
    private void requireTotal(Expr expression) {
        var op=JavaExpressions.operationOf(expression);
        if (op.isEmpty()) return;
        var kind=JavaExpressions.resultKind(expression); var args=JavaExpressions.operands(expression);
        if (op.get().name().endsWith("EXACT")) throw new IllegalArgumentException("EXISTING_EXACT_CHECK_MUST_REMAIN");
        if ((op.get()==NumericOperation.DIVIDE || op.get()==NumericOperation.REMAINDER) && !kind.floatingPoint()) {
            if (!JavaExpressions.isLiteral(args.get(1)) || integer(JavaExpressions.literalValue(args.get(1))).signum()==0)
                throw new IllegalArgumentException("POSSIBLE_DIVISION_EXCEPTION");
        }
        if (kind==NumericKind.BIG_INTEGER) switch(op.get()) {
            case MOD -> { if (!positive(args.get(1))) throw new IllegalArgumentException("POSITIVE_MODULUS_REQUIRED"); }
            case MOD_POW -> {
                try {
                    var proof = new ModularBridge(request.assumptions()).verify(expression,expression); work.charge(proof.work());
                    if (!proof.accepted()) throw new IllegalArgumentException("NONNEGATIVE_AFFINE_EXPONENT_AND_POSITIVE_MODULUS_REQUIRED");
                } catch (NoSuchElementException malformed) { throw new IllegalArgumentException("MODULAR_FRAGMENT_REQUIRED",malformed); }
            }
            case MOD_MULTIPLY -> { if (!positive(args.get(2))) throw new IllegalArgumentException("POSITIVE_MODULUS_REQUIRED"); }
            case POW -> {
                if (!(JavaExpressions.isLiteral(args.get(1)) && integer(JavaExpressions.literalValue(args.get(1))).signum()>=0)
                        && !(args.get(1) instanceof VariableExpr v && (has(SemanticAssumption.Kind.NON_NEGATIVE,v.name()) || has(SemanticAssumption.Kind.POSITIVE,v.name()))))
                    throw new IllegalArgumentException("NONNEGATIVE_EXPONENT_REQUIRED");
            }
            default -> { }
        }
    }
    static BigInteger integer(Object value) {
        if (value instanceof BigInteger b) return b;
        if (value instanceof Character c) return BigInteger.valueOf(c);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return BigInteger.valueOf(((Number)value).longValue());
        throw new IllegalArgumentException("INTEGRAL_VALUE_REQUIRED");
    }
}
