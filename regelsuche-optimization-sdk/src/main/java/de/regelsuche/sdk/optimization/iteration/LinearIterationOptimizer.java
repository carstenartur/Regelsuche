package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.LinearIterationAlgebra.*;

/** Constructs one bounded, division-free transition candidate; verification is separate. */
public final class LinearIterationOptimizer {
    public record Candidate(LinearIteration source,LinearTransition transition,long work) {
        public Candidate { Objects.requireNonNull(source); Objects.requireNonNull(transition); }
    }
    public record SearchResult(Optional<Candidate> candidate,String reason,long work) {}
    public SearchResult search(LinearIteration source,OptimizationBudget budget,CancellationToken cancellation) {
        Objects.requireNonNull(source); var w=new Work(budget,cancellation);
        try {
            w.charge();
            var original=environment(source,w,true); var current=new HashMap<>(original);
            parse(source.count(),environment(source,w,false),source,w);
            for(var u:source.updates()) {
                var env=source.order()==LinearIteration.UpdateOrder.SEQUENTIAL?current:original;
                current.put(u.state(),parse(u.expression(),env,source,w));
            }
            int degree=0;
            for(var state:source.states()) for(var m:current.get(state.symbol()).terms.keySet()) degree=Math.max(degree,m.degree(source.index()));
            if(source.states().size()+degree+1>budget.maximumStates()) throw new Unavailable("STATE_LIMIT");
            var matrix=new ArrayList<List<Expr>>();
            for(var state:source.states()) matrix.add(row(current.get(state.symbol()),degree));
            var power=constant(source,w,BigInteger.ONE);
            var shifted=variable(source,w,source.index()).add(constant(source,w,source.indexStep()));
            for(int k=0;k<=degree;k++) {
                matrix.add(row(power,degree));
                if(k<degree) power=power.multiply(shifted);
            }
            var transition=new LinearTransition(matrix,initial(source,w,degree),source.states().size(),degree);
            return new SearchResult(Optional.of(new Candidate(source,transition,w.used)),"CANDIDATE",w.used);
        } catch(Unavailable ex) { return new SearchResult(Optional.empty(),ex.getMessage(),w.used); }
    }
}
