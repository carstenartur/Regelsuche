package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;

/** Independent bounded ring normal form. Java word arithmetic is reduced modulo 2^width.
 * Non-polynomial typed subexpressions remain opaque atoms; they are never treated as real numbers.
 */
final class PolynomialProof {
    static final int MAX_TERMS = 256, MAX_DEGREE = 64, MAX_COEFFICIENT_BITS = 4096;
    // A signed sum can cancel larger intermediates before producing its bounded normal form.
    private static final int MAX_SUM_CONTRIBUTIONS = 2048;
    static final class OutsideFragment extends RuntimeException {}
    private record Monomial(Map<Expr, Integer> powers) {
        Monomial { powers = Map.copyOf(powers); }
        Monomial times(Monomial other) {
            var result = new HashMap<>(powers);
            other.powers.forEach((key, power) -> result.merge(key, power, Integer::sum));
            if (result.values().stream().mapToInt(Integer::intValue).sum() > MAX_DEGREE) throw new OutsideFragment();
            return new Monomial(result);
        }
    }
    private final NumericKind kind;
    private final BigInteger modulus;
    private final Expr residueModulus;
    private final boolean exactDivision;
    private final boolean floatingAlgebra;
    private final VerificationWork work;
    PolynomialProof(NumericKind kind, boolean exactDivision, boolean floatingAlgebra, VerificationWork work) {
        this.kind = kind; this.exactDivision = exactDivision; this.floatingAlgebra = floatingAlgebra; this.work = work;
        modulus = kind.integral() && !exactDivision ? BigInteger.ONE.shiftLeft(kind.bits()) : null;
        residueModulus = null;
    }
    PolynomialProof(NumericKind kind, int lowBits, VerificationWork work) {
        if ((kind != NumericKind.INT && kind != NumericKind.LONG) || lowBits < 1 || lowBits > kind.bits())
            throw new IllegalArgumentException("INVALID_LOW_WORD_WIDTH");
        this.kind = kind; this.exactDivision = false; this.floatingAlgebra = false; this.work = work;
        modulus = BigInteger.ONE.shiftLeft(lowBits);
        residueModulus = null;
    }
    /** Caller independently establishes positivity. Only this exact modulus is erased. */
    PolynomialProof(Expr positiveModulus, VerificationWork work) {
        kind = NumericKind.BIG_INTEGER; exactDivision = false; floatingAlgebra = false;
        this.work = work; residueModulus = Objects.requireNonNull(positiveModulus);
        modulus = JavaExpressions.isLiteral(positiveModulus)
            ? SemanticChecker.integer(JavaExpressions.literalValue(positiveModulus)) : null;
        if (modulus != null && modulus.signum() <= 0) throw new OutsideFragment();
    }
    boolean equivalent(Expr source, Expr target) { return normalize(source, 0).equals(normalize(target, 0)); }
    /** Clear the powers of one known unit inverse. Multiplication by the unit's
     * maximum denominator power is injective even when the modulus is composite.
     */
    boolean equivalentAssumingInverse(Expr source,Expr target,Expr inverse) {
        work.charge(1);
        if(residueModulus==null || JavaExpressions.operationOf(inverse).orElse(null)!=NumericOperation.MOD_INVERSE
                || !JavaExpressions.operands(inverse).get(1).equals(residueModulus)) return false;
        var difference=add(normalize(source,0),normalize(target,0),BigInteger.ONE.negate());
        int degree=0;
        for(var term:difference.keySet()) { work.charge(1); degree=Math.max(degree,term.powers().getOrDefault(inverse,0)); }
        var operand=normalize(JavaExpressions.operands(inverse).getFirst(),0);
        var powers=new ArrayList<Map<Monomial,BigInteger>>(); powers.add(constant(BigInteger.ONE));
        for(int i=1;i<=degree;i++) powers.add(multiply(powers.getLast(),operand));
        var numerator=new SumAccumulator();
        for(var term:difference.entrySet()) {
            work.charge(1); var factors=new HashMap<>(term.getKey().powers()); int inverseDegree=factors.getOrDefault(inverse,0); factors.remove(inverse);
            numerator.product(Map.of(new Monomial(factors),term.getValue()),powers.get(degree-inverseDegree),BigInteger.ONE);
        }
        return numerator.terms.isEmpty();
    }
    private Map<Monomial, BigInteger> normalize(Expr expression, int depth) {
        work.charge(1);
        if (depth > 128) throw new OutsideFragment();
        if(residueModulus!=null && residueModulus.equals(expression)) return constant(BigInteger.ZERO);
        if (JavaExpressions.isLiteral(expression)) return constant(literal(expression));
        if (expression instanceof VariableExpr) return atom(expression);
        if (JavaExpressions.resultKind(expression) != kind) return atom(expression);
        var operation = JavaExpressions.operationOf(expression);
        if (operation.isEmpty()) return atom(expression);
        var args = JavaExpressions.operands(expression);
        return switch (operation.get()) {
            case MOD -> residueModulus != null && residueModulus.equals(args.get(1))
                ? normalize(args.getFirst(), depth+1) : atom(expression);
            case MOD_MULTIPLY -> residueModulus != null && residueModulus.equals(args.get(2))
                ? multiply(normalize(args.getFirst(), depth+1), normalize(args.get(1), depth+1)) : atom(expression);
            case ADD, SUBTRACT, NEGATE -> sum(expression,depth);
            case MULTIPLY -> multiply(normalize(args.getFirst(), depth+1), normalize(args.get(1), depth+1));
            case SHIFT_LEFT -> leftShift(expression,args,depth);
            case POW -> power(expression,args,depth);
            case DIVIDE -> divide(expression,args,depth);
            default -> atom(expression);
        };
    }
    private BigInteger literal(Expr expression) {
        Object value=JavaExpressions.literalValue(expression);
        if(value instanceof BigInteger b) return b;
        if(value instanceof Character c) return BigInteger.valueOf(c);
        if(value instanceof Float || value instanceof Double) {
            double d=((Number)value).doubleValue();
            if(!floatingAlgebra || !Double.isFinite(d) || d!=Math.rint(d) || Math.abs(d)>1e15) throw new OutsideFragment();
            return BigInteger.valueOf((long)d);
        }
        return BigInteger.valueOf(((Number)value).longValue());
    }
    private Map<Monomial,BigInteger> power(Expr expression,List<Expr> args,int depth) {
        if(!JavaExpressions.isLiteral(args.get(1))) return atom(expression);
        BigInteger exponent=SemanticChecker.integer(JavaExpressions.literalValue(args.get(1)));
        if(exponent.signum()<0 || exponent.compareTo(BigInteger.valueOf(MAX_DEGREE))>0) return atom(expression);
        var base=normalize(args.getFirst(),depth+1); var result=constant(BigInteger.ONE);
        for(int i=0;i<exponent.intValue();i++) result=multiply(result,base);
        return result;
    }
    private Map<Monomial,BigInteger> leftShift(Expr expression,List<Expr> args,int depth) {
        if(kind!=NumericKind.BIG_INTEGER || !JavaExpressions.isLiteral(args.get(1))
                || !(JavaExpressions.literalValue(args.get(1)) instanceof Integer distance)
                || distance<0 || distance>=MAX_COEFFICIENT_BITS) return atom(expression);
        // BigInteger shifts have no primitive distance masking. Bound and pay
        // for the exact coefficient before allocating 2^distance.
        work.charge(1L+distance);
        return multiply(normalize(args.getFirst(),depth+1),constant(BigInteger.ONE.shiftLeft(distance)));
    }
    private Map<Monomial,BigInteger> divide(Expr expression,List<Expr> args,int depth) {
        if(!exactDivision || !JavaExpressions.isLiteral(args.get(1))) return atom(expression);
        BigInteger divisor=SemanticChecker.integer(JavaExpressions.literalValue(args.get(1)));
        if(divisor.signum()==0) throw new OutsideFragment();
        var result=new HashMap<Monomial,BigInteger>();
        for(var entry:normalize(args.getFirst(),depth+1).entrySet()) {
            work.charge(1);
            var qr=entry.getValue().divideAndRemainder(divisor);
            if(qr[1].signum()!=0) return atom(expression);
            put(result,entry.getKey(),qr[0]);
        }
        return Map.copyOf(result);
    }
    private record SignedTerm(Expr expression,int depth,BigInteger sign) { }
    private Map<Monomial,BigInteger> sum(Expr expression,int depth) {
        var pending=new ArrayDeque<SignedTerm>(); pending.push(new SignedTerm(expression,depth,BigInteger.ONE));
        var scratch=new SumAccumulator();
        while(!pending.isEmpty()) {
            work.charge(1); var term=pending.pop();
            if(term.depth()>128) throw new OutsideFragment();
            var e=term.expression(); var operation=JavaExpressions.operationOf(e).orElse(null); var args=JavaExpressions.operands(e);
            boolean sameKind=operation!=null && JavaExpressions.resultKind(e)==kind;
            if(sameKind && operation==NumericOperation.MOD && residueModulus!=null && residueModulus.equals(args.get(1))) {
                pending.push(new SignedTerm(args.getFirst(),term.depth()+1,term.sign()));
            } else if(sameKind && (operation==NumericOperation.ADD || operation==NumericOperation.SUBTRACT || operation==NumericOperation.NEGATE)) {
                if(operation!=NumericOperation.NEGATE) pending.push(new SignedTerm(args.get(1),term.depth()+1,
                    operation==NumericOperation.SUBTRACT?term.sign().negate():term.sign()));
                pending.push(new SignedTerm(args.getFirst(),term.depth()+1,operation==NumericOperation.NEGATE?term.sign().negate():term.sign()));
            } else if(sameKind && operation==NumericOperation.MULTIPLY)
                scratch.product(normalize(args.getFirst(),term.depth()+1),normalize(args.get(1),term.depth()+1),term.sign());
            else scratch.add(normalize(e,term.depth()),term.sign());
        }
        if(scratch.terms.size()>MAX_TERMS) throw new OutsideFragment();
        return Map.copyOf(scratch.terms);
    }
    private final class SumAccumulator {
        private final Map<Monomial,BigInteger> terms=new HashMap<>();
        private int contributions;
        private void reserve() {
            work.charge(1);
            if(++contributions>MAX_SUM_CONTRIBUTIONS) throw new OutsideFragment();
        }
        void add(Map<Monomial,BigInteger> polynomial,BigInteger sign) {
            for(var entry:polynomial.entrySet()) { reserve(); accumulate(entry.getKey(),entry.getValue().multiply(sign)); }
        }
        void product(Map<Monomial,BigInteger> left,Map<Monomial,BigInteger> right,BigInteger sign) {
            if((long)left.size()*right.size()>MAX_TERMS*4L) throw new OutsideFragment();
            for(var l:left.entrySet()) for(var r:right.entrySet()) {
                reserve(); accumulate(l.getKey().times(r.getKey()),l.getValue().multiply(r.getValue()).multiply(sign));
            }
        }
        private void accumulate(Monomial monomial,BigInteger coefficient) {
            putCoefficient(terms,monomial,terms.getOrDefault(monomial,BigInteger.ZERO).add(coefficient));
        }
    }
    private Map<Monomial, BigInteger> atom(Expr expression) { return Map.of(new Monomial(Map.of(expression, 1)), BigInteger.ONE); }
    private Map<Monomial, BigInteger> constant(BigInteger value) {
        var result = new HashMap<Monomial, BigInteger>(); put(result, new Monomial(Map.of()), value); return Map.copyOf(result);
    }
    private Map<Monomial, BigInteger> add(Map<Monomial, BigInteger> left, Map<Monomial, BigInteger> right, BigInteger sign) {
        var result=new SumAccumulator(); result.add(left,BigInteger.ONE); result.add(right,sign);
        if(result.terms.size()>MAX_TERMS) throw new OutsideFragment();
        return Map.copyOf(result.terms);
    }
    private Map<Monomial, BigInteger> multiply(Map<Monomial, BigInteger> left, Map<Monomial, BigInteger> right) {
        if ((long)left.size() * right.size() > MAX_TERMS * 4L) throw new OutsideFragment();
        var result = new HashMap<Monomial, BigInteger>();
        for (var l : left.entrySet()) for (var r : right.entrySet()) {
            work.charge(1); var monomial = l.getKey().times(r.getKey());
            put(result, monomial, result.getOrDefault(monomial, BigInteger.ZERO).add(l.getValue().multiply(r.getValue())));
        }
        return Map.copyOf(result);
    }
    private void put(Map<Monomial, BigInteger> target, Monomial monomial, BigInteger value) {
        putCoefficient(target,monomial,value);
        if (target.size() > MAX_TERMS) throw new OutsideFragment();
    }
    private void putCoefficient(Map<Monomial, BigInteger> target, Monomial monomial, BigInteger value) {
        if (modulus != null) value = value.mod(modulus);
        if (value.bitLength() > MAX_COEFFICIENT_BITS) throw new OutsideFragment();
        if (value.signum() == 0) target.remove(monomial); else target.put(monomial, value);
    }
}
