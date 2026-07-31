package de.regelsuche.search.program;

import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Objects;

/**
 * Adapter that makes an interpreted rewrite program available to every
 * existing search strategy through the ordinary {@link TransformationEngine}
 * interface.
 */
public final class ProgrammedTransformationEngine implements TransformationEngine {
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
    public List<Transformation> transform(String expression) {
        return execute(expression).transformations();
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
