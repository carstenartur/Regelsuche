package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class AstHistoryValidationTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static AstRewriteTransport.Step step(Expr source, Expr target, List<String> assumptions) {
        return new AstRewriteTransport.Step(source,target,"rule-é\n\"\\😀",RewriteKind.SIMPLIFY,
            true,Integer.MIN_VALUE,false,assumptions,"pack","license");
    }
    private static CompiledAstRewriteProgram.Candidate history(Expr source, Expr target, List<String> assumptions) {
        return new CompiledAstRewriteProgram.Candidate("program",List.of("stage"),List.of(step(source,target,assumptions)));
    }
    private static final class Work implements de.regelsuche.retention.RetainedOperation.Sink {
        long validation,execution;
        @Override public void validationWork(long units){validation+=units;}
        @Override public void executionWork(long units){execution+=units;}
        @Override public void checkpoint(){}
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor visitor){}
    }
    @Test void successfulAndAbortedDirectHistoryTraversalRemainPaidWithoutSerialization() {
        var source=new VariableExpr("x");var valid=history(source,new NumberExpr(0),List.of());
        var invalid=new CompiledAstRewriteProgram.Candidate("p".repeat(4097),valid.sourceIds(),valid.steps());
        var work=new Work();
        try(var transport=AstTransportObservation.open();var observed=de.regelsuche.retention.RetainedOperation.open(work)) {
            var inspected=AstExpressionValidation.inspectHistory(valid);
            assertEquals(inspected.work(),work.validation);
            long paid=work.validation;
            assertThrows(IllegalArgumentException.class,()->AstExpressionValidation.inspectHistory(invalid));
            assertTrue(work.validation>paid,"refused metadata inspection cannot refund its already performed work");
            assertEquals(0,transport.total());
        }
        long finished=work.validation;
        AstExpressionValidation.inspectHistory(valid);
        assertEquals(finished,work.validation,"closed observation must not retain or receive the next execution");
    }

    @Test void directHistoryValidationMatchesCanonicalBytesWithoutTransport() {
        var source=new FunctionExpr("f",List.of(new VariableExpr("x"),NumberExpr.exact("-7/13")));
        var target=new VariableExpr("é😀");
        var first=step(source,target,List.of("x > 0","y != 0"));
        var second=new AstRewriteTransport.Step(target,new NumberExpr(1),"next",RewriteKind.SIMPLIFY,
            false,0,true,List.of(),"pack","license");
        var candidate=new CompiledAstRewriteProgram.Candidate("program",List.of("stage","next"),List.of(first,second));
        byte[] encoded=CODEC.encode(candidate);
        try(var transport=AstTransportObservation.open()) {
            var inspected=AstExpressionValidation.inspectHistory(candidate);
            assertEquals(encoded.length,inspected.canonicalBytes());
            assertEquals(new String(encoded,StandardCharsets.UTF_8).length(),inspected.canonicalCharacters());
            assertEquals(5,inspected.nodes());
            assertTrue(inspected.work()>inspected.nodes());
            assertEquals(0,transport.total());
        }
    }
    @Test void directHistoryValidationKeepsAssumptionUnicodeAndIntermediateStateLimits() {
        var source=new VariableExpr("x");
        var assumptions=java.util.stream.IntStream.range(0,129).mapToObj(i->"x"+i+" > 0").toList();
        var tooMany=history(source,new NumberExpr(0),assumptions);
        var badUnicode=new CompiledAstRewriteProgram.Candidate("program\ud800",List.of("stage"),List.of(step(source,source,List.of())));
        var intermediate=new VariableExpr("x".repeat(4097));
        var badIntermediate=new CompiledAstRewriteProgram.Candidate("program",List.of("first","last"),List.of(
            step(source,intermediate,List.of()),step(intermediate,source,List.of())));
        for(var candidate:List.of(tooMany,badUnicode,badIntermediate)) {
            assertThrows(IllegalArgumentException.class,()->CODEC.encode(candidate));
            assertThrows(IllegalArgumentException.class,()->AstExpressionValidation.inspectHistory(candidate));
        }
    }
    @Test void aggregateHistoryBytesRemainBoundedWhenEveryIndividualStateFits() {
        Expr state=new FunctionExpr("f",Collections.nCopies(16,new VariableExpr("\\".repeat(4096))));
        assertDoesNotThrow(()->CODEC.encodeExpression(state));
        var candidate=new CompiledAstRewriteProgram.Candidate("program",Collections.nCopies(8,"stage"),
            Collections.nCopies(8,step(state,state,List.of())));
        assertThrows(IllegalArgumentException.class,()->CODEC.encode(candidate));
        assertThrows(IllegalArgumentException.class,()->AstExpressionValidation.inspectHistory(candidate));
    }
}
