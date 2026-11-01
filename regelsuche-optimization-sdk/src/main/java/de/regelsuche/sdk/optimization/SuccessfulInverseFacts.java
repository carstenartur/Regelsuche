package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.*;

/** One bounded conditional inverse relation, bound to its exact original trace occurrence. */
final class SuccessfulInverseFacts {
    private final VerificationWork work;
    private Expr inverse;
    SuccessfulInverseFacts(OptimizationRequest request,VerificationWork work) {
        this.work=work;
        for(var assumption:request.assumptions()) {
            work.charge(1);
            if(assumption.kind()!=SemanticAssumption.Kind.SUCCESSFUL_MODULAR_INVERSE) continue;
            if(inverse!=null || assumption.subject().length()>4096 || assumption.parameter().length()!=64
                    || request.sourceTrace().occurrences().size()>JointComputationPlan.MAX_NODES)
                throw new IllegalArgumentException("SUCCESSFUL_INVERSE_WITNESS_BOUND");
            SourceEvaluationTrace.Occurrence found=null;
            for(var occurrence:request.sourceTrace().occurrences()) {
                work.charge(1L+assumption.subject().length());
                if(occurrence.sourceId().equals(assumption.subject())) { found=occurrence; break; }
            }
            if(found==null || !assumption.parameter().equals(commitment(found,work)))
                throw new IllegalArgumentException("SUCCESSFUL_INVERSE_OCCURRENCE_DIFFERS");
            inverse=found.expression();
        }
    }
    Expr inverse() { return inverse; }
    boolean matches(Expr expression) {
        if(inverse==null) return false;
        work.charge(JointComputationPlan.MAX_NODES);
        return inverse.equals(expression);
    }
    static String commitment(SourceEvaluationTrace.Occurrence occurrence,VerificationWork work) {
        if(work!=null) work.charge(1);
        if(occurrence.sourceId().length()>4096 || occurrence.declaredKind()!=NumericKind.BIG_INTEGER
                || occurrence.evaluatedKind()!=NumericKind.BIG_INTEGER
                || JavaExpressions.operationOf(occurrence.expression()).orElse(null)!=NumericOperation.MOD_INVERSE)
            throw new IllegalArgumentException("EXACT_MODULAR_INVERSE_OCCURRENCE_REQUIRED");
        if(work==null) return hash(occurrence,new CompiledAstReplayCodec().encodeExpression(occurrence.expression()));
        try(var scope=RetainedOperation.open(new RetainedOperation.Sink() {
            @Override public void executionWork(long units) { work.charge(units); }
            @Override public void validationWork(long units) { work.charge(units); }
            @Override public void checkpoint() { work.charge(0); }
            @Override public long observedWork() { return work.used(); }
            @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        })) {
            String encoded=new CompiledAstReplayCodec().encodeExpression(occurrence.expression());
            work.charge(4L*(encoded.length()+occurrence.sourceId().length()+32));
            return hash(occurrence,encoded);
        }
    }
    private static String hash(SourceEvaluationTrace.Occurrence occurrence,String encoded) {
        return EvidenceHashes.hash(occurrence.sourceId().length()+":"+occurrence.sourceId()+"|BIG_INTEGER|"+encoded);
    }
}
