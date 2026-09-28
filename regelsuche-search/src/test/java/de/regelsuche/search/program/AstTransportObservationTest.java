package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.search.program.AstTransportObservation.Operation.*;
import de.regelsuche.ast.VariableExpr;
import org.junit.jupiter.api.Test;

class AstTransportObservationTest {
    @Test void realCodecEntriesRemainObservableEvenWhenExpressionCacheHits() {
        var codec=new CompiledAstReplayCodec();var expression=new VariableExpr("measured");
        try(var observation=AstTransportObservation.open()) {
            CompiledAstReplayCodec.withExpressionCache(4,10000,()->{
                String encoded=codec.encodeExpression(expression);assertSame(encoded,codec.encodeExpression(expression));
                assertSame(expression,codec.decodeExpression(encoded));assertSame(expression,codec.decodeExpression(encoded));return null;
            });
            assertEquals(2,observation.count(EXPRESSION_ENCODE));assertEquals(2,observation.count(EXPRESSION_DECODE));
            assertEquals(1,observation.count(EXPRESSION_JSON_WRITE));assertEquals(0,observation.count(EXPRESSION_JSON_READ));
            assertEquals(5,observation.total());
        }
    }
    @Test void nestedScopesRestoreTheirParentAndThreadsHaveIndependentCounters() throws Exception {
        var codec=new CompiledAstReplayCodec();
        try(var outer=AstTransportObservation.open()) {
            try(var inner=AstTransportObservation.open()) {
                codec.encodeExpression(new VariableExpr("nested"));assertEquals(2,inner.total());assertEquals(2,outer.total());
                var other=new Thread(()->codec.encodeExpression(new VariableExpr("other")));other.start();other.join();
                assertEquals(2,inner.total());assertEquals(2,outer.total());
            }
            codec.encodeExpression(new VariableExpr("parent"));assertEquals(4,outer.total());
        }
        codec.encodeExpression(new VariableExpr("outside"));
        try(var fresh=AstTransportObservation.open()){assertEquals(0,fresh.total());}
    }
}
