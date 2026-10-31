package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import static de.regelsuche.sdk.optimization.iteration.LinearIterationAlgebra.*;

/** Checks original update identities and actual supplied results without calling candidate search. */
public final class LinearIterationVerifier {
    public record Verification(boolean verified,String reason,long work) {}
    public Verification verify(LinearIteration source,LinearIterationOptimizer.Candidate candidate,
            OptimizationBudget budget,CancellationToken cancellation) {
        var w=new Work(budget,cancellation);
        try { check(source,candidate,w); return new Verification(true,"VERIFIED_LINEAR_TRANSITION",w.used); }
        catch(Unavailable ex) { return new Verification(false,ex.getMessage(),w.used); }
    }
    public Verification verifyResult(LinearIteration source,LinearIterationOptimizer.Candidate candidate,
            Map<SymbolId,BigInteger> bindings,List<BigInteger> emitted,OptimizationBudget budget,CancellationToken cancellation) {
        var w=new Work(budget,cancellation);
        try {
            check(source,candidate,w);
            if(emitted.size()!=source.states().size()) throw new Unavailable("RESULT_DIMENSION");
            for(var n:emitted) w.checked(Objects.requireNonNull(n));
            var actual=LinearIterationEvaluator.power(source,candidate.transition(),bindings,w);
            if(!actual.equals(emitted)) throw new Unavailable("RESULT_MISMATCH");
            return new Verification(true,"VERIFIED_LINEAR_RESULT",w.used);
        } catch(Unavailable ex) { return new Verification(false,ex.getMessage(),w.used); }
    }
    static void check(LinearIteration source,LinearIterationOptimizer.Candidate candidate,Work w) {
        Objects.requireNonNull(source); Objects.requireNonNull(candidate); w.charge();
        // Every transported tree is constructor-bounded; charge an upper bound before structural equality.
        w.charge((long)LinearIteration.MAX_NODES*(2L*source.states().size()+3));
        if(!source.equals(candidate.source())) throw new Unavailable("SOURCE_MISMATCH");
        var t=candidate.transition(); int n=source.states().size(),dimension=t.matrix().size();
        if(t.stateCount()!=n) throw new Unavailable("STATE_DIMENSION");
        if(dimension>w.budget.maximumStates()) throw new Unavailable("STATE_LIMIT");
        var paramEnv=environment(source,w,false);
        parse(source.count(),paramEnv,source,w);
        var basis=basis(source,w,t.indexDegree());
        var actualRows=new ArrayList<Poly>();
        for(var row:t.matrix()) {
            var value=constant(source,w,BigInteger.ZERO);
            for(int j=0;j<dimension;j++) value=value.add(parse(row.get(j),paramEnv,source,w).multiply(basis.get(j)));
            actualRows.add(value);
        }
        var old=environment(source,w,true); var seen=new HashMap<>(old);
        for(var update:source.updates()) {
            int coordinate=0; while(!source.states().get(coordinate).symbol().equals(update.state())) coordinate++;
            var expected=parse(update.expression(),source.order()==LinearIteration.UpdateOrder.SEQUENTIAL?seen:old,source,w);
            if(!expected.terms.equals(actualRows.get(coordinate).terms)) throw new Unavailable("TRANSITION_MISMATCH");
            // Inductively verified prior coordinates are the values visible to later assignments.
            seen.put(update.state(),actualRows.get(coordinate));
        }
        var expectedPower=constant(source,w,BigInteger.ONE);
        var nextIndex=variable(source,w,source.index()).add(constant(source,w,source.indexStep()));
        for(int k=0;k<=t.indexDegree();k++) {
            if(!expectedPower.terms.equals(actualRows.get(n+k).terms)) throw new Unavailable("INDEX_TRANSITION_MISMATCH");
            if(k<t.indexDegree()) expectedPower=expectedPower.multiply(nextIndex);
        }
        // Check initial values independently, including every augmentation coordinate.
        for(int k=0;k<n;k++) same(parse(source.states().get(k).initial(),paramEnv,source,w),parse(t.initial().get(k),paramEnv,source,w));
        var initialPower=constant(source,w,BigInteger.ONE);
        Poly start=t.indexDegree()>0?parse(source.indexStart(),paramEnv,source,w):null;
        for(int k=0;k<=t.indexDegree();k++) {
            same(initialPower,parse(t.initial().get(n+k),paramEnv,source,w));
            if(k<t.indexDegree()) initialPower=initialPower.multiply(start);
        }
    }
    private static void same(Poly expected,Poly actual) {
        if(!expected.terms.equals(actual.terms)) throw new Unavailable("INITIAL_MISMATCH");
    }
}
