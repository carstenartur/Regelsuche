package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.*;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;

/** Symbolic start and fixed stride; runtime count is supplied separately and must be nonnegative. */
public record PolynomialSum(SymbolId index, Set<SymbolId> parameters, Expr start, BigInteger stride,
                            Expr term, Expr initial, IntegerArithmetic arithmetic) {
    public static final int MAX_INDEX_DEGREE=8, MAX_TOTAL_DEGREE=32, MAX_RUNTIME_BITS=65536;
    public PolynomialSum {
        Objects.requireNonNull(index); Objects.requireNonNull(stride); Objects.requireNonNull(arithmetic);
        if(parameters.size()>FiniteIteration.MAX_PARAMETERS) throw new IllegalArgumentException("TOO_MANY_PARAMETERS");
        parameters=Set.copyOf(parameters);
        if(parameters.contains(index)) throw new IllegalArgumentException("COLLIDING_BINDERS");
        if(stride.signum()==0 || stride.bitLength()>FiniteIteration.MAX_COEFFICIENT_BITS) throw new IllegalArgumentException("INVALID_STRIDE");
        var range=new IndexRange(BigInteger.ZERO,stride,BigInteger.ZERO);
        new FiniteIteration.Sum(range,index,parameters,term,arithmetic);
        // Existing scope validator permits the index in terms; explicitly forbid it in bound/initial.
        new FiniteIteration.Sum(range,index,parameters,start,arithmetic);
        new FiniteIteration.Sum(range,index,parameters,initial,arithmetic);
        SeriesSupport.forbid(start,index); SeriesSupport.forbid(initial,index);
    }
}
