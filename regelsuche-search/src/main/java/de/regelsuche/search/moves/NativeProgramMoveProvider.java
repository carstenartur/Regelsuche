package de.regelsuche.search.moves;

import de.regelsuche.search.program.CompiledAstRewriteProgram;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.*;

/** Object transport of the existing registered interpreter, with independent full regeneration. */
public record NativeProgramMoveProvider(MoveProvider.Descriptor descriptor,CompiledAstRewriteProgram program) implements NativeMoveProvider {
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
            return new Batch(batch.candidates().stream().map(history->proposal(history,batch.workMetrics().totalWorkUnits())).toList(),batch.workMetrics(),false);
        } catch(CompiledAstRewriteProgram.CandidateLimitExceeded limit){return new Batch(List.of(),limit.workMetrics(),false);}
    }
    public NativeSearchMove proposal(CompiledAstRewriteProgram.Candidate history,long generationCost){
        de.regelsuche.search.program.AstExpressionValidation.inspectHistory(history);
        return new NativeSearchMove(new NativeMoveProof.Program(history),descriptor,generationCost,Set.of());
    }
    @Override public NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context){
        if(!NativeMoveProvider.carries(move.assumptions(),source,context) || !NativeMoveProvider.carries(descriptor.requiredAssumptions(),source,context))
            return new NativeVerification(false,1,null,null,"TYPED_PROGRAM_ASSUMPTIONS_MISSING");
        CompiledAstRewriteProgram.Batch regenerated;
        try{regenerated=program.transformMeasured(source.expression());}
        catch(CompiledAstRewriteProgram.CandidateLimitExceeded limit){return new NativeVerification(false,verificationWork(limit.workMetrics()),null,null,"TYPED_PROGRAM_CANDIDATE_LIMIT");}
        long work=Math.addExact(verificationWork(regenerated.workMetrics()),regenerated.candidates().size());
        boolean accepted=regenerated.candidates().stream().map(history->proposal(history,move.generationCost())).anyMatch(move.withCapabilityDelta(Set.of())::equals);
        return new NativeVerification(accepted,work,accepted?move.proof():null,accepted?move.ruleId():null,
            accepted?"TYPED_PROGRAM_REPLAYED":"TYPED_PROGRAM_REPLAY_REJECTED");
    }
    private static long verificationWork(TransformationWorkMetrics work){return Math.addExact(work.totalWorkUnits(),work.candidateWork().canonicalWorkUnits());}
}
