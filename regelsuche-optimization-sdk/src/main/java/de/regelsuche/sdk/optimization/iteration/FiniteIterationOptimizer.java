package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Bounded candidate search; only FiniteIterationVerifier can establish the identity. */
public final class FiniteIterationOptimizer {
    /** Source-bound rational antidifference. Public construction permits independent transport/checking. */
    public record Certificate(FiniteIteration source, Expr antidifference) {
        public Certificate { Objects.requireNonNull(source); Objects.requireNonNull(antidifference); }
    }
    /** Arithmetic-operation estimates only: no loop-control, loads or compiler/runtime cost claims. */
    public record Cost(BigInteger originalOperations, long resultOperations) {
        public Cost {
            Objects.requireNonNull(originalOperations);
            if(originalOperations.signum()<0 || resultOperations<0) throw new IllegalArgumentException("NEGATIVE_COST");
        }
    }
    /** Search work and cost are informational; the checker recomputes its own values. */
    public record Candidate(Expr result, Certificate certificate, long work, Cost cost) {
        public Candidate { Objects.requireNonNull(result); Objects.requireNonNull(certificate); Objects.requireNonNull(cost); }
    }
    public record SearchResult(Optional<Candidate> candidate, String reason, long work) {
        public SearchResult { Objects.requireNonNull(candidate); Objects.requireNonNull(reason); }
    }
    public SearchResult search(FiniteIteration source, OptimizationBudget budget, CancellationToken cancellation) {
        Objects.requireNonNull(source);
        var work=new Work(budget,cancellation);
        try {
            var parsed=source(source,work);
            var remaining=parsed.term(); var antidifference=zero(work);
            // Triangular elimination in the index degree; no range unrolling or stored sum formulas.
            int rounds=0;
            while(!remaining.terms.isEmpty()) {
                work.charge(remaining.terms.size());
                if(++rounds>MAX_TERMS*(MAX_DEGREE+1)) throw new Unavailable("SYNTHESIS_LIMIT");
                var leading=remaining.terms.entrySet().stream().max(Comparator.comparingInt(e -> e.getKey().degree(source.index()))).orElseThrow();
                int degree=leading.getKey().degree(source.index())+1;
                var denominator=ExactRational.integer(source.range().step().multiply(BigInteger.valueOf(degree)));
                var primitive=single(leading.getKey(),leading.getValue().divide(denominator),work).multiply(variable(source.index(),work));
                antidifference=antidifference.add(primitive);
                remaining=remaining.subtract(primitive.shifted(source.index(),source.range().step()).subtract(primitive));
            }
            var value=antidifference.at(source.index(),source.range().endExclusive())
                .subtract(antidifference.at(source.index(),source.range().start())).add(parsed.initial()).integral(source.arithmetic());
            Expr result=value.expression(), proof=antidifference.expression();
            // Generated trees obey the same transport limits as untrusted inputs to the checker.
            parse(proof,symbols(source,false),false,MAX_DEGREE+1,work);
            parse(result,source.parameters(),true,MAX_DEGREE,work);
            var cost=cost(source,result,work);
            var candidate=new Candidate(result,new Certificate(source,proof),work.used,cost);
            return new SearchResult(Optional.of(candidate),"CANDIDATE",work.used);
        } catch(Unavailable unavailable) { return new SearchResult(Optional.empty(),unavailable.getMessage(),work.used); }
    }
}
