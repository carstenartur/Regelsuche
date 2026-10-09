package de.regelsuche.retention;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedJsonTest {
    private static final class NodeAllocationMeter implements RetainedOperation.Sink {
        RetainedOperation operation;
        boolean armed, objectAndMap, arrayAndList;
        final IllegalStateException failure = new IllegalStateException("completed JSON node debit");
        @Override public void executionWork(long units) {
            if (armed && units == 2) { armed = false; throw failure; }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        @Override public void checkpoint() {
            RetainedGraph.measure(operation);
            var pending = new java.util.ArrayDeque<Object>();
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
            pending.add(operation);
            while (!pending.isEmpty()) {
                Object current = pending.removeFirst();
                if (!seen.add(current)) continue;
                if (current instanceof RetainedGraph.View view) {
                    var owned = references(view);
                    if (current instanceof com.fasterxml.jackson.databind.node.ObjectNode)
                        objectAndMap |= owned.stream().anyMatch(value -> value instanceof java.util.Map<?, ?>);
                    if (current instanceof com.fasterxml.jackson.databind.node.ArrayNode)
                        arrayAndList |= owned.stream().anyMatch(value -> value instanceof List<?>);
                    owned.stream().filter(java.util.Objects::nonNull).forEach(pending::add);
                } else if (current instanceof Object[] values) {
                    for (Object value : values) if (value != null) pending.add(value);
                }
            }
        }
    }

    @Test void failedObjectAllocationDebitStillObservesItsActualChildMap() {
        var mapper = new ObjectMapper();
        var meter = new NodeAllocationMeter();
        try (var operation = RetainedOperation.open(meter)) {
            meter.operation = operation;
            try (var json = RetainedJson.open()) {
                meter.armed = true;
                assertSame(meter.failure, assertThrows(IllegalStateException.class, () -> RetainedJson.object(mapper)));
                assertTrue(meter.objectAndMap, "the completed ObjectNode and child map are published before their debit");
                assertNull(references(operation).get(2), "failed construction restores the enclosing frame");
                assertEquals("{\"retry\":true}", RetainedJson.object(mapper).put("retry", true).toString());
            }
        }
        assertFalse(RetainedJson.active());
    }

    @Test void failedArrayAllocationDebitStillObservesItsUnpublishedChildList() {
        var mapper = new ObjectMapper();
        var meter = new NodeAllocationMeter();
        try (var operation = RetainedOperation.open(meter)) {
            meter.operation = operation;
            try (var json = RetainedJson.open()) {
                var parent = RetainedJson.object(mapper);
                try (var owner = RetainedOperation.retain(parent)) {
                    meter.armed = true;
                    assertSame(meter.failure, assertThrows(IllegalStateException.class, () -> parent.putArray("items")));
                    assertTrue(meter.arrayAndList, "the completed ArrayNode and child list are visible before insertion in the parent");
                    assertFalse(parent.has("items"));
                    assertSame(owner, references(operation).get(2));
                    parent.putArray("items").add("retry");
                    assertEquals("{\"items\":[\"retry\"]}", parent.toString());
                }
            }
        }
        assertFalse(RetainedJson.active());
    }

    @Test void importReaderKeepsStrictConfigurationAndExposesActualDecodedContainers()throws Exception{
        var mapper=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        String input="{\"nested\":[{\"value\":\"𐐀é\"},7,true,null]}";
        var expected=mapper.readTree(input);
        try(var scope=RetainedJson.open()){
            var actual=RetainedJson.readTree(mapper,input);
            assertEquals(expected,actual);
            assertInstanceOf(RetainedGraph.View.class,actual);
            assertInstanceOf(RetainedGraph.View.class,actual.get("nested"));
            assertTrue(RetainedGraph.measure(actual).retained().characters()>0);
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                ()->RetainedJson.readTree(mapper,"{\"x\":1,\"x\":2}"));
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                ()->RetainedJson.readTree(mapper,input+" {}"));
        }
        assertFalse(RetainedJson.active());
        assertEquals(com.fasterxml.jackson.databind.node.ObjectNode.class,RetainedJson.readTree(mapper,input).getClass());
    }
    @Test void scopeCloseKeepsItsActualRecyclerUntilClearAndPaysItsExistingCostsOnce()throws Exception {
        assertScopeClose(CloseMeter.Abort.NONE,false,false);
    }
    @Test void failedClearDebitStillPaysTheCompletedScopeTransition()throws Exception {
        assertScopeClose(CloseMeter.Abort.CLEAR,false,false);
    }
    @Test void failedClearErrorStillPaysTheCompletedScopeTransition()throws Exception {
        assertScopeClose(CloseMeter.Abort.CLEAR_ERROR,false,false);
    }
    @Test void repeatedClearObservationAndFinalPaymentFailureKeepsOriginalIdentity()throws Exception {
        assertScopeClose(CloseMeter.Abort.CLEAR,true,false);
    }
    @Test void distinctObservationAndFinalPaymentFailuresRemainSuppressed()throws Exception {
        assertScopeClose(CloseMeter.Abort.CLEAR,true,true);
    }
    @Test void finalScopePaymentFailureStillReleasesAllReferencesAndRestoresOuterScope()throws Exception {
        assertScopeClose(CloseMeter.Abort.FINAL,false,false);
    }
    private static void assertScopeClose(CloseMeter.Abort abort,boolean laterFailures,boolean distinct)throws Exception {
        var mapper=new ObjectMapper(new RetainedJson.Factory(new JsonFactory()));
        var meter=new CloseMeter(abort,laterFailures,distinct);
        try(var operation=RetainedOperation.open(meter)) {
            meter.operation=operation;
            try(var outer=RetainedJson.open()) {
                var inner=RetainedJson.open();meter.json=inner;
                try {
                    String text="actual\noutput𐐀".repeat(20);
                    assertEquals(new ObjectMapper().writeValueAsString(text),RetainedJson.writeString(mapper,text));
                    meter.recycler=assertInstanceOf(RetainedGraph.View.class,references(inner).get(1));
                    var owned=references(meter.recycler);
                    meter.slots=((List<?>)owned.get(0)).size()
                        +((java.util.concurrent.atomic.AtomicReferenceArray<?>)owned.get(1)).length()
                        +((java.util.concurrent.atomic.AtomicReferenceArray<?>)owned.get(2)).length();
                    assertTrue(meter.slots>3);assertTrue(RetainedGraph.measure(inner).retained().characters()>0,"actual output buffers were leased and returned to this recycler");
                    meter.armed=true;
                    if(abort==CloseMeter.Abort.NONE)inner.close();
                    else {
                        var thrown=assertThrows(Throwable.class,inner::close);
                        assertSame(meter.failure,thrown);
                        if(distinct)assertArrayEquals(new Throwable[]{meter.observationFailure,meter.paymentFailure},thrown.getSuppressed());
                        else assertEquals(0,thrown.getSuppressed().length);
                    }
                    assertEquals(List.of(meter.slots,3L),meter.payments,"clear work plus the already completed scope transition, including failed debits");
                    assertTrue(meter.ownedAtClear,"Scope must still reference its actual recycler during clear payment");
                    if(abort==CloseMeter.Abort.CLEAR || abort==CloseMeter.Abort.CLEAR_ERROR)
                        assertTrue(meter.ownedAtFailureObservation,"observe the failed clear before releasing the recycler owner");
                    inner.close();assertEquals(List.of(meter.slots,3L),meter.payments,"repeated close is free and inert");
                    assertEquals(0,RetainedGraph.measure(inner).retained().characters());
                    assertTrue(references(inner).stream().allMatch(java.util.Objects::isNull));
                } finally {
                    meter.armed=false;inner.close();
                }
                assertTrue(RetainedJson.active(),"the same outer scope remains active after failed inner close");
                meter.json=outer;
                assertEquals("\"outer\"",RetainedJson.writeString(mapper,"outer"));
            }
            assertFalse(RetainedJson.active());assertEquals(0,RetainedGraph.measure(meter.json).retained().characters());
        }
        assertEquals(0,RetainedGraph.measure(meter.operation).retained().characters());
    }
    private static final class CloseMeter implements RetainedOperation.Sink {
        enum Abort { NONE,CLEAR,CLEAR_ERROR,FINAL }
        final Abort abort;final boolean laterFailures;final Throwable failure,observationFailure,paymentFailure;
        RetainedOperation operation;RetainedJson.Scope json;RetainedGraph.View recycler;
        long slots;boolean armed,failed,ownedAtClear,ownedAtFailureObservation;
        final List<Long> payments=new ArrayList<>();
        CloseMeter(Abort abort,boolean laterFailures,boolean distinct) {
            this.abort=abort;this.laterFailures=laterFailures;
            failure=abort==Abort.CLEAR_ERROR?new AssertionError("JSON clear error"):new IllegalStateException("JSON close "+abort);
            observationFailure=distinct?new IllegalArgumentException("JSON failure observation"):failure;
            paymentFailure=distinct?new IllegalArgumentException("JSON final payment"):failure;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(operation);v.reference(json);}
        @Override public void validationWork(long amount){executionWork(amount);}
        @Override public void executionWork(long amount) {
            if(!armed)return;
            payments.add(amount);
            if(amount==slots) {
                ownedAtClear=references(json).get(1)==recycler;
                if(abort==Abort.CLEAR || abort==Abort.CLEAR_ERROR){failed=true;raise(failure);}
            }
            if(amount==3 && (abort==Abort.FINAL || failed && laterFailures))raise(failed?paymentFailure:failure);
        }
        @Override public void checkpoint() {
            if(!armed || !failed)return;
            RetainedGraph.measure(this);
            ownedAtFailureObservation=references(json).get(1)==recycler;
            if(laterFailures)raise(observationFailure);
        }
        private static void raise(Throwable failure) {
            if(failure instanceof RuntimeException runtime)throw runtime;
            throw (Error)failure;
        }
    }
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
        boolean abort=true,failGrowthDebit,writerOwnsOld,replacementUnwritten;
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(operation);v.reference(json);}
        @Override public void executionWork(long amount){execution=Math.addExact(execution,amount);if(failGrowthDebit && amount==82){failGrowthDebit=false;throw new GrowthAbort();}}
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
    @Test void failedTextGrowthDebitObservesBothAllocatedBuffers()throws Exception{
        var mapper=new ObjectMapper(new RetainedJson.Factory(new JsonFactory()));
        var meter=new GrowthMeter();meter.abort=false;meter.failGrowthDebit=true;
        try(var operation=RetainedOperation.open(meter)){
            meter.operation=operation;
            try(var json=RetainedJson.open()){
                meter.json=json;
                assertThrows(GrowthAbort.class,()->RetainedJson.writeString(mapper,"A".repeat(80)));
                assertTrue(meter.writerOwnsOld);
                assertTrue(meter.replacementUnwritten);
                assertTrue(meter.characters>=114,"the old and new buffers overlap at failed allocation debit");
                assertEquals("\""+"A".repeat(80)+"\"",RetainedJson.writeString(mapper,"A".repeat(80)));
            }
            assertEquals(0,RetainedGraph.measure(meter.json).retained().characters());
        }
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
