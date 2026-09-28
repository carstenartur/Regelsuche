package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectBackedMoveSearchTest {
    @Test void primitiveNativeSearchRetainsProducerObjectsAndExportsTheSameFullLegacyResult() {
        var leaf=NumberExpr.exact("-7/13");var source=new BinaryExpr(leaf,BinaryOperator.ADD,new NumberExpr(0));
        var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var transport=new AstRewriteTransport(List.of(rule),32,32);
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,
            List.of(),SearchMove.ValueEvidence.UNKNOWN,"native-test/v1");
        var budget=new MoveSearch.Budget(6,2,100,64,30000);
        var context=TypedMoveSearch.Context.frozen(leaf);
        var legacy=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,
            List.of(TypedMoveSearch.primitiveProvider(descriptor,transport)),MovePriorityPolicy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport),state->0,MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget));
        var nativeResult=assertDoesNotThrow(()->new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,
            List.of(new NativeMoveSearch.Primitive(descriptor,transport)),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget),
            SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,nativeResult.outcome());
        assertSame(leaf,nativeResult.output(),"untouched producer subtree must survive frontier and independent admission");
        assertEquals(legacy.encodedResult(),nativeResult.exportLegacy(),"every event/state/witness/assessment/receipt must project equally");
    }
}
