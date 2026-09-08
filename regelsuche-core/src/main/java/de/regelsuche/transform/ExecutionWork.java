package de.regelsuche.transform;

/** Mathematical path work, independent of the frozen mechanical v1 ledger. */
public record ExecutionWork(long primitiveRewrites, long exactTheorySteps, long exactTheoryWorkUnits) {
    public static final ExecutionWork ZERO = new ExecutionWork(0, 0, 0);

    public ExecutionWork {
        if (primitiveRewrites < 0 || exactTheorySteps < 0 || exactTheoryWorkUnits < exactTheorySteps
                || (exactTheorySteps == 0 && exactTheoryWorkUnits != 0)) {
            throw new IllegalArgumentException("inconsistent primitive/theory work");
        }
        Math.addExact(primitiveRewrites, exactTheoryWorkUnits);
    }

    public ExecutionWork plus(ExecutionWork other) {
        return new ExecutionWork(Math.addExact(primitiveRewrites, other.primitiveRewrites),
            Math.addExact(exactTheorySteps, other.exactTheorySteps),
            Math.addExact(exactTheoryWorkUnits, other.exactTheoryWorkUnits));
    }

    public long canonicalWorkUnits() {
        return Math.addExact(primitiveRewrites, exactTheoryWorkUnits);
    }
}
