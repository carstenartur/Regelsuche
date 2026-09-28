package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramTemporaryOwnershipTest {
    /** Inspects the public audited graph only; no reflection or production hook beyond the real scope. */
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        long work;
        int maximumHistories;
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){work=Math.addExact(work,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);}
        @Override public void checkpoint(){
            RetainedGraph.measure(scope);
            var pending=new ArrayDeque<Object>();
            var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);int histories=0;
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof CompiledAstRewriteProgram.Candidate)histories++;
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Collection<?> values)values.forEach(visitor::reference);
                else if(value instanceof Map<?,?> values)values.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                else if(value instanceof Object[] values)for(var item:values)visitor.reference(item);
            }
            maximumHistories=Math.max(maximumHistories,histories);
        }
    }
    @Test void completeProgramObservesPriorAndNextStageHistoriesBeforeHandoff(){
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var one=new PatternRewriteRule("one",PatternExpr.op(BinaryOperator.MUL,a,PatternExpr.num(1)),a);
        var program=new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("cleanup"),List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("zero-stage"),new PreparedAstRewriteTransformationEngine(List.of(zero),64,128)),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("one-stage"),new PreparedAstRewriteTransformationEngine(List.of(one),64,128)))),128).compileAst();
        var goal=new VariableExpr("x");
        var source=new BinaryExpr(new BinaryExpr(goal,BinaryOperator.MUL,new NumberExpr(1)),BinaryOperator.ADD,new NumberExpr(0));
        var expected=program.transformMeasured(source);
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertEquals(expected,program.transformMeasured(source));
        }
        assertTrue(observation.maximumHistories>=2,
            "stage composition keeps its prior prefix and new complete history live together before result handoff");
        assertTrue(observation.work>0);
    }
}
