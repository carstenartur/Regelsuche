package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.*;

/** Shared bounded arithmetic, not a certificate authority. */
final class SeriesSupport {
    private SeriesSupport() {}
    static void forbid(Expr e,SymbolId index) {
        if(e instanceof VariableExpr v && v.symbol().orElseThrow().equals(index)) throw new IllegalArgumentException("INDEX_IN_BOUND");
        if(e instanceof BinaryExpr b) { forbid(b.left(),index); forbid(b.right(),index); }
        if(e instanceof FunctionExpr f) for(var a:f.arguments()) forbid(a,index);
    }
    static Set<SymbolId> symbols(PolynomialSum s) { var ids=new HashSet<>(s.parameters()); ids.add(s.index()); return ids; }
    static IterationPolynomial parse(Expr e,Set<SymbolId> ids,Work w) {
        return IterationPolynomial.parse(e,ids,false,PolynomialSum.MAX_TOTAL_DEGREE,w);
    }
    static int degree(IterationPolynomial p,SymbolId index) {
        p.work().charge(p.terms().size()); return p.terms().keySet().stream().mapToInt(m->m.degree(index)).max().orElse(0);
    }
    /** Integer-valued polynomial criterion: every multivariate Newton coefficient is integral. */
    static void integerValued(IterationPolynomial p) {
        p.work().charge(p.terms().size());
        if(p.terms().values().stream().allMatch(ExactRational::isInteger)) return;
        var symbol=p.terms().keySet().stream().flatMap(m->m.symbols().stream()).findFirst();
        if(symbol.isEmpty()) throw new Unavailable("NONINTEGRAL_POLYNOMIAL");
        var x=symbol.orElseThrow(); int d=degree(p,x); var difference=p;
        for(int j=0;j<=d;j++) {
            integerValued(difference.at(x,BigInteger.ZERO));
            if(j<d) difference=difference.shifted(x,BigInteger.ONE).subtract(difference);
        }
    }
    static IterationPolynomial source(PolynomialSum s,Work w) {
        boolean integralLeaves=s.arithmetic()!=de.regelsuche.ast.iteration.IntegerArithmetic.EXACT;
        var term=IterationPolynomial.parse(s.term(),symbols(s),integralLeaves,PolynomialSum.MAX_TOTAL_DEGREE,w);
        if(degree(term,s.index())>PolynomialSum.MAX_INDEX_DEGREE) throw new Unavailable("INDEX_DEGREE_LIMIT");
        integerValued(term);
        var start=IterationPolynomial.parse(s.start(),s.parameters(),integralLeaves,PolynomialSum.MAX_TOTAL_DEGREE,w); integerValued(start);
        var initial=IterationPolynomial.parse(s.initial(),s.parameters(),integralLeaves,PolynomialSum.MAX_TOTAL_DEGREE,w); integerValued(initial);
        var ordinal=variable(s.index(),w).scale(ExactRational.integer(s.stride())).add(start);
        return term.substitute(s.index(),ordinal);
    }
    static void bits(BigInteger x,Work w) {
        w.runtimeInteger(x);
    }
    static ExactRational bounded(ExactRational x,Work w) { bits(x.numerator(),w); bits(x.denominator(),w); return x; }
    static BigInteger value(IterationPolynomial p,Map<SymbolId,BigInteger> values) {
        var r=rationalValue(p,values);
        if(!r.isInteger()) throw new Unavailable("NONINTEGRAL_VALUE"); return r.numerator();
    }
    static ExactRational rationalValue(IterationPolynomial p,Map<SymbolId,BigInteger> values) {
        var r=ExactRational.ZERO;
        for(var e:p.terms().entrySet()) {
            var term=e.getValue();
            for(var symbol:e.getKey().symbols()) {
                var n=values.get(symbol); if(n==null) throw new Unavailable("UNBOUND_SYMBOL"); bits(n,p.work());
                term=bounded(term.multiply(ExactRational.integer(n)),p.work());
            }
            r=bounded(r.add(term),p.work());
        }
        return r;
    }
    static IterationPolynomial binomial(IterationPolynomial x,int k) {
        var result=constant(BigInteger.ONE,x.work());
        for(int j=0;j<k;j++) result=result.multiply(x.add(constant(BigInteger.valueOf(-j),x.work()))).scale(ExactRational.ONE.divide(ExactRational.integer(j+1)));
        return result;
    }

    static Work work(OptimizationBudget budget, CancellationToken cancellation, int maximumDegree) {
        Objects.requireNonNull(cancellation);
        return new Work(new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis()),
            cancellation::isCancelled, maximumDegree);
    }

    static Set<SymbolId> symbols(FiniteIteration source, boolean state) {
        var result=new HashSet<>(source.parameters()); result.add(source.index());
        if(state && source instanceof FiniteIteration.Recurrence recurrence) result.add(recurrence.state());
        return result;
    }
    record Source(IterationPolynomial term, IterationPolynomial initial) {}
    static Source source(FiniteIteration source, Work w) {
        w.charge(1);
        if(source instanceof FiniteIteration.Sum sum)
            return new Source(IterationPolynomial.parse(sum.term(),symbols(source,false),true,MAX_DEGREE,w),zero(w));
        var recurrence=(FiniteIteration.Recurrence)source;
        var update=IterationPolynomial.parse(recurrence.update(),symbols(source,true),true,MAX_DEGREE,w);
        var term=update.subtract(variable(recurrence.state(),w));
        if(!term.independent(recurrence.state())) throw new Unavailable("NONLINEAR_STATE_UPDATE");
        return new Source(term,IterationPolynomial.parse(recurrence.initial(),source.parameters(),true,MAX_DEGREE,w));
    }
    static long operations(Expr expression, Work w) { return operations(expression,w,1,new int[]{0}); }
    private static long operations(Expr e, Work w, int depth,int[] nodes) {
        w.charge(1);
        if(depth>FiniteIteration.MAX_DEPTH || ++nodes[0]>FiniteIteration.MAX_NODES) throw new Unavailable("EXPRESSION_LIMIT");
        return e instanceof BinaryExpr b ? 1+operations(b.left(),w,depth+1,nodes)+operations(b.right(),w,depth+1,nodes) : 0;
    }
    static FiniteIterationOptimizer.Cost cost(FiniteIteration source, Expr emitted, Work w) {
        long perIteration=source instanceof FiniteIteration.Sum s ? 1+operations(s.term(),w) : operations(((FiniteIteration.Recurrence)source).update(),w);
        long initial=source instanceof FiniteIteration.Recurrence r ? operations(r.initial(),w) : 0;
        return new FiniteIterationOptimizer.Cost(source.range().count().multiply(BigInteger.valueOf(perIteration)).add(BigInteger.valueOf(initial)),operations(emitted,w));
    }
}
