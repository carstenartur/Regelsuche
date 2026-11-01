package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import java.math.*;
import java.util.*;

/** Reference execution for testing/integration of the emitted policy. Never invoked by optimization. */
final class RuntimeChecks {
    static final class AssumptionGuardFailure extends IllegalArgumentException {
        AssumptionGuardFailure(String message) { super(message); }
    }

    private RuntimeChecks() {}
    static Map<String,Object> checked(OptimizationRequest request, JointComputationPlan target, RuntimeObligations obligations, Map<String,?> inputs) {
        var captured=snapshot(request.plan(),inputs);
        validateCapturedAssumptions(request,captured);
        if(obligations.requireFinite()) for(var value:captured.values()) finite(value);
        boolean evaluateOriginal=request.safetyProfile()==SafetyProfile.CHECKED_THROW
            || obligations.checkIntegralRange() || obligations.requireFinite() || obligations.compareFloatingPointBits();
        // The prepared DAG is also the source of candidate operation costs. A
        // trace expands shared outputs and would execute that arithmetic again.
        if(!evaluateOriginal) return ComputationOptimizer.prepare(target).execute(captured);
        var original=trace(request.plan(),obligations.originalTrace(),captured,obligations);
        var replacement=trace(target,obligations.replacementTrace(),captured,obligations);
        if(obligations.compareFloatingPointBits()) for(var name:original.keySet())
            if(!same(original.get(name),replacement.get(name))) throw new ArithmeticException("MATH_NUMERIC_DEVIATION");
        return replacement;
    }
    static Map<String,Object> trace(JointComputationPlan plan,SourceEvaluationTrace trace,Map<String,?> inputs,RuntimeObligations obligations) {
        var backend=new JavaNumericBackend(plan.inputs());
        var captured=snapshot(plan,inputs);
        var values=new HashMap<Expr,Object>();
        for(var occurrence:trace.occurrences()) {
            Expr expression=occurrence.expression();
            var args=JavaExpressions.operands(expression).stream().map(e -> value(e,values,captured)).toList();
            if(obligations.checkIntegralRange()) range(expression,args);
            Object value=backend.apply(backend.operation(expression),args);
            if(obligations.requireFinite()) finite(value);
            values.put(expression,value);
        }
        var outputs=new LinkedHashMap<String,Object>(); var expressions=plan.outputExpressions();
        for(int i=0;i<expressions.size();i++) {
            Object value=value(expressions.get(i),values,captured);
            if(obligations.requireFinite()) finite(value);
            outputs.put(plan.outputs().get(i).name(),value);
        }
        return Collections.unmodifiableMap(outputs);
    }
    private static Object value(Expr expression,Map<Expr,Object> values,Map<String,?> inputs) {
        if(expression instanceof VariableExpr variable) return inputs.get(variable.name());
        if(JavaExpressions.isLiteral(expression)) return JavaExpressions.literalValue(expression);
        Object result=values.get(expression);
        if(result==null) throw new IllegalArgumentException("TRACE_DEPENDENCY_MISSING");
        return result;
    }
    static void finite(Object value) {
        if(value instanceof Float f && !Float.isFinite(f) || value instanceof Double d && !Double.isFinite(d))
            throw new ArithmeticException("MATH_NON_FINITE");
    }
    private static void range(Expr expression,List<Object> args) {
        NumericKind kind=JavaExpressions.resultKind(expression);
        if(!kind.integral()) return;
        var cast=JavaExpressions.castSourceKind(expression);
        if(cast.isPresent()) {
            Object value=args.getFirst(); BigInteger integer;
            if(value instanceof Float || value instanceof Double) {
                finite(value); integer=new BigDecimal(((Number)value).doubleValue()).toBigInteger();
            } else integer=SemanticChecker.integer(value);
            inRange(integer,kind); return;
        }
        var op=JavaExpressions.operationOf(expression).orElseThrow();
        BigInteger left=SemanticChecker.integer(args.getFirst());
        BigInteger right=args.size()>1?SemanticChecker.integer(args.get(1)):BigInteger.ZERO;
        BigInteger result=switch(op) {
            case ADD,ADD_EXACT -> left.add(right); case SUBTRACT,SUBTRACT_EXACT -> left.subtract(right);
            case MULTIPLY,MULTIPLY_EXACT -> left.multiply(right); case NEGATE,NEGATE_EXACT -> left.negate();
            case ABS -> left.abs(); case DIVIDE -> left.divide(right);
            case SHIFT_LEFT -> left.shiftLeft(right.intValue() & (kind.bits()-1));
            default -> null; // Remainder, bitwise and right shifts cannot overflow their executed width.
        };
        if(result!=null) inRange(result,kind);
    }
    private static void inRange(BigInteger value,NumericKind kind) {
        BigInteger minimum=kind.signed()?BigInteger.ONE.shiftLeft(kind.bits()-1).negate():BigInteger.ZERO;
        BigInteger maximum=BigInteger.ONE.shiftLeft(kind.signed()?kind.bits()-1:kind.bits()).subtract(BigInteger.ONE);
        if(value.compareTo(minimum)<0 || value.compareTo(maximum)>0) throw new ArithmeticException("MATH_INTEGRAL_RANGE");
    }
    static boolean same(Object left,Object right) {
        if(left instanceof Float a && right instanceof Float b) return Float.floatToIntBits(a)==Float.floatToIntBits(b);
        if(left instanceof Double a && right instanceof Double b) return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);
        return Objects.equals(left,right);
    }
    static void validateAssumptions(OptimizationRequest request,Map<String,?> inputs) {
        validateCapturedAssumptions(request,snapshot(request.plan(),inputs));
    }
    /** Read only declared bindings once; caller maps may change or enumerate arbitrarily many entries. */
    static Map<String,Object> snapshot(JointComputationPlan plan,Map<String,?> inputs) {
        var captured=new LinkedHashMap<String,Object>();
        for(var input:plan.inputs().entrySet()) {
            Object value=inputs.get(input.getKey()); input.getValue().requireValue(value);
            captured.put(input.getKey(),value);
        }
        new JavaNumericBackend(plan.inputs()).validateInputs(captured);
        return Collections.unmodifiableMap(captured);
    }
    private static void validateCapturedAssumptions(OptimizationRequest request,Map<String,Object> inputs) {
        for(var assumption:request.assumptions()) {
            Object supplied=inputs.get(assumption.subject());
            switch(assumption.kind()) {
                // A caller's conditional proof premise is not an observed runtime success.
                // Guarded evaluation selects the original captured source and its exceptions.
                case SUCCESSFUL_MODULAR_INVERSE -> throw new AssumptionGuardFailure("SUCCESSFUL_INVERSE_RUNTIME_OBSERVATION_REQUIRED");
                case BIG_INTEGER_VALUE_SEMANTICS -> {
                    if(supplied==null || supplied.getClass()!=BigInteger.class) throw new IllegalArgumentException("BIG_INTEGER_VALUE_CONTRACT_VIOLATED");
                }
                case BIG_INTEGER_BIT_LENGTH_BOUND -> {
                    if (!(supplied instanceof BigInteger value)) throw new IllegalArgumentException("BIG_INTEGER_VALUE_REQUIRED");
                    if (value.abs().bitLength() > BigIntegerBounds.parameter(assumption.parameter()))
                        throw new AssumptionGuardFailure("BIG_INTEGER_MAGNITUDE_ASSUMPTION_VIOLATED");
                }
                case NON_NEGATIVE_UPPER_BOUND -> {
                    var value=SemanticChecker.integer(supplied);
                    if(value.signum()<0 || value.compareTo(BigInteger.valueOf(BigIntegerBounds.parameter(assumption.parameter())))>0)
                        throw new AssumptionGuardFailure("NUMERIC_UPPER_BOUND_ASSUMPTION_VIOLATED");
                }
                case NON_NEGATIVE -> { if(SemanticChecker.integer(supplied).signum()<0) throw new AssumptionGuardFailure("NONNEGATIVE_ASSUMPTION_VIOLATED"); }
                case POSITIVE -> { if(SemanticChecker.integer(supplied).signum()<=0) throw new AssumptionGuardFailure("POSITIVE_ASSUMPTION_VIOLATED"); }
                case NORMALIZED_MODULAR_INPUT -> {
                    var value=SemanticChecker.integer(supplied); var modulus=SemanticChecker.integer(inputs.get(assumption.parameter()));
                    if(modulus.signum()<=0 || value.signum()<0 || value.compareTo(modulus)>=0) throw new AssumptionGuardFailure("NORMALIZATION_ASSUMPTION_VIOLATED");
                }
                case MODULAR_UNIT_INPUT -> {
                    var value=SemanticChecker.integer(supplied); var modulus=SemanticChecker.integer(inputs.get(assumption.parameter()));
                    if(modulus.signum()<=0 || !value.gcd(modulus).equals(BigInteger.ONE))
                        throw new AssumptionGuardFailure("MODULAR_UNIT_ASSUMPTION_VIOLATED");
                }
                default -> { }
            }
        }
    }
}
