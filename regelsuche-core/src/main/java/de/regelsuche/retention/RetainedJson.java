package de.regelsuche.retention;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.io.*;
import com.fasterxml.jackson.core.json.*;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/** Existing Jackson serializers with lexical, explicitly owned native output buffers. */
public final class RetainedJson {
    private RetainedJson(){}
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    public static boolean active(){return CURRENT.get()!=null;}
    public static Scope open(){return new Scope();}
    public static final class Scope implements AutoCloseable,RetainedGraph.View {
        private Scope previous;private Recycler recycler;private boolean closed;
        private Scope(){previous=CURRENT.get();RetainedOperation.work(15);recycler=new Recycler();CURRENT.set(this);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(previous);v.reference(recycler);}
        @Override public void close(){
            if(closed)return;if(CURRENT.get()!=this)throw new IllegalStateException("JSON scope order");
            closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            Throwable primary=null;
            try {
                recycler.clear();
            } catch(RuntimeException | Error failure) {
                primary=failure;
                // Scope still owns the recycler while observing a failed clear.
                try{RetainedOperation.checkpoint();}
                catch(RuntimeException | Error observation){if(observation!=failure)failure.addSuppressed(observation);}
                throw failure;
            } finally {
                previous=null;recycler=null;
                // CURRENT/closed and these field resets happened even if clear's debit failed.
                try{RetainedOperation.work(3);}
                catch(RuntimeException | Error accounting){
                    if(primary==null)throw accounting;
                    if(accounting!=primary)primary.addSuppressed(accounting);
                }
            }
        }
    }
    private static final class Recycler extends BufferRecycler implements RetainedGraph.View {
        private final ArrayList<Object> leased=new ArrayList<>();
        @Override public void retainedReferences(RetainedGraph.Visitor v){
            v.reference(leased);v.reference(_byteBuffers);v.reference(_charBuffers);
        }
        @Override protected byte[] balloc(int size){RetainedOperation.work(size);return new byte[size];}
        @Override protected char[] calloc(int size){RetainedOperation.work(size);return new char[size];}
        @Override public byte[] allocByteBuffer(int index,int minimum){var b=super.allocByteBuffer(index,minimum);leased.add(b);RetainedOperation.work(2);RetainedOperation.checkpoint();return b;}
        @Override public char[] allocCharBuffer(int index,int minimum){var b=super.allocCharBuffer(index,minimum);leased.add(b);RetainedOperation.work(2);RetainedOperation.checkpoint();return b;}
        @Override public void releaseByteBuffer(int index,byte[] buffer){
            try(var frame=RetainedOperation.retain(buffer)){super.releaseByteBuffer(index,buffer);leased.remove(buffer);RetainedOperation.work(3);RetainedOperation.checkpoint();}
        }
        @Override public void releaseCharBuffer(int index,char[] buffer){
            try(var frame=RetainedOperation.retain(buffer)){super.releaseCharBuffer(index,buffer);leased.remove(buffer);RetainedOperation.work(3);RetainedOperation.checkpoint();}
        }
        void clear(){
            long slots=_byteBuffers.length()+_charBuffers.length()+leased.size();
            for(int i=0;i<_byteBuffers.length();i++)_byteBuffers.set(i,null);
            for(int i=0;i<_charBuffers.length();i++)_charBuffers.set(i,null);
            leased.clear();RetainedOperation.work(slots);
        }
    }
    /** Copies all existing configuration once; historical calls use the original Jackson implementations. */
    public static final class Factory extends JsonFactory {
        public Factory(JsonFactory source){super(source,source.getCodec());}
        @Override protected IOContext _createContext(ContentReference content,boolean managed){
            if(!active())return super._createContext(content,managed);
            return new Context(_streamReadConstraints,_streamWriteConstraints,_errorReportConfiguration,
                CURRENT.get().recycler,content,managed);
        }
        @Override protected JsonGenerator _createGenerator(Writer out,IOContext context)throws IOException{
            if(!active())return super._createGenerator(out,context);
            try(var held=RetainedOperation.retain(context,out)) {
            var generator=new Characters(context,_generatorFeatures,_objectCodec,out,_quoteChar);
            if(_maximumNonEscapedChar>0)generator.setHighestNonEscapedChar(_maximumNonEscapedChar);
            if(_characterEscapes!=null)generator.setCharacterEscapes(_characterEscapes);
            if(_rootValueSeparator!=DEFAULT_ROOT_VALUE_SEPARATOR)generator.setRootValueSeparator(_rootValueSeparator);
            return RetainedOperation.produced(generator);
            }
        }
        @Override protected JsonGenerator _createUTF8Generator(OutputStream out,IOContext context)throws IOException{
            if(!active())return super._createUTF8Generator(out,context);
            try(var held=RetainedOperation.retain(context,out)) {
            var generator=new Bytes(context,_generatorFeatures,_objectCodec,out,_quoteChar);
            if(_maximumNonEscapedChar>0)generator.setHighestNonEscapedChar(_maximumNonEscapedChar);
            if(_characterEscapes!=null)generator.setCharacterEscapes(_characterEscapes);
            if(_rootValueSeparator!=DEFAULT_ROOT_VALUE_SEPARATOR)generator.setRootValueSeparator(_rootValueSeparator);
            return RetainedOperation.produced(generator);
            }
        }
    }
    private static final class Context extends IOContext implements RetainedGraph.View {
        Context(StreamReadConstraints read,StreamWriteConstraints write,ErrorReportConfiguration errors,Recycler recycler,ContentReference content,boolean managed){
            super(read,write,errors,recycler,content,managed);markBufferRecyclerReleased();RetainedOperation.work(1);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){
            // Constraint/error metadata is pre-existing immutable factory infrastructure. Raw owner is actual.
            v.reference(_contentReference);v.reference(_sourceRef);v.reference(_bufferRecycler);
            v.reference(_readIOBuffer);v.reference(_writeEncodingBuffer);v.reference(_base64Buffer);
            v.reference(_tokenCBuffer);v.reference(_concatCBuffer);v.reference(_nameCopyBuffer);
            v.reference(null);v.reference(null);v.reference(null);v.reference(null);
        }
    }
    private static final class WriteContext extends JsonWriteContext implements RetainedGraph.View {
        WriteContext(int type,WriteContext parent,DupDetector duplicates,Object value){super(type,parent,duplicates,value);RetainedOperation.work(1);}
        @Override public JsonWriteContext createChildArrayContext(){return createChildArrayContext(null);}
        @Override public JsonWriteContext createChildArrayContext(Object value){return child(TYPE_ARRAY,value);}
        @Override public JsonWriteContext createChildObjectContext(){return createChildObjectContext(null);}
        @Override public JsonWriteContext createChildObjectContext(Object value){return child(TYPE_OBJECT,value);}
        private JsonWriteContext child(int type,Object value){
            if(_child==null)_child=new WriteContext(type,this,_dups==null?null:_dups.child(),value);else _child.reset(type,value);
            RetainedOperation.work(1);return _child;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(_parent);v.reference(_child);v.reference(_dups);v.reference(_currentName);v.reference(_currentValue);}
    }
    private static final class Characters extends WriterBasedJsonGenerator implements RetainedGraph.View {
        Characters(IOContext context,int features,ObjectCodec codec,Writer out,char quote){super(context,features,codec,out,quote);_writeContext=new WriteContext(JsonStreamContext.TYPE_ROOT,null,_writeContext.getDupDetector(),null);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){
            v.reference(_writer);v.reference(_outputBuffer);v.reference(_entityBuffer);v.reference(_copyBuffer);v.reference(_currentEscape);
            v.reference(_ioContext);v.reference(_writeContext);v.reference(_outputEscapes);
            v.reference(null);v.reference(null);v.reference(null);v.reference(null); // pre-existing codec/configuration metadata
        }
    }
    private static final class Bytes extends UTF8JsonGenerator implements RetainedGraph.View {
        Bytes(IOContext context,int features,ObjectCodec codec,OutputStream out,char quote){super(context,features,codec,out,quote);_writeContext=new WriteContext(JsonStreamContext.TYPE_ROOT,null,_writeContext.getDupDetector(),null);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){
            v.reference(_outputStream);v.reference(_outputBuffer);v.reference(_charBuffer);v.reference(_entityBuffer);
            v.reference(_ioContext);v.reference(_writeContext);v.reference(_outputEscapes);
            v.reference(null);v.reference(null);v.reference(null);v.reference(null);
        }
    }
    public static ObjectNode object(ObjectMapper mapper){return active()?new ObjectValue():mapper.createObjectNode();}
    /** Existing strict mapper/reader, with explicitly owned containers at the import boundary.
     * Parser-internal temporaries are not yet a complete native inventory. */
    public static JsonNode readTree(ObjectMapper mapper,String input)throws JsonProcessingException{
        return active()?mapper.reader().with(NODES).readTree(input):mapper.readTree(input);
    }
    private static final class NodeFactory extends JsonNodeFactory implements RetainedGraph.View {
        @Override public ObjectNode objectNode(){return new ObjectValue();}
        @Override public ArrayNode arrayNode(){return new ArrayValue();}
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    private static final NodeFactory NODES=new NodeFactory();
    private static final class ObjectValue extends ObjectNode implements RetainedGraph.View {
        ObjectValue(){super(NODES,new LinkedHashMap<>());RetainedOperation.work(2);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(_nodeFactory);v.reference(_children);}
    }
    private static final class ArrayValue extends ArrayNode implements RetainedGraph.View {
        private final ArrayList<JsonNode> owned;
        ArrayValue(){this(new ArrayList<>());}
        private ArrayValue(ArrayList<JsonNode> values){super(NODES,values);owned=values;RetainedOperation.work(2);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(_nodeFactory);v.reference(owned);v.reference(owned);}
    }
    private static final class TextOutput extends Writer implements RetainedGraph.View {
        private char[] buffer=new char[32];private int size;
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(buffer);v.reference(lock);}
        @Override public void write(char[] input,int offset,int length){
            int needed=Math.addExact(size,length);
            if(needed>buffer.length){
                var old=buffer;var replacement=new char[Math.max(needed,Math.multiplyExact(buffer.length,2))];
                RetainedOperation.work(replacement.length);
                try(var frame=RetainedOperation.retain(old,replacement)){
                    System.arraycopy(old,0,replacement,0,size);RetainedOperation.work(size);buffer=replacement;
                }
            }
            System.arraycopy(input,offset,buffer,size,length);size=needed;RetainedOperation.work(length);RetainedOperation.checkpoint();
        }
        String value(){RetainedOperation.work(size);return RetainedOperation.produced(new String(buffer,0,size));}
        @Override public void flush(){}
        @Override public void close(){}
    }
    private static final class ByteOutput extends ByteArrayOutputStream implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(buf);}
        @Override public synchronized void write(byte[] input,int offset,int length){
            var old=buf;
            try(var frame=RetainedOperation.retain(old)){
                super.write(input,offset,length);RetainedOperation.work(length+(old==buf?0L:Math.addExact(old.length,buf.length)));RetainedOperation.checkpoint();
            }
        }
        @Override public synchronized void write(int value){var old=buf;try(var frame=RetainedOperation.retain(old)){super.write(value);RetainedOperation.work(1+(old==buf?0L:Math.addExact(old.length,buf.length)));RetainedOperation.checkpoint();}}
        byte[] value(){RetainedOperation.work(count);return RetainedOperation.produced(toByteArray());}
    }
    public static String writeString(ObjectMapper mapper,Object value)throws IOException{
        if(!active())return mapper.writeValueAsString(value);
        var output=new TextOutput();RetainedOperation.work(33);
        try(var frame=RetainedOperation.retain(value,output)){
            try(var generator=mapper.getFactory().createGenerator(output);var held=RetainedOperation.retain(generator)){
                mapper.writeValue(generator,value);generator.flush();RetainedOperation.checkpoint();return output.value();
            }
        }
    }
    public static byte[] writeBytes(ObjectMapper mapper,Object value)throws IOException{
        if(!active())return mapper.writeValueAsBytes(value);
        var output=new ByteOutput();RetainedOperation.work(33);
        try(var frame=RetainedOperation.retain(value,output)){
            try(var generator=mapper.getFactory().createGenerator(output);var held=RetainedOperation.retain(generator)){
                mapper.writeValue(generator,value);generator.flush();RetainedOperation.checkpoint();return output.value();
            }
        }
    }
}
