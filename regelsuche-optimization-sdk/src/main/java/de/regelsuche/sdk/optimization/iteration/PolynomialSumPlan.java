package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Immutable untrusted transport: initial + sum_j coefficients[j] binomial(count,j+1). */
public record PolynomialSumPlan(PolynomialSum source,Expr initial,List<Expr> coefficients) {
    public PolynomialSumPlan {
        Objects.requireNonNull(source); Objects.requireNonNull(initial);
        if(coefficients.size()>PolynomialSum.MAX_INDEX_DEGREE+1) throw new IllegalArgumentException("COEFFICIENT_COUNT_LIMIT");
        coefficients=List.copyOf(coefficients);
    }
    /** Independently validates this plan before bounded evaluation; zero count avoids unused parameter reads. */
    public BigInteger evaluate(BigInteger count,Map<SymbolId,BigInteger> parameters,OptimizationBudget budget,CancellationToken cancellation) {
        Objects.requireNonNull(count); Objects.requireNonNull(parameters);
        var w=new Work(budget,cancellation,PolynomialSum.MAX_TOTAL_DEGREE);
        try { return evaluate(count,parameters,w); }
        catch(Unavailable e) { throw new IllegalArgumentException(e.getMessage(),e); }
    }
    BigInteger evaluate(BigInteger count,Map<SymbolId,BigInteger> parameters,Work w) {
            PolynomialSumVerifier.check(source,this,w);
            SeriesSupport.bits(count,w); if(count.signum()<0) throw new Unavailable("NEGATIVE_COUNT");
            var result=SeriesSupport.value(SeriesSupport.parse(initial,source.parameters(),w),parameters);
            if(count.signum()==0) return source.arithmetic().normalize(result);
            var choose=BigInteger.ONE;
            for(int j=0;j<coefficients.size();j++) {
                choose=choose.multiply(count.subtract(BigInteger.valueOf(j))).divide(BigInteger.valueOf(j+1)); SeriesSupport.bits(choose,w);
                if(choose.signum()!=0) {
                    var coefficient=SeriesSupport.value(SeriesSupport.parse(coefficients.get(j),source.parameters(),w),parameters);
                    var product=coefficient.multiply(choose); SeriesSupport.bits(product,w);
                    result=result.add(product); SeriesSupport.bits(result,w);
                }
            }
            return source.arithmetic().normalize(result);
    }
}
