package de.regelsuche.search.moves;

import de.regelsuche.search.program.CompiledAstRewriteProgram;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.*;

/** Object transport of the existing registered interpreter, with independent full regeneration. */
public record NativeProgramMoveProvider(MoveProvider.Descriptor descriptor,CompiledAstRewriteProgram program) implements NativeMoveProvider,de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(descriptor);v.reference(program);}
    public NativeProgramMoveProvider {
        Objects.requireNonNull(descriptor);Objects.requireNonNull(program);
        if((descriptor.sourceKind()!=SearchMove.SourceKind.LEARNED && descriptor.sourceKind()!=SearchMove.SourceKind.EXPERT)
                || descriptor.proofStrength()!=SearchMove.ProofStrength.REPLAYABLE)
            throw new IllegalArgumentException("compiled regeneration requires a replayable learned/expert program");
    }
    @Override public IncrementalProviderContract.Mathematics mathematicalKind(){return IncrementalProviderContract.Mathematics.PRIMITIVE;}
    @Override public Batch candidates(TypedMoveSearch.State source,TypedMoveSearch.Context context) {
        if(!NativeMoveProvider.carries(descriptor.requiredAssumptions(),source,context))return NativeMoveProvider.rejectedAssumptions();
        try {
            var batch=program.transformMeasured(source.expression());
            var moves=new ArrayList<NativeSearchMove>();
            try(var retained=RetainedOperation.retain(this,source,context,batch,moves)) {
                for(var history:batch.candidates()) {
                    moves.add(proposal(history,batch.workMetrics().totalWorkUnits()));
                    RetainedOperation.work(1);RetainedOperation.checkpoint();
                }
                return RetainedOperation.produced(new Batch(moves,batch.workMetrics(),false));
            } catch(SearchExecution.ResourceLimit exhausted){throw exhausted.paidGeneration(batch.workMetrics());}
        } catch(CompiledAstRewriteProgram.CandidateLimitExceeded limit){return new Batch(List.of(),limit.workMetrics(),false);}
    }
    public NativeSearchMove proposal(CompiledAstRewriteProgram.Candidate history,long generationCost){
        try(var retained=RetainedOperation.retain(this,history)) {
            de.regelsuche.search.program.AstExpressionValidation.inspectHistory(history);
            RetainedOperation.work(2);
            return RetainedOperation.produced(new NativeSearchMove(new NativeMoveProof.Program(history),descriptor,generationCost,Set.of()));
        }
    }
    @Override public NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context){
        if(!NativeMoveProvider.carries(move.assumptions(),source,context) || !NativeMoveProvider.carries(descriptor.requiredAssumptions(),source,context))
            return new NativeVerification(false,1,null,null,"TYPED_PROGRAM_ASSUMPTIONS_MISSING");
        CompiledAstRewriteProgram.Batch regenerated;
        try{regenerated=program.transformMeasured(source.expression());}
        catch(SearchExecution.ResourceLimit exhausted){throw exhausted.verificationPhase();}
        catch(CompiledAstRewriteProgram.CandidateLimitExceeded limit){return new NativeVerification(false,verificationWork(limit.workMetrics()),null,null,"TYPED_PROGRAM_CANDIDATE_LIMIT");}
        long work=Math.addExact(verificationWork(regenerated.workMetrics()),regenerated.candidates().size());
        long compared=0;
        try(var retained=RetainedOperation.retain(this,source,move,context,regenerated)) {
            var expected=move.withCapabilityDelta(Set.of());
            try(var expectedFrame=RetainedOperation.retain(expected)) {
                boolean accepted=false;
                for(var history:regenerated.candidates()) {
                    var candidate=proposal(history,move.generationCost());
                    try(var comparedFrame=RetainedOperation.retain(candidate)) {
                        compared++;RetainedOperation.work(1);
                        if(expected.equals(candidate)){accepted=true;break;}
                    }
                }
                return RetainedOperation.produced(new NativeVerification(accepted,work,accepted?move.proof():null,accepted?move.ruleId():null,
                    accepted?"TYPED_PROGRAM_REPLAYED":"TYPED_PROGRAM_REPLAY_REJECTED"));
            }
        } catch(SearchExecution.ResourceLimit exhausted) {
            throw exhausted.paidVerification(Math.addExact(verificationWork(regenerated.workMetrics()),compared));
        }
    }
    private static long verificationWork(TransformationWorkMetrics work){return Math.addExact(work.totalWorkUnits(),work.candidateWork().canonicalWorkUnits());}
}
