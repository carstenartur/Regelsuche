package de.regelsuche.search.program;

/** Scoped diagnostic measurement of actual codec entries, including cache hits and evidence export. */
public final class AstTransportObservation implements AutoCloseable {
    public enum Operation { EXPRESSION_ENCODE, EXPRESSION_DECODE, EXPRESSION_JSON_WRITE, EXPRESSION_JSON_READ,
        HISTORY_ENCODE, HISTORY_DECODE, HISTORY_HASH, EVIDENCE_JSON_WRITE }
    public static AstTransportObservation open(){return new AstTransportObservation();}
    public long count(Operation operation){return 0;}
    public long total(){return 0;}
    public static void record(Operation operation){}
    @Override public void close(){}
}
