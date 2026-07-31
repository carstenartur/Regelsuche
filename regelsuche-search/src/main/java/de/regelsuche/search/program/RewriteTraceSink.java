package de.regelsuche.search.program;

/** Consumer for deterministic rewrite-program interpreter events. */
@FunctionalInterface
public interface RewriteTraceSink {
    void accept(RewriteTraceEvent event);

    static RewriteTraceSink noOp() {
        return event -> { };
    }
}
