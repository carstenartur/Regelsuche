package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;

import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;

/** Typed recheck receipt. Complete producer structure, never a digest, determines equality. */
public record NativeVerification(boolean accepted,long work,NativeMoveProof checkedProof,String ruleId,String reason)
        implements SearchExecution.Verification,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(checkedProof);v.reference(ruleId);v.reference(reason);}

    public NativeVerification {
        if(work<0 || reason==null || (accepted && (checkedProof==null || ruleId==null)))throw new IllegalArgumentException("invalid native verification");
    }
    public MoveVerifier.Verification exportLegacy(){
        if(!accepted)return new MoveVerifier.Verification(false,work,List.of(),reason);
        String receipt=switch(checkedProof) {
            case NativeMoveProof.Primitive primitive -> {
                var codec=new CompiledAstReplayCodec();
                yield "typed-primitive-replay:"+NativeSearchMove.digest(codec.encodeExpression(primitive.source())+"\n"+codec.encodeExpression(primitive.target())+"\n"+ruleId);
            }
            case NativeMoveProof.Program program -> "typed-program-replay:"+program.exportLegacy().applicationKey();
            case NativeMoveProof.Exact exact -> "checked-schema-application:"+exact.evidence().exportLegacy().binding().evidenceHash();
        };
        return new MoveVerifier.Verification(true,work,List.of(receipt),reason);
    }
}
