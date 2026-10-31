package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.sdk.optimization.*;
import java.util.Objects;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.*;

/** Independent telescoping checker: never invokes candidate synthesis or executes the source loop. */
public final class FiniteIterationVerifier {
    /** cost is null on failure; work is this verification's bounded accounting, never candidate data. */
    public record Verification(boolean verified, String reason, long work, FiniteIterationOptimizer.Cost cost) {}
    public Verification verify(FiniteIteration source, FiniteIterationOptimizer.Candidate candidate,
                               OptimizationBudget budget, CancellationToken cancellation) {
        Objects.requireNonNull(candidate);
        return verify(source,candidate.result(),candidate.certificate(),budget,cancellation);
    }
    /** Recheck the actual emitted scalar expression, allowing any supported equivalent ring polynomial. */
    public Verification verify(FiniteIteration source, Expr emitted, FiniteIterationOptimizer.Certificate certificate,
                               OptimizationBudget budget, CancellationToken cancellation) {
        Objects.requireNonNull(source); Objects.requireNonNull(emitted); Objects.requireNonNull(certificate);
        var work=new Work(budget,cancellation);
        try {
            work.charge(1);
            // Both immutable sources were bounded and scope-checked at construction. Debit the
            // worst-case two scalar trees before structural comparison; leaf sizes are bounded too.
            work.charge(2L*FiniteIteration.MAX_NODES);
            if(!source.equals(certificate.source())) throw new Unavailable("SOURCE_MISMATCH");
            var parsed=source(source,work);
            var primitive=parse(certificate.antidifference(),symbols(source,false),false,MAX_DEGREE+1,work);
            var delta=primitive.shifted(source.index(),source.range().step()).subtract(primitive);
            if(!delta.same(parsed.term())) throw new Unavailable("ANTIDIFFERENCE_MISMATCH");
            var expected=primitive.at(source.index(),source.range().endExclusive())
                .subtract(primitive.at(source.index(),source.range().start())).add(parsed.initial());
            // Validate integral coefficients BEFORE modular reduction, including emitted leaves.
            var expectedRing=expected.integral(source.arithmetic());
            var actual=parse(emitted,source.parameters(),true,MAX_DEGREE,work).integral(source.arithmetic());
            if(!expectedRing.same(actual)) throw new Unavailable("RESULT_MISMATCH");
            var cost=cost(source,emitted,work);
            return new Verification(true,"VERIFIED_TELESCOPING",work.used,cost);
        } catch(Unavailable unavailable) { return new Verification(false,unavailable.getMessage(),work.used,null); }
    }
}
