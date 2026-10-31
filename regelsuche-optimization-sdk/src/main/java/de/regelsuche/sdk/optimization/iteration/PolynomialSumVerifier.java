package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.sdk.optimization.*;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Independent Newton reconstruction certificate checker; never invokes synthesis. */
public final class PolynomialSumVerifier {
    public record Verification(boolean verified,String reason,long work) {}
    public Verification verify(PolynomialSum source,PolynomialSumPlan plan,OptimizationBudget budget,CancellationToken cancellation) {
        Objects.requireNonNull(source); Objects.requireNonNull(plan); var w=new Work(budget,cancellation,PolynomialSum.MAX_TOTAL_DEGREE);
        try { check(source,plan,w); return new Verification(true,"VERIFIED_NEWTON_SUM",w.used); }
        catch(Unavailable e) { return new Verification(false,e.getMessage(),w.used); }
    }
    static void check(PolynomialSum source,PolynomialSumPlan plan,Work w) {
        w.charge(6L*FiniteIteration.MAX_NODES);
        if(!source.equals(plan.source())) throw new Unavailable("SOURCE_MISMATCH");
        var expected=SeriesSupport.source(source,w);
        var initial=SeriesSupport.parse(plan.initial(),source.parameters(),w); SeriesSupport.integerValued(initial);
        if(!initial.same(SeriesSupport.parse(source.initial(),source.parameters(),w))) throw new Unavailable("INITIAL_MISMATCH");
        var actual=zero(w); var x=variable(source.index(),w);
        for(int j=0;j<plan.coefficients().size();j++) {
            var coefficient=SeriesSupport.parse(plan.coefficients().get(j),source.parameters(),w); SeriesSupport.integerValued(coefficient);
            actual=actual.add(coefficient.multiply(SeriesSupport.binomial(x,j)));
        }
        if(!actual.same(expected)) throw new Unavailable("COEFFICIENT_MISMATCH");
    }
}
