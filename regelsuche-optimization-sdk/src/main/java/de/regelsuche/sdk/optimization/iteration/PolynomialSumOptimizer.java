package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Newton forward differences, bounded by polynomial degree, independent of runtime count. */
public final class PolynomialSumOptimizer {
    public record SearchResult(Optional<PolynomialSumPlan> plan,String reason,long work) {}
    public SearchResult search(PolynomialSum source,OptimizationBudget budget,CancellationToken cancellation) {
        Objects.requireNonNull(source); var w=new Work(budget,cancellation,PolynomialSum.MAX_TOTAL_DEGREE);
        try { return new SearchResult(Optional.of(create(source,w)),"CANDIDATE",w.used); }
        catch(Unavailable e) { return new SearchResult(Optional.empty(),e.getMessage(),w.used); }
    }
    static PolynomialSumPlan create(PolynomialSum source,Work w) {
            var difference=SeriesSupport.source(source,w); int d=SeriesSupport.degree(difference,source.index());
            var coefficients=new ArrayList<Expr>();
            for(int j=0;j<=d;j++) {
                var coefficient=difference.at(source.index(),BigInteger.ZERO);
                SeriesSupport.integerValued(coefficient);
                var expression=coefficient.expression();
                SeriesSupport.parse(expression,source.parameters(),w);
                coefficients.add(expression);
                if(j<d) difference=difference.shifted(source.index(),BigInteger.ONE).subtract(difference);
            }
            return new PolynomialSumPlan(source,source.initial(),coefficients);
    }
}
