package de.regelsuche.math.sympy;

/** Package-local transport result before exact polynomial decoding. */
record SymPyInvocation(
    Status status,
    String detailCode,
    String output,
    String runtimeId,
    String runtimeVersion,
    boolean coldStart,
    long initializationNanos,
    long invocationNanos
) {
    SymPyInvocation {
        if (status == null
                || detailCode == null
                || detailCode.isBlank()
                || output == null
                || runtimeId == null
                || runtimeId.isBlank()
                || runtimeVersion == null
                || initializationNanos < 0
                || invocationNanos < 0) {
            throw new IllegalArgumentException(
                "SymPy invocation is invalid");
        }
        if (status != Status.COMPLETED && !output.isEmpty()) {
            throw new IllegalArgumentException(
                "failed SymPy invocation cannot expose output");
        }
    }

    static SymPyInvocation completed(
        String output,
        String runtimeId,
        String runtimeVersion,
        boolean coldStart,
        long initializationNanos,
        long invocationNanos
    ) {
        return new SymPyInvocation(
            Status.COMPLETED,
            "SYMPY_INVOCATION_COMPLETED",
            output,
            runtimeId,
            runtimeVersion,
            coldStart,
            initializationNanos,
            invocationNanos);
    }

    static SymPyInvocation failure(
        Status status,
        String detailCode,
        String runtimeId,
        long invocationNanos
    ) {
        if (status == Status.COMPLETED) {
            throw new IllegalArgumentException(
                "completed invocation requires output");
        }
        return new SymPyInvocation(
            status,
            detailCode,
            "",
            runtimeId,
            "",
            true,
            0,
            Math.max(0, invocationNanos));
    }

    enum Status {
        COMPLETED,
        UNAVAILABLE,
        TIMEOUT,
        TECHNICAL_FAILURE
    }
}
