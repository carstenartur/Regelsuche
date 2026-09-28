package de.regelsuche.retention;

import java.util.Objects;

/** Lexical native execution observation. Inactive on historical calls; no completed graph is registered. */
public final class RetainedOperation implements AutoCloseable,RetainedGraph.View {
    public interface Sink extends RetainedGraph.View {
        void executionWork(long units);
        void validationWork(long units);
        void checkpoint();
        /** Additive native work outside the provider meter, for remaining pull allowance. */
        default long observedWork(){return 0;}
    }
    private static final ThreadLocal<RetainedOperation> CURRENT=new ThreadLocal<>();
    private RetainedOperation previous;
    private Sink sink;
    private final long owner=Thread.currentThread().threadId();
    private Frame current;
    private boolean closed;
    private RetainedOperation(Sink sink){this.sink=Objects.requireNonNull(sink);previous=CURRENT.get();sink.executionWork(1);CURRENT.set(this);}
    public static RetainedOperation open(Sink sink){return new RetainedOperation(sink);}
    public static long observedWork(){var scope=CURRENT.get();return scope==null?0:scope.sink.observedWork();}
    public static void work(long units){var scope=CURRENT.get();if(scope!=null)scope.sink.executionWork(units);}
    public static void validation(long units){var scope=CURRENT.get();if(scope!=null)scope.sink.validationWork(units);}
    /** Retains the actual mutable collections/objects, so later checkpoints observe their current fields. */
    public static Frame retain(Object... values){
        var scope=CURRENT.get();if(scope==null)return null;
        var frame=new Frame(scope,values);scope.sink.executionWork(2);scope.current=frame;
        try { scope.sink.checkpoint();return frame; }
        catch(RuntimeException | Error failure){
            try{frame.close();}catch(RuntimeException | Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    public static void checkpoint(){var scope=CURRENT.get();if(scope!=null)scope.sink.checkpoint();}
    public static <T> T produced(T value){work(1);try(var frame=retain(value)){return value;}}
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(previous);v.reference(sink);v.reference(current);}
    @Override public void close(){
        if(closed)return;
        if(Thread.currentThread().threadId()!=owner || CURRENT.get()!=this || current!=null)throw new IllegalStateException("native operation scope order");
        closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        var charged=sink;previous=null;sink=null;charged.executionWork(3);
    }
    public static final class Frame implements AutoCloseable,RetainedGraph.View {
        private RetainedOperation scope;
        private Frame previous;
        private Object[] values;
        private boolean closed;
        private Frame(RetainedOperation scope,Object[] values){this.scope=scope;previous=scope.current;this.values=values;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);v.reference(previous);v.reference(values);}
        @Override public void close(){
            if(closed)return;
            if(Thread.currentThread().threadId()!=scope.owner || scope.current!=this)throw new IllegalStateException("native retained frame order");
            closed=true;scope.current=previous;
            var charged=scope.sink;values=null;previous=null;scope=null;charged.executionWork(4);
        }
    }
}
