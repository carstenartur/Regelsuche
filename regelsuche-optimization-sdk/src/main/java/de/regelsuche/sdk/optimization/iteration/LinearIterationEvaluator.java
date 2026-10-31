package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.LinearIterationAlgebra.*;

/** Reference evaluator using bounded binary powering, never iteration-count source unrolling. */
public final class LinearIterationEvaluator {
    public record Evaluation(Optional<List<BigInteger>> values,String reason,long work) {
        public Evaluation { values=values.map(List::copyOf); }
    }
    public Evaluation evaluate(LinearIteration source,LinearIterationOptimizer.Candidate candidate,
            Map<SymbolId,BigInteger> bindings,OptimizationBudget budget,CancellationToken cancellation) {
        var w=new Work(budget,cancellation);
        try {
            LinearIterationVerifier.check(source,candidate,w);
            return new Evaluation(Optional.of(power(source,candidate.transition(),bindings,w)),"EVALUATED_LINEAR_TRANSITION",w.used);
        } catch(Unavailable ex) { return new Evaluation(Optional.empty(),ex.getMessage(),w.used); }
    }
    static List<BigInteger> power(LinearIteration source,LinearTransition t,Map<SymbolId,BigInteger> bindings,Work w) {
        Objects.requireNonNull(bindings);
        var count=eval(source.count(),bindings,IntegerArithmetic.EXACT,w);
        if(count.signum()<0) throw new Unavailable("NEGATIVE_COUNT");
        var ring=source.arithmetic(); int dimension=t.matrix().size();
        var values=new ArrayList<BigInteger>();
        // Incoming states are preserved on an empty range without evaluating index or update coefficients.
        if(count.signum()==0) {
            for(var state:source.states()) values.add(eval(state.initial(),bindings,ring,w));
            return List.copyOf(values);
        }
        for(var initial:t.initial()) values.add(eval(initial,bindings,ring,w));
        var matrix=new BigInteger[dimension][dimension];
        for(int i=0;i<dimension;i++) for(int j=0;j<dimension;j++) matrix[i][j]=eval(t.matrix().get(i).get(j),bindings,ring,w);
        while(count.signum()>0) {
            w.charge();
            if(count.testBit(0)) {
                var next=new ArrayList<BigInteger>();
                for(int i=0;i<dimension;i++) {
                    var value=BigInteger.ZERO;
                    for(int j=0;j<dimension;j++) value=w.add(value,w.multiply(matrix[i][j],values.get(j),ring),ring);
                    next.add(value);
                }
                values=next;
            }
            count=count.shiftRight(1);
            if(count.signum()>0) matrix=square(matrix,ring,w);
        }
        return List.copyOf(values.subList(0,source.states().size()));
    }
    private static BigInteger[][] square(BigInteger[][] matrix,IntegerArithmetic ring,Work w) {
        int n=matrix.length; var result=new BigInteger[n][n];
        for(int i=0;i<n;i++) for(int j=0;j<n;j++) {
            var value=BigInteger.ZERO;
            for(int k=0;k<n;k++) value=w.add(value,w.multiply(matrix[i][k],matrix[k][j],ring),ring);
            result[i][j]=value;
        }
        return result;
    }
}
