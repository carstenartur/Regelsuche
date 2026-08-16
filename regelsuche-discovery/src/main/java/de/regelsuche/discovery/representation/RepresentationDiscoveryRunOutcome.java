package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import java.util.Objects;

/** Terminal/current state with canonical work and runtime kept separate. */
public record RepresentationDiscoveryRunOutcome(
    TerminalState state,
    String terminalReason,
    long configuredWork,
    long consumedWork,
    String canonicalWorkLedgerHash,
    String runtimeDiagnosticsHash,
    String contentHash
) {
    public RepresentationDiscoveryRunOutcome {
        state = Objects.requireNonNull(state, "state");
        terminalReason = requireText(terminalReason, "terminalReason");
        if (configuredWork < 0 || consumedWork < 0
                || consumedWork > configuredWork) {
            throw new IllegalArgumentException(
                "configured and consumed work do not balance");
        }
        if (state == TerminalState.CREATED
                && (configuredWork != 0
                    || consumedWork != 0
                    || !"NOT_STARTED".equals(terminalReason))) {
            throw new IllegalArgumentException(
                "CREATED outcome must be NOT_STARTED with zero work");
        }
        if (state == TerminalState.BUDGET_EXHAUSTED
                && (configuredWork == 0
                    || consumedWork != configuredWork)) {
            throw new IllegalArgumentException(
                "BUDGET_EXHAUSTED must consume all configured work");
        }
        canonicalWorkLedgerHash = requireSha256(
            canonicalWorkLedgerHash,
            "canonicalWorkLedgerHash"
        );
        runtimeDiagnosticsHash = requireSha256(
            runtimeDiagnosticsHash,
            "runtimeDiagnosticsHash"
        );
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = outcomeHash(
            state,
            terminalReason,
            configuredWork,
            consumedWork,
            canonicalWorkLedgerHash,
            runtimeDiagnosticsHash
        );
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "run outcome content hash mismatch");
        }
    }

    public static RepresentationDiscoveryRunOutcome create(
        TerminalState state,
        String terminalReason,
        long configuredWork,
        long consumedWork,
        String canonicalWorkLedgerHash,
        String runtimeDiagnosticsHash
    ) {
        TerminalState normalizedState = Objects.requireNonNull(state, "state");
        String reason = requireText(terminalReason, "terminalReason");
        String workHash = requireSha256(
            canonicalWorkLedgerHash, "canonicalWorkLedgerHash");
        String runtimeHash = requireSha256(
            runtimeDiagnosticsHash, "runtimeDiagnosticsHash");
        String hash = outcomeHash(
            normalizedState,
            reason,
            configuredWork,
            consumedWork,
            workHash,
            runtimeHash
        );
        return new RepresentationDiscoveryRunOutcome(
            normalizedState,
            reason,
            configuredWork,
            consumedWork,
            workHash,
            runtimeHash,
            hash
        );
    }

    public static RepresentationDiscoveryRunOutcome created() {
        return create(
            TerminalState.CREATED,
            "NOT_STARTED",
            0,
            0,
            sha256(WORKSPACE_SCHEMA + "/work/NOT_STARTED"),
            sha256(WORKSPACE_SCHEMA + "/runtime/NOT_EVALUATED")
        );
    }

    private static String outcomeHash(
        TerminalState state,
        String reason,
        long configuredWork,
        long consumedWork,
        String workHash,
        String runtimeHash
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/outcome");
        append(descriptor, state.name());
        append(descriptor, reason);
        append(descriptor, Long.toString(configuredWork));
        append(descriptor, Long.toString(consumedWork));
        append(descriptor, workHash);
        append(descriptor, runtimeHash);
        return sha256(descriptor.toString());
    }

    public enum TerminalState {
        CREATED,
        RUNNING,
        COMPLETED,
        BUDGET_EXHAUSTED,
        NO_RESULT,
        CANCELLED,
        FAILED,
        UNSUPPORTED
    }
}
