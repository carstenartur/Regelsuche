package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Exact rational fragment N(parameters)/(a*(i+b(parameters))*(i+b(parameters)+stride)).
 * a is a nonzero rational constant. No Java division semantics are represented by this API.
 * The checker proves a cross-multiplication identity and a complete affine pole factorization. */
public final class RationalTelescoping {
    private RationalTelescoping() {}
    public record Source(SymbolId index,Set<SymbolId> parameters,Expr start,BigInteger stride,
                         Expr numerator,Expr denominator,ExactRational initial) {
        public Source {
            Objects.requireNonNull(initial); parameters=Set.copyOf(parameters);
            // Reuse bounded structural/scoping validation without assigning integer meaning to the quotient.
            new PolynomialSum(index,parameters,start,stride,new BinaryExpr(numerator,BinaryOperator.ADD,denominator),new NumberExpr(initial),IntegerArithmetic.EXACT);
        }
    }
    public record SearchResult(Optional<Plan> plan,String reason,long work) {}
    public record Verification(boolean verified,String reason,long work) {}
    private static Work work(OptimizationBudget b,CancellationToken c) { return new Work(b,c,PolynomialSum.MAX_TOTAL_DEGREE); }
    private static Set<SymbolId> symbols(Source s) { var ids=new HashSet<>(s.parameters()); ids.add(s.index()); return ids; }
    private static IterationPolynomial coefficient(IterationPolynomial p,SymbolId index,int power) {
        var r=zero(p.work);
        for(var e:p.terms.entrySet()) if(e.getKey().degree(index)==power)
            r=r.add(single(e.getKey().without(index),e.getValue(),p.work));
        return r;
    }
    private static ExactRational nonzeroConstant(IterationPolynomial p) {
        p.work.charge(p.terms.size()+1);
        if(p.terms.size()!=1) throw new Unavailable("NONCONSTANT_LEADING_COEFFICIENT");
        var entry=p.terms.entrySet().iterator().next();
        if(!entry.getKey().symbols().isEmpty()) throw new Unavailable("NONCONSTANT_LEADING_COEFFICIENT");
        return entry.getValue();
    }
    private static void source(Source source,Work w) {
        var start=SeriesSupport.parse(source.start(),source.parameters(),w); SeriesSupport.integerValued(start);
        var numerator=SeriesSupport.parse(source.numerator(),symbols(source),w);
        if(!numerator.independent(source.index())) throw new Unavailable("INDEX_DEPENDENT_NUMERATOR");
        var denominator=SeriesSupport.parse(source.denominator(),symbols(source),w);
        if(SeriesSupport.degree(denominator,source.index())!=2) throw new Unavailable("NONQUADRATIC_DENOMINATOR");
        nonzeroConstant(coefficient(denominator,source.index(),2)); w.coefficient(source.initial());
    }
    public static SearchResult search(Source source,OptimizationBudget budget,CancellationToken cancellation) {
        Objects.requireNonNull(source); var w=work(budget,cancellation);
        try {
            source(source,w); var ids=symbols(source);
            var denominator=SeriesSupport.parse(source.denominator(),ids,w);
            var a=nonzeroConstant(coefficient(denominator,source.index(),2));
            var linear=coefficient(denominator,source.index(),1).scale(ExactRational.ONE.divide(a));
            var intercept=linear.subtract(constant(source.stride(),w)).scale(ExactRational.ONE.divide(ExactRational.integer(2)));
            var factor=variable(source.index(),w).add(intercept);
            var scale=a.multiply(ExactRational.integer(source.stride()));
            var numerator=SeriesSupport.parse(source.numerator(),ids,w).scale(ExactRational.NEGATIVE_ONE.divide(scale));
            var plan=new Plan(source,numerator.expression(),factor.expression());
            check(source,plan,w);
            return new SearchResult(Optional.of(plan),"CANDIDATE",w.used);
        } catch(Unavailable e) { return new SearchResult(Optional.empty(),e.getMessage(),w.used); }
    }
    /** Primitive numerator and denominator are the emitted rational boundary expression. */
    public record Plan(Source source,Expr primitiveNumerator,Expr primitiveDenominator) {
        public Plan { Objects.requireNonNull(source); Objects.requireNonNull(primitiveNumerator); Objects.requireNonNull(primitiveDenominator); }
        public ExactRational evaluate(BigInteger count,Map<SymbolId,BigInteger> parameters,OptimizationBudget budget,CancellationToken cancellation) {
            var w=work(budget,cancellation);
            try {
                check(source,this,w); SeriesSupport.bits(count,w);
                if(count.signum()<0) throw new Unavailable("NEGATIVE_COUNT");
                if(count.signum()==0) return source.initial();
                var start=SeriesSupport.value(SeriesSupport.parse(source.start(),source.parameters(),w),parameters);
                var end=start.add(source.stride().multiply(count)); SeriesSupport.bits(end,w);
                var p=SeriesSupport.parse(primitiveNumerator,symbols(source),w);
                var q=SeriesSupport.parse(primitiveDenominator,symbols(source),w);
                var slope=nonzeroConstant(coefficient(q,source.index(),1));
                var values=new HashMap<>(parameters); values.put(source.index(),start);
                var first=SeriesSupport.rationalValue(q,values);
                // Q(start+k*stride)=0 iff k=-Q(start)/(slope*stride), checked exactly.
                var root=SeriesSupport.bounded(first.negate().divide(slope.multiply(ExactRational.integer(source.stride()))),w);
                if(root.isInteger() && root.signum()>=0 && root.numerator().compareTo(count)<=0) throw new Unavailable("POLE_IN_RANGE");
                var left=SeriesSupport.bounded(SeriesSupport.rationalValue(p,values).divide(first),w);
                values.put(source.index(),end);
                var right=SeriesSupport.bounded(SeriesSupport.rationalValue(p,values).divide(SeriesSupport.rationalValue(q,values)),w);
                return SeriesSupport.bounded(source.initial().add(right).subtract(left),w);
            } catch(Unavailable e) { throw new IllegalArgumentException(e.getMessage(),e); }
        }
    }
    public static Verification verify(Source source,Plan plan,OptimizationBudget budget,CancellationToken cancellation) {
        Objects.requireNonNull(source); Objects.requireNonNull(plan); var w=work(budget,cancellation);
        try { check(source,plan,w); return new Verification(true,"VERIFIED_RATIONAL_TELESCOPE",w.used); }
        catch(Unavailable e) { return new Verification(false,e.getMessage(),w.used); }
    }
    private static void check(Source source,Plan plan,Work w) {
        w.charge(6L*FiniteIteration.MAX_NODES);
        if(!source.equals(plan.source())) throw new Unavailable("SOURCE_MISMATCH");
        source(source,w); var ids=symbols(source);
        var p=SeriesSupport.parse(plan.primitiveNumerator(),ids,w);
        var q=SeriesSupport.parse(plan.primitiveDenominator(),ids,w);
        if(SeriesSupport.degree(q,source.index())!=1) throw new Unavailable("NONAFFINE_PRIMITIVE_DENOMINATOR");
        var slope=nonzeroConstant(coefficient(q,source.index(),1));
        var ps=p.shifted(source.index(),source.stride()); var qs=q.shifted(source.index(),source.stride());
        var n=SeriesSupport.parse(source.numerator(),ids,w); var d=SeriesSupport.parse(source.denominator(),ids,w);
        var a=nonzeroConstant(coefficient(d,source.index(),2));
        // Establish that Q and its shift account for every source pole, including zero-numerator cases.
        if(!d.same(q.multiply(qs).scale(a.divide(slope.multiply(slope))))) throw new Unavailable("DENOMINATOR_FACTORIZATION_MISMATCH");
        var left=ps.multiply(q).subtract(p.multiply(qs)).multiply(d);
        var right=n.multiply(qs).multiply(q);
        if(!left.same(right)) throw new Unavailable("RATIONAL_IDENTITY_MISMATCH");
    }
}
