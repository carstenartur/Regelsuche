package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Bounded exact finite compositions. All child proofs and runtime arithmetic share one work ledger. */
public final class PolynomialSeries {
    public static final int MAX_PARTS=32;
    private PolynomialSeries() {}
    private static Work work(OptimizationBudget b,CancellationToken c) { return new Work(b,c,PolynomialSum.MAX_TOTAL_DEGREE); }
    private static void count(BigInteger count,Work w) {
        SeriesSupport.bits(count,w); if(count.signum()<0) throw new Unavailable("NEGATIVE_COUNT");
    }
    /** Inner count is affine in the outer index; its parameter coefficients may be polynomial.
     * Runtime endpoint checks establish nonnegativity over every executed inner range.
     * Inner initial is included on every outer iteration, including empty inner ranges. */
    public record NestedSum(PolynomialSum inner,Expr innerCount,SymbolId index,Set<SymbolId> parameters,
                            Expr start,BigInteger stride,Expr initial,IntegerArithmetic arithmetic) {
        public NestedSum {
            Objects.requireNonNull(inner); Objects.requireNonNull(innerCount); parameters=Set.copyOf(parameters);
            new PolynomialSum(index,parameters,start,stride,innerCount,initial,arithmetic);
            var symbols=new HashSet<>(parameters); symbols.add(index);
            if(index.equals(inner.index()) || !symbols.containsAll(inner.parameters())) throw new IllegalArgumentException("NESTED_SCOPE_MISMATCH");
            if(arithmetic!=IntegerArithmetic.EXACT || inner.arithmetic()!=IntegerArithmetic.EXACT) throw new IllegalArgumentException("NESTED_REQUIRES_EXACT");
        }
    }
    public static NestedPlan nested(NestedSum source,OptimizationBudget budget,CancellationToken cancellation) {
        var w=work(budget,cancellation);
        try {
            var inner=PolynomialSumOptimizer.create(source.inner(),w);
            var outer=PolynomialSumOptimizer.create(outerSource(source,inner,w),w);
            return new NestedPlan(source,inner,outer);
        } catch(Unavailable e) { throw new IllegalArgumentException(e.getMessage(),e); }
    }
    private static PolynomialSum outerSource(NestedSum source,PolynomialSumPlan inner,Work w) {
        PolynomialSumVerifier.check(source.inner(),inner,w);
        var ids=new HashSet<>(source.parameters()); ids.add(source.index());
        var bound=SeriesSupport.parse(source.innerCount(),ids,w); SeriesSupport.integerValued(bound);
        if(SeriesSupport.degree(bound,source.index())>1) throw new Unavailable("NONAFFINE_INNER_COUNT");
        var term=SeriesSupport.parse(inner.initial(),ids,w);
        for(int j=0;j<inner.coefficients().size();j++)
            term=term.add(SeriesSupport.parse(inner.coefficients().get(j),ids,w).multiply(SeriesSupport.binomial(bound,j+1)));
        SeriesSupport.integerValued(term);
        var expression=term.expression();
        // Reject generated transport limits through Unavailable before the model constructor.
        // This keeps verifier failures structured without catching caller or programming errors.
        SeriesSupport.parse(expression,ids,w);
        return new PolynomialSum(source.index(),source.parameters(),source.start(),source.stride(),expression,source.initial(),source.arithmetic());
    }
    public record NestedPlan(NestedSum source,PolynomialSumPlan inner,PolynomialSumPlan outer) {
        public NestedPlan { Objects.requireNonNull(source); Objects.requireNonNull(inner); Objects.requireNonNull(outer); }
        private void check(Work w) {
            var expected=outerSource(source,inner,w);
            PolynomialSumVerifier.check(expected,outer,w);
        }
        public PolynomialSumVerifier.Verification verify(OptimizationBudget b,CancellationToken c) {
            var w=work(b,c);
            try { check(w); return new PolynomialSumVerifier.Verification(true,"VERIFIED_NESTED_SUM",w.used); }
            catch(Unavailable e) { return new PolynomialSumVerifier.Verification(false,e.getMessage(),w.used); }
        }
        public BigInteger evaluate(BigInteger count,Map<SymbolId,BigInteger> parameters,OptimizationBudget b,CancellationToken c) {
            var w=work(b,c);
            try {
                check(w); count(count,w);
                if(count.signum()>0) {
                    var start=SeriesSupport.value(SeriesSupport.parse(source.start(),source.parameters(),w),parameters);
                    var end=start.add(count.subtract(BigInteger.ONE).multiply(source.stride())); SeriesSupport.bits(end,w);
                    var ids=new HashSet<>(source.parameters()); ids.add(source.index());
                    var bound=SeriesSupport.parse(source.innerCount(),ids,w);
                    var values=new HashMap<>(parameters); boolean allEmpty=true;
                    for(var point:List.of(start,end)) {
                        values.put(source.index(),point);
                        var innerCount=SeriesSupport.value(bound,values);
                        if(innerCount.signum()<0) throw new Unavailable("NEGATIVE_INNER_COUNT");
                        allEmpty &= innerCount.signum()==0;
                    }
                    if(allEmpty) {
                        var initialOnly=new PolynomialSum(source.index(),source.parameters(),source.start(),source.stride(),source.inner().initial(),source.initial(),source.arithmetic());
                        return PolynomialSumOptimizer.create(initialOnly,w).evaluate(count,parameters,w);
                    }
                }
                return outer.evaluate(count,parameters,w);
            } catch(Unavailable e) { throw new IllegalArgumentException(e.getMessage(),e); }
        }
    }
    /** Residue r receives ceil((count-r)/period) terms, clamped at zero.
     * Child source starts/strides describe that residue's local progression. Child initial values
     * are additive incoming states and included exactly once, even for an empty residue. */
    public record PeriodicPlan(List<PolynomialSumPlan> residues) {
        public PeriodicPlan { residues=parts(residues); sameArithmetic(residues); }
        public BigInteger evaluate(BigInteger count,Map<SymbolId,BigInteger> parameters,OptimizationBudget b,CancellationToken c) {
            var w=work(b,c);
            try {
                count(count,w); var result=BigInteger.ZERO; var period=BigInteger.valueOf(residues.size());
                for(int r=0;r<residues.size();r++) {
                    var local=count.add(period).subtract(BigInteger.valueOf(r+1)).divide(period).max(BigInteger.ZERO);
                    result=result.add(residues.get(r).evaluate(local,parameters,w)); SeriesSupport.bits(result,w);
                }
                return residues.getFirst().source().arithmetic().normalize(result);
            } catch(Unavailable e) { throw new IllegalArgumentException(e.getMessage(),e); }
        }
    }
    /** A contiguous ordinal segment ends here (exclusive). Null denotes an unbounded final segment. */
    public record Piece(BigInteger endExclusive,PolynomialSumPlan plan) { public Piece { Objects.requireNonNull(plan); } }
    /** Pieces implicitly begin at the previous endpoint, initially zero: no gaps or overlap.
     * Each child progression starts locally at ordinal zero; initial states are added once each. */
    public record PiecewisePlan(List<Piece> pieces) {
        public PiecewisePlan {
            pieces=parts(pieces); var previous=BigInteger.ZERO;
            for(int j=0;j<pieces.size();j++) {
                var end=pieces.get(j).endExclusive();
                if(end==null) { if(j!=pieces.size()-1) throw new IllegalArgumentException("NONFINAL_UNBOUNDED_PIECE"); }
                else {
                    if(end.bitLength()>PolynomialSum.MAX_RUNTIME_BITS || end.compareTo(previous)<=0) throw new IllegalArgumentException("INVALID_PARTITION");
                    previous=end;
                }
            }
            sameArithmetic(pieces.stream().map(Piece::plan).toList());
        }
        public BigInteger evaluate(BigInteger count,Map<SymbolId,BigInteger> parameters,OptimizationBudget b,CancellationToken c) {
            var w=work(b,c);
            try {
                count(count,w); var last=pieces.getLast().endExclusive();
                if(last!=null && count.compareTo(last)>0) throw new Unavailable("UNCOVERED_RANGE");
                var result=BigInteger.ZERO; var start=BigInteger.ZERO;
                for(var piece:pieces) {
                    var end=piece.endExclusive()==null?count:count.min(piece.endExclusive());
                    var local=end.subtract(start).max(BigInteger.ZERO);
                    result=result.add(piece.plan().evaluate(local,parameters,w)); SeriesSupport.bits(result,w);
                    if(piece.endExclusive()!=null) start=piece.endExclusive();
                }
                return pieces.getFirst().plan().source().arithmetic().normalize(result);
            } catch(Unavailable e) { throw new IllegalArgumentException(e.getMessage(),e); }
        }
    }
    private static <T> List<T> parts(List<T> parts) {
        if(parts.isEmpty() || parts.size()>MAX_PARTS) throw new IllegalArgumentException("PART_COUNT_LIMIT");
        return List.copyOf(parts);
    }
    private static void sameArithmetic(List<PolynomialSumPlan> plans) {
        var arithmetic=plans.getFirst().source().arithmetic();
        if(plans.stream().anyMatch(p->p.source().arithmetic()!=arithmetic)) throw new IllegalArgumentException("ARITHMETIC_MISMATCH");
    }
}
