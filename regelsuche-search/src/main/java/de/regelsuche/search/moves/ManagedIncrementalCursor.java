package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;

import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import java.util.Objects;
import java.util.Optional;

/** Owns admission/lifecycle and keeps its meter even if a delegated open, pull or close throws. */
final class ManagedIncrementalCursor implements Cursor {
    private final MoveProvider.Descriptor descriptor;
    private final Definition definition;
    private final Factory factory;
    private final MoveState state;
    private final MoveContext context;
    private final Meter meter = new Meter();
    private Source source;
    private Status status = Status.OPEN;
    private boolean admitted, closed, accountingComplete = true;
    private long emitted;
    private ExecutionWork emittedWork = ExecutionWork.ZERO;
    private String detail = "";

    ManagedIncrementalCursor(MoveProvider.Descriptor descriptor, Definition definition, Factory factory,
            MoveState state, MoveContext context) {
        this.descriptor = descriptor; this.definition = definition; this.factory = factory;
        this.state = Objects.requireNonNull(state); this.context = Objects.requireNonNull(context);
    }
    @Override public Optional<Transformation> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative cursor allowance");
        if (closed || terminal()) return Optional.empty();
        long before = total();
        try {
            if (!available(before, allowance)) return Optional.empty();
            if (!admitted) admit();
            if (terminal() || !available(before, allowance)) return Optional.empty();
            if (source == null) open();
            if (!available(before, allowance)) return Optional.empty();
            meter.charge(Operation.PULL, 1);
            if (!available(before, allowance)) return Optional.empty();
            var candidate = Objects.requireNonNull(source.next(remaining(before, allowance)));
            status = Objects.requireNonNull(source.status());
            if (status == Status.OPEN || status == Status.CLOSED || status == Status.FAILED)
                throw new IllegalStateException("invalid delegated lifecycle state");
            if (candidate.isPresent()) retain(candidate.orElseThrow());
            validateMathematics();
            return candidate;
        } catch (RuntimeException failure) {
            fail(failure);
            return Optional.empty();
        }
    }
    private void admit() {
        meter.charge(Operation.ADMISSION, 1); admitted = true;
        if (!context.carries(descriptor.requiredAssumptions(), state)) {
            status = Status.EXHAUSTED; detail = "ASSUMPTIONS_NOT_CARRIED";
        }
    }
    private void open() {
        meter.charge(Operation.OPEN, 1);
        source = Objects.requireNonNull(factory.open(state, context, meter));
        status = Status.READY;
    }
    private void retain(Transformation candidate) {
        candidate.provenance().requireSource(state.expression());
        emittedWork = emittedWork.plus(candidate.executionWork());
        var paid = meter.work().mathematics();
        if (paid.primitiveRewrites() < emittedWork.primitiveRewrites() || paid.exactTheorySteps() < emittedWork.exactTheorySteps()
                || paid.exactTheoryWorkUnits() < emittedWork.exactTheoryWorkUnits())
            throw new IllegalStateException("candidate mathematics missing from cumulative receipt");
        emitted = Math.addExact(emitted, 1);
    }
    private void validateMathematics() {
        var work = meter.work().mathematics();
        if ((definition.mathematics() == Mathematics.PRIMITIVE && work.exactTheorySteps() != 0)
                || (definition.mathematics() == Mathematics.EXACT && work.primitiveRewrites() != 0))
            throw new IllegalStateException("work differs from declared mathematical kind");
    }
    private boolean terminal() {
        return status == Status.EXHAUSTED || status == Status.INCONCLUSIVE || status == Status.FAILED || status == Status.CLOSED;
    }
    private long total() { return meter.work().metrics().totalWorkUnitsV2(); }
    private long remaining(long before, long allowance) { return Math.max(0, allowance - (total() - before)); }
    private boolean available(long before, long allowance) {
        if (remaining(before, allowance) > 0) return true;
        status = Status.LIMIT; return false;
    }
    private void fail(RuntimeException failure) {
        meter.charge(Operation.ABORT, 1);
        status = Status.FAILED; accountingComplete = false;
        detail = failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage());
    }
    @Override public Snapshot snapshot() {
        return new Snapshot(definition, status, closed, !closed && status == Status.LIMIT, accountingComplete,
            meter.work(), emitted, detail);
    }
    @Override public void close() {
        if (closed) return;
        closed = true; meter.charge(Operation.CLOSE, 1);
        try {
            if (source != null) source.close();
            validateMathematics();
            if (!terminal()) status = Status.CLOSED;
        } catch (RuntimeException failure) { fail(failure); }
    }
}
