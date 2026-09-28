package de.regelsuche.search.moves;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.AstTransportObservation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeExportAccountingTest {
    private static NativeMoveSearch.Result source(){
        var x=new VariableExpr("x_é_𐐀");
        var problem=new NativeMoveSearch.Problem(x,TypedMoveSearch.Context.frozen(x),List.of(),MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1000000));
        try(var transport=AstTransportObservation.open()) {
            var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE);
            assertEquals(0,transport.total());return result;
        }
    }
    @Test void explicitOutputPaysItsActualBuffersAndLeavesPublishedSearchWorkUnchanged(){
        var result=source();long paidSearch=result.totalWork();var before=result.accounting();
        var historical=result.exportLegacy(NativeMoveSearch.Result.DEFAULT_EXPORT_WORK,SearchExpressionStore.Limits.DEFAULT).projection();
        try(var transport=AstTransportObservation.open()) {
            var output=assertDoesNotThrow(()->result.exportLegacy(1000000,SearchExpressionStore.Limits.DEFAULT));
            assertTrue(output.artifactAvailable(),output.accounting().detail());assertEquals(historical,output.projection());
            assertTrue(transport.total()>0);assertTrue(output.accounting().work()>0);
            assertTrue(output.accounting().peak().characters()>output.accounting().resultRetained().characters(),"real output buffers overlap the result");
            assertEquals(paidSearch,result.totalWork());assertSame(before,result.accounting());
        }
    }
    @Test void exportDoesNotReadOrPopulateACallersLexicalExpressionCache(){
        var result=source();var codec=new de.regelsuche.search.program.CompiledAstReplayCodec();
        var warm=new VariableExpr("outer");
        de.regelsuche.search.program.CompiledAstReplayCodec.withExpressionCache(8,1000000,()->{
            codec.encodeExpression(warm);
            var output=result.exportLegacy(1000000,SearchExpressionStore.Limits.DEFAULT);assertTrue(output.artifactAvailable());
            try(var transport=AstTransportObservation.open()) {
                codec.encodeExpression(warm);assertEquals(0,transport.count(de.regelsuche.search.program.AstTransportObservation.Operation.EXPRESSION_JSON_WRITE));
                codec.encodeExpression(result.output());assertEquals(1,transport.count(de.regelsuche.search.program.AstTransportObservation.Operation.EXPRESSION_JSON_WRITE),"export must not insert its AST into a caller-owned outer cache");
            }
            try(var transport=AstTransportObservation.open()) {
                assertTrue(result.exportLegacy(1000000,SearchExpressionStore.Limits.DEFAULT).artifactAvailable());
                assertTrue(transport.count(de.regelsuche.search.program.AstTransportObservation.Operation.EXPRESSION_JSON_WRITE)>0,"warm outer entries do not make native export encoding free");
            }
            return null;
        });
    }
    @Test void zeroAndTightWorkAndRetentionReturnPaidIncompleteExportsWithoutAProjection(){
        var result=source();long paidSearch=result.totalWork();
        var normal=assertDoesNotThrow(()->result.exportLegacy(1000000,SearchExpressionStore.Limits.DEFAULT));
        assertTrue(normal.artifactAvailable(),normal.accounting().detail());
        for(long budget:List.of(0L,1L,64L,4096L,normal.accounting().work()/3,normal.accounting().work()/2,normal.accounting().work()-1)) {
            var stopped=result.exportLegacy(budget,SearchExpressionStore.Limits.DEFAULT);
            assertFalse(stopped.complete());assertNull(stopped.projection());
            assertEquals(de.regelsuche.retention.RetainedGraph.measure(stopped).retained(),stopped.accounting().resultRetained(),"the final receipt describes only the actually returned graph");
            assertTrue(stopped.accounting().work()>budget);assertEquals("NATIVE_EXPORT_WORK_EXHAUSTED",stopped.accounting().detail());
        }
        var memory=result.exportLegacy(1000000,new SearchExpressionStore.Limits(1000,1000,1,0));
        assertFalse(memory.complete());assertNull(memory.projection());assertTrue(memory.accounting().work()>0);
        assertEquals("NATIVE_EXPORT_RETENTION_EXHAUSTED",memory.accounting().detail());
        assertEquals(paidSearch,result.totalWork());
    }
}
