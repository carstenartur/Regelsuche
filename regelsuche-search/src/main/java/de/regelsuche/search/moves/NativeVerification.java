package de.regelsuche.search.moves;

import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;

/** Typed recheck receipt. The exact producer binding, not its eventual digest, determines equality. */
public record NativeVerification(boolean accepted,long work,AstRewriteTransport.Step checkedStep,String ruleId,String reason)
        implements SearchExecution.Verification {
    public NativeVerification {
        if(work<0 || reason==null || (accepted && (checkedStep==null || ruleId==null)))throw new IllegalArgumentException("invalid native verification");
    }
    public MoveVerifier.Verification exportLegacy(){
        if(!accepted)return new MoveVerifier.Verification(false,work,List.of(),reason);
        var codec=new CompiledAstReplayCodec();
        String receipt="typed-primitive-replay:"+NativeSearchMove.digest(codec.encodeExpression(checkedStep.source())+"\n"
            +codec.encodeExpression(checkedStep.target())+"\n"+ruleId);
        return new MoveVerifier.Verification(true,work,List.of(receipt),reason);
    }
}
