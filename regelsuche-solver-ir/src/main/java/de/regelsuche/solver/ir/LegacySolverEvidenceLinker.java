package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Binds already-executed legacy oracle evidence to the canonical IR.
 *
 * <p>This class does not execute a backend. It records whether the legacy
 * invocation consumed the complete IR. Existing equivalence services receive
 * only the goal expressions, so non-empty structured assumptions are retained
 * but explicitly reported as an approximated translation.</p>
 */
public final class LegacySolverEvidenceLinker {
    public SolverResult link(
        Obligation obligation,
        String backendId,
        String backendVersion,
        boolean confirmed,
        String evidence
    ) {
        String normalizedEvidence = evidence == null ? "" : evidence;
        BackendDescriptor descriptor = new BackendDescriptor(
            backendId,
            backendVersion,
            obligation.theories(),
            List.of(obligation.goal().relation()),
            List.of(obligation.requestedEvidence()),
            true);
        boolean assumptionsConsumed = obligation.assumptions().isEmpty();
        TranslationStatus translation = assumptionsConsumed
            ? TranslationStatus.LOSSLESS : TranslationStatus.APPROXIMATED;
        List<String> issues = assumptionsConsumed
            ? List.of()
            : List.of("LEGACY_BACKEND_DID_NOT_CONSUME_STRUCTURED_ASSUMPTIONS");
        ResultStatus status = classify(confirmed, normalizedEvidence);
        String certificate = status == ResultStatus.CONFIRMED
            ? SolverIr.sha256(normalizedEvidence) : "";
        return SolverResult.create(
            obligation,
            descriptor,
            status,
            translation,
            List.of("LEGACY_EQUIVALENCE_EVIDENCE"),
            issues,
            normalizedEvidence,
            Map.of(),
            certificate);
    }

    private static ResultStatus classify(boolean confirmed, String evidence) {
        if (confirmed) {
            return ResultStatus.CONFIRMED;
        }
        String normalized = evidence.toLowerCase(Locale.ROOT);
        if (normalized.contains("not equivalent")
                || normalized.contains("counterexample")
                || normalized.contains("refut")) {
            return ResultStatus.REFUTED;
        }
        return ResultStatus.UNKNOWN;
    }
}
