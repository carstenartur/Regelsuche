package de.regelsuche.retention;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedJsonTest {
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
