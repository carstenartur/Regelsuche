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
    long invocationNanos,
    String failureDiagnostic
) {
    private static final int MAX_DIAGNOSTIC_CHARACTERS = 4_096;
    private static final int MAX_DIAGNOSTIC_CAUSES = 8;

    SymPyInvocation {
        failureDiagnostic = failureDiagnostic == null
            ? ""
            : failureDiagnostic.strip();
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
        if (status == Status.COMPLETED && !failureDiagnostic.isEmpty()) {
            throw new IllegalArgumentException(
                "completed SymPy invocation cannot expose failure diagnostics");
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
            invocationNanos,
            "");
    }

    static SymPyInvocation failure(
        Status status,
        String detailCode,
        String runtimeId,
        long invocationNanos
    ) {
        return failure(
            status,
            detailCode,
            runtimeId,
            invocationNanos,
            "");
    }

    static SymPyInvocation failure(
        Status status,
        String detailCode,
        String runtimeId,
        long invocationNanos,
        Throwable failure
    ) {
        return failure(
            status,
            detailCode,
            runtimeId,
            invocationNanos,
            diagnostic(failure));
    }

    static SymPyInvocation failure(
        Status status,
        String detailCode,
        String runtimeId,
        long invocationNanos,
        String failureDiagnostic
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
            Math.max(0, invocationNanos),
            boundedDiagnostic(failureDiagnostic));
    }

    static String diagnostic(Throwable failure) {
        if (failure == null) {
            return "";
        }
        StringBuilder diagnostic = new StringBuilder();
        Throwable current = failure;
        int causes = 0;
        while (current != null && causes < MAX_DIAGNOSTIC_CAUSES) {
            if (causes > 0) {
                diagnostic.append(" <- ");
            }
            diagnostic.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                diagnostic.append(": ")
                    .append(message.replaceAll("\\s+", " ").strip());
            }
            current = current.getCause();
            causes++;
        }
        if (current != null) {
            diagnostic.append(" <- ...");
        }
        return boundedDiagnostic(diagnostic.toString());
    }

    private static String boundedDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return "";
        }
        String normalized = diagnostic
            .replaceAll("\\s+", " ")
            .strip();
        if (normalized.length() <= MAX_DIAGNOSTIC_CHARACTERS) {
            return normalized;
        }
        return normalized.substring(0, MAX_DIAGNOSTIC_CHARACTERS - 3)
            + "...";
    }

    enum Status {
        COMPLETED,
        UNAVAILABLE,
        TIMEOUT,
        TECHNICAL_FAILURE
    }
}
