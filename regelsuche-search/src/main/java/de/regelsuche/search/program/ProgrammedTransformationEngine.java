package de.regelsuche.search.program;

import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationEngine;
import java.util.Objects;

/**
 * Adapter that makes an interpreted rewrite program available to every
 * existing search strategy through the ordinary {@link TransformationEngine}
 * interface while retaining deterministic internal-work metrics.
 */
public final class ProgrammedTransformationEngine
        implements MeasuredTransformationEngine {
    private final RewriteProgram program;
    private final RewriteProgramInterpreter interpreter;
    private final RewriteTraceLevel traceLevel;
    private final RewriteTraceSink traceSink;

    public ProgrammedTransformationEngine(RewriteProgram program) {
        this(
            program,
            new RewriteProgramInterpreter(),
            RewriteTraceLevel.OFF,
            RewriteTraceSink.noOp()
        );
    }

    public ProgrammedTransformationEngine(
        RewriteProgram program,
        RewriteTraceLevel traceLevel,
        RewriteTraceSink traceSink
    ) {
        this(program, new RewriteProgramInterpreter(), traceLevel, traceSink);
    }

    public ProgrammedTransformationEngine(
        RewriteProgram program,
        RewriteProgramInterpreter interpreter,
        RewriteTraceLevel traceLevel,
        RewriteTraceSink traceSink
    ) {
        this.program = Objects.requireNonNull(program, "program");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.traceLevel = Objects.requireNonNull(traceLevel, "traceLevel");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
    }

    @Override
    public TransformationBatch transformMeasured(String expression) {
        RewriteExecution execution = execute(expression);
        return new TransformationBatch(
            execution.transformations(),
            execution.workMetrics());
    }

    public RewriteExecution execute(String expression) {
        return interpreter.execute(program, expression, traceLevel, traceSink);
    }

    public RewriteProgram program() {
        return program;
    }

    public RewriteTraceLevel traceLevel() {
        return traceLevel;
    }
}
