package de.regelsuche.search.program;

import java.util.Objects;

/** Scoped diagnostic measurement of actual codec entries, including cache hits and evidence export. */
public final class AstTransportObservation implements AutoCloseable {
    public enum Operation { EXPRESSION_ENCODE, EXPRESSION_DECODE, EXPRESSION_JSON_WRITE, EXPRESSION_JSON_READ,
        HISTORY_ENCODE, HISTORY_DECODE, HISTORY_HASH, EVIDENCE_JSON_WRITE }
    private static final ThreadLocal<AstTransportObservation> CURRENT=new ThreadLocal<>();
    private final AstTransportObservation previous=CURRENT.get();
    private final Thread owner=Thread.currentThread();
    private final long[] counts=new long[Operation.values().length];
    private boolean closed;
    private AstTransportObservation(){CURRENT.set(this);}
    public static AstTransportObservation open(){return new AstTransportObservation();}
    public long count(Operation operation){return counts[Objects.requireNonNull(operation).ordinal()];}
    public long total(){long total=0;for(long count:counts)total=Math.addExact(total,count);return total;}
    public static void record(Operation operation){
        for(var scope=CURRENT.get();scope!=null;scope=scope.previous)
            scope.counts[operation.ordinal()]=Math.addExact(scope.counts[operation.ordinal()],1);
    }
    @Override public void close(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("transport observation belongs to another thread");
        if(closed)return;
        if(CURRENT.get()!=this)throw new IllegalStateException("transport observations close in stack order");
        closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);
    }
}
