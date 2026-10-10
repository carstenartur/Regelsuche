package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

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
        // Actual transport/evidence, digest, receipt and list survive until the
        // immutable verification takes its already immutable receipt list.
        Object[] pending = new Object[4];
        var retained = RetainedOperation.retainCompleted(1, this, pending);
        Throwable primary = null;
        try {
            if (!accepted) return RetainedOperation.produced(new MoveVerifier.Verification(false,work,List.of(),reason));
            String receipt = switch (checkedProof) {
                case NativeMoveProof.Primitive primitive -> {
                    var codec = new CompiledAstReplayCodec();
                    var text = new String[2];
                    pending[0] = text;
                    RetainedOperation.work(1);
                    RetainedOperation.checkpoint();
                    text[0] = codec.encodeExpression(primitive.source());
                    text[1] = codec.encodeExpression(primitive.target());
                    String joined = text[0]+"\n"+text[1]+"\n"+ruleId;
                    pending[1] = joined;
                    RetainedOperation.work(1L + joined.length());
                    RetainedOperation.checkpoint();
                    String digest = NativeSearchMove.digest(joined);
                    pending[1] = digest;
                    RetainedOperation.work(1);
                    yield "typed-primitive-replay:"+digest;
                }
                case NativeMoveProof.Program program -> {
                    var exported = program.exportLegacy();
                    pending[0] = exported;
                    RetainedOperation.work(1);
                    yield "typed-program-replay:"+exported.applicationKey();
                }
                case NativeMoveProof.Exact exact -> {
                    var exported = exact.evidence().exportLegacy();
                    pending[0] = exported;
                    RetainedOperation.work(1);
                    yield "checked-schema-application:"+exported.binding().evidenceHash();
                }
            };
            pending[2] = receipt;
            RetainedOperation.work(1L + receipt.length());
            RetainedOperation.checkpoint();
            var receipts = List.of(receipt);
            pending[3] = receipts;
            RetainedOperation.work(2);
            RetainedOperation.checkpoint();
            return RetainedOperation.produced(new MoveVerifier.Verification(true,work,receipts,reason));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) {
                if (observation != failure) failure.addSuppressed(observation);
            }
            throw failure;
        } finally {
            closeFrame(retained, primary);
        }
    }

    private static void closeFrame(RetainedOperation.Frame frame, Throwable primary) {
        if (frame == null) return;
        try { frame.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }
}
