package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectBackedMoveSearchTest {
    @Test void nativeInputCannotBypassTheExistingCodecTextAndUnicodeLimits() {
        var search=new NativeMoveSearch();
        for(var source:List.<Expr>of(new VariableExpr("x".repeat(4097)),new VariableExpr("x\uD800"))) {
            var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(new VariableExpr("goal")),List.of(),
                MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,2,100));
            assertThrows(IllegalArgumentException.class,()->search.search(problem,SearchContinuationContract.PATH_SENSITIVE),
                "removing serialization must not widen accepted inputs");
        }
    }
    @Test void aCandidateProviderCannotPromoteItsOwnAcceptedReceiptToDefaultAuthority() {
        var source=new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0));
        var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"native-test/v1");
        var real=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(rule),32,32));
        var selfAuthorizing=new NativeMoveProvider(){
            @Override public MoveProvider.Descriptor descriptor(){return descriptor;}
            @Override public Batch candidates(TypedMoveSearch.State state,TypedMoveSearch.Context context){return real.candidates(state,context);}
            @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context){
                return new NativeVerification(true,1,move.proof(),move.ruleId(),"self-asserted accepted");
            }
        };
        var state=new TypedMoveSearch.State(source,0,0,"",List.of(),java.util.Set.of(),0);
        var context=TypedMoveSearch.Context.frozen(new VariableExpr("x"));
        var original=real.candidates(state,context).moves().getFirst();
        var step=((NativeMoveProof.Primitive)original.proof()).step();
        var forgedStep=new AstRewriteTransport.Step(step.source(),new VariableExpr("forged"),step.rule(),step.kind(),
            step.mayIncreaseComplexity(),step.estimatedCostDelta(),step.equivalencePreservingByConstruction(),step.assumptions(),step.packId(),step.license());
        var forged=new NativeSearchMove(forgedStep,descriptor,original.generationCost(),java.util.Set.of());
        assertTrue(selfAuthorizing.verify(state,forged,context).accepted());
        var checked=NativeVerifier.registered(List.of(real)).verify(state,forged,context);
        assertFalse(checked.accepted());assertTrue(checked.work()>1);
        assertEquals("TYPED_PRIMITIVE_REPLAY_REJECTED",checked.reason());
        var wrongSource=new TypedMoveSearch.State(new VariableExpr("other"),0,0,"",List.of(),java.util.Set.of(),0);
        assertFalse(NativeVerifier.registered(List.of(real)).verify(wrongSource,original,context).accepted());
        assertThrows(IllegalArgumentException.class,()->new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(new VariableExpr("x")),List.of(selfAuthorizing),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(2,2,0,10,1000)),SearchContinuationContract.PATH_SENSITIVE));
    }
    @Test void primitiveNativeSearchRetainsProducerObjectsAndExportsTheSameFullLegacyResult() {
        var leaf=NumberExpr.exact("-7/13");var source=new BinaryExpr(leaf,BinaryOperator.ADD,new NumberExpr(0));
        var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var transport=new AstRewriteTransport(List.of(rule),32,32);
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,
            List.of(),SearchMove.ValueEvidence.UNKNOWN,"native-test/v1");
        var budget=new MoveSearch.Budget(6,2,100,64,1000000);
        var context=TypedMoveSearch.Context.frozen(leaf);
        var legacy=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,
            List.of(TypedMoveSearch.primitiveProvider(descriptor,transport)),MovePriorityPolicy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport),state->0,MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget));
        var nativeResult=assertDoesNotThrow(()->new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,
            List.of(new NativeMoveSearch.Primitive(descriptor,transport)),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget),
            SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,nativeResult.outcome());
        assertSame(leaf,nativeResult.output(),"untouched producer subtree must survive frontier and independent admission");
        assertEquals(withNativePrimitiveVerificationWork(legacy.encodedResult()),nativeResult.exportLegacy(),"every event/state/witness/assessment/receipt must project equally");
    }
    @Test void nativePrimitiveBatchUsesTheSameStagedManagedLanes() {
        assertNativePrimitiveBatch(SearchMove.ProofStrength.REPLAYABLE);
    }
    @Test void verifiedPrimitiveEvidenceStillUsesPrimitiveMathematicalWork() {
        assertNativePrimitiveBatch(SearchMove.ProofStrength.VERIFIED);
    }
    private void assertNativePrimitiveBatch(SearchMove.ProofStrength strength) {
        var leaf=new VariableExpr("x");var source=new BinaryExpr(leaf,BinaryOperator.ADD,new NumberExpr(0));
        var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var transport=new AstRewriteTransport(List.of(rule),32,32);
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,strength,List.of(),SearchMove.ValueEvidence.UNKNOWN,"native-staged/v1");
        var budget=new MoveSearch.Budget(1,1,0,10,1000000);var context=TypedMoveSearch.Context.frozen(leaf);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,
            List.of(new NativeMoveSearch.Primitive(descriptor,transport)),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget),
            SearchContinuationContract.PATH_SENSITIVE).outcome());
        var legacy=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,List.of(TypedMoveSearch.primitiveProvider(descriptor,transport)),
            MovePriorityPolicy.INVENTORY_ORDER,TypedMoveSearch.primitiveReplay(transport),s->0,MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED_INCREMENTAL,budget));
        var nativeResult=new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,List.of(new NativeMoveSearch.Primitive(descriptor,transport)),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED_INCREMENTAL,budget),SearchContinuationContract.PATH_SENSITIVE);
        var projected=nativeResult.exportLegacy();assertEquals(withNativePrimitiveVerificationWork(legacy.encodedResult()).witness(),projected.witness());
        assertEquals(withNativePrimitiveVerificationWork(legacy.encodedResult()).events(),projected.events());
        assertEquals(withNativePrimitiveVerificationWork(legacy.encodedResult()).metrics(),nativeResult.metrics());
        assertTrue(nativeResult.accountingComplete());assertTrue(nativeResult.cursorReceipts().getFirst().closed());
        assertEquals(IncrementalProviderContract.NATIVE_REVISION,projected.stagedIncrementalExecution().providers().getFirst().definition().revision());
    }

    /** These single-step fixtures regenerate one primitive in each admission; v3 pays that actual work. */
    private static MoveSearch.Result withNativePrimitiveVerificationWork(MoveSearch.Result old) {
        var m=old.metrics();
        var metrics=new MoveSearch.Metrics(m.generatedSuccessors(),m.consumedSuccessors(),m.discardedSuccessors(),m.unconsumedSuccessors(),
            m.duplicates(),m.deadEnds(),m.exploredStates(),m.expandedStates(),m.primitiveWork(),m.searchWork(),m.verificationWork()+1,
            m.firstHitDepth(),m.firstHitPrimitiveDepth(),m.familyMatches());
        return new MoveSearch.Result(old.outcome(),old.witness().stream().map(w->new MoveSearch.WitnessStep(w.source(),w.target(),w.move(),nativeVerification(w.verification()))).toList(),
            old.events().stream().map(e->new MoveSearch.Event(e.source(),e.target(),e.move(),e.decision(),nativeVerification(e.verification()))).toList(),
            old.reachedStates(),old.deadEndStates(),metrics,old.completeBoundedRelation(),old.stateAssessments(),old.incrementalExecution(),old.stagedIncrementalExecution());
    }
    private static MoveVerifier.Verification nativeVerification(MoveVerifier.Verification old) {
        return old==null?null:new MoveVerifier.Verification(old.accepted(),old.work()+1,old.receipts(),old.reason());
    }
}
