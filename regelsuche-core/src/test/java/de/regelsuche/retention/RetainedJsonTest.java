package de.regelsuche.retention;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedJsonTest {
    private static final class GrowthAbort extends RuntimeException {}
    private static List<Object> references(RetainedGraph.View view){
        var values=new ArrayList<Object>();
        view.retainedReferences(new RetainedGraph.Visitor(){
            @Override public void reference(Object value){values.add(value);}
            @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
        });
        return values;
    }
    private static final class GrowthMeter implements RetainedOperation.Sink {
        RetainedOperation operation;RetainedJson.Scope json;
        long execution,previousCheckpoint,paidGrowth,characters;
        boolean abort=true,writerOwnsOld,replacementUnwritten;
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(operation);v.reference(json);}
        @Override public void executionWork(long amount){execution=Math.addExact(execution,amount);}
        @Override public void validationWork(long amount){}
        @Override public void checkpoint(){
            var observation=RetainedGraph.measure(this);
            Object current=operation==null?null:references(operation).get(2);
            if(current instanceof RetainedOperation.Frame frame){
                var refs=references(frame);var values=(Object[])refs.get(2);
                if(values.length==2 && values[0] instanceof char[] old && old.length==32
                        && values[1] instanceof char[] replacement && replacement.length==82){
                    paidGrowth=execution-previousCheckpoint;characters=observation.retained().characters();
                    replacementUnwritten=true;
                    for(char value:replacement)if(value!=0)replacementUnwritten=false;
                    Object previous=refs.get(1);
                    while(previous instanceof RetainedOperation.Frame outer){
                        var held=references(outer);
                        for(Object value:(Object[])held.get(2))
                            if(value instanceof Writer && value instanceof RetainedGraph.View writer
                                    && references(writer).getFirst()==old)writerOwnsOld=true;
                        previous=held.get(1);
                    }
                    if(abort)throw new GrowthAbort();
                }
            }
            previousCheckpoint=execution;
        }
    }
    @Test void allocatedTextGrowthIsPaidBeforeItsFirstCheckpointCanAbort()throws Exception{
        var mapper=new ObjectMapper(new RetainedJson.Factory(new JsonFactory()));
        var meter=new GrowthMeter();String input="A".repeat(80);long paidAtAbort;
        try(var operation=RetainedOperation.open(meter)){
            meter.operation=operation;
            try(var json=RetainedJson.open()){
                meter.json=json;
                assertThrows(GrowthAbort.class,()->RetainedJson.writeString(mapper,input));
                paidAtAbort=meter.paidGrowth;
                assertTrue(meter.writerOwnsOld,"the writer still owns its actual 32-character buffer");
                assertTrue(meter.replacementUnwritten,"the allocated replacement has not been copied yet");
                assertTrue(meter.characters>=114,"both actual buffers overlap at the failing checkpoint");
                meter.abort=false;
                assertEquals("\""+input+"\"",RetainedJson.writeString(mapper,input));
            }
            assertFalse(RetainedJson.active());
            assertEquals(0,RetainedGraph.measure(meter.json).retained().characters());
        }
        assertEquals(0,RetainedGraph.measure(meter.operation).retained().characters());
        assertEquals(84,paidAtAbort,"82 allocated characters and two frame operations were already completed");
    }
    private static final class Meter implements RetainedOperation.Sink {
        RetainedOperation operation;RetainedJson.Scope json;long work,peak;long maximum=Long.MAX_VALUE;
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(operation);v.reference(json);}
        @Override public void executionWork(long amount){work=Math.addExact(work,amount);}
        @Override public void validationWork(long amount){executionWork(amount);}
        @Override public void checkpoint(){var seen=RetainedGraph.measure(this);work=Math.addExact(work,seen.work());peak=Math.max(peak,seen.peak().characters());if(peak>maximum)throw new IllegalStateException("bounded output buffers");}
    }
    @Test void existingCharacterAndUtf8SerializersKeepTheirDistinctUnicodeBytesAndReleaseBuffers()throws Exception{
        var ordinary=new ObjectMapper();var audited=new ObjectMapper(new RetainedJson.Factory(new JsonFactory()));
        String value="quote\"\\\n\t\u0001𐐀é".repeat(900);
        var normal=ordinary.createObjectNode().put("value",value);String chars=ordinary.writeValueAsString(normal);byte[] bytes=ordinary.writeValueAsBytes(normal);
        var meter=new Meter();RetainedJson.Scope closed;
        try(var operation=RetainedOperation.open(meter)){
            meter.operation=operation;
            try(var json=RetainedJson.open()){
                meter.json=json;closed=json;var node=RetainedJson.object(audited).put("value",value);
                assertEquals(chars,RetainedJson.writeString(audited,node));assertArrayEquals(bytes,RetainedJson.writeBytes(audited,node));
                assertTrue(meter.peak>value.length()*2L,"generator, grown sink buffers, escaped result and input overlap");
                assertTrue(meter.work>chars.length()+bytes.length);
            }
            assertFalse(RetainedJson.active());assertEquals(0,RetainedGraph.measure(closed).retained().characters());
        }
        assertEquals(0,RetainedGraph.measure(meter.operation).retained().characters());
    }
    @Test void failedNestedOutputRestoresTheOuterScopeAndClosesAllOwnedLeases()throws Exception{
        var mapper=new ObjectMapper(new RetainedJson.Factory(new JsonFactory()));var meter=new Meter();
        try(var operation=RetainedOperation.open(meter)){
            meter.operation=operation;
            try(var outer=RetainedJson.open()){
                meter.json=outer;
                var failed=assertThrows(IllegalStateException.class,()->{
                    try(var inner=RetainedJson.open()){
                        meter.json=inner;meter.maximum=100;
                        RetainedJson.writeString(mapper,RetainedJson.object(mapper).put("value","escaped\n"));
                    }
                });
                assertEquals("bounded output buffers",failed.getMessage());assertTrue(RetainedJson.active());
                assertEquals(0,RetainedGraph.measure(meter.json).retained().characters());
                meter.json=outer;meter.maximum=Long.MAX_VALUE;
                assertEquals("{\"value\":\"ok\"}",RetainedJson.writeString(mapper,RetainedJson.object(mapper).put("value","ok")));
            }
            assertFalse(RetainedJson.active());assertEquals(0,RetainedGraph.measure(meter.json).retained().characters());
        }
        assertTrue(meter.work>0);
    }
}
