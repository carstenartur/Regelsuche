package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.ArrayList;
import java.util.List;

/** Shared fail-closed compatibility checks for backend adapters. */
final class SolverBackendSupport {
    private SolverBackendSupport() {
    }

    static List<String> issues(
        Obligation obligation,
        BackendDescriptor descriptor,
        boolean supportsAssumptions
    ) {
        List<String> issues = new ArrayList<>();
        obligation.theories().stream()
            .filter(theory -> !descriptor.supportedTheories().contains(theory))
            .forEach(theory -> issues.add("UNSUPPORTED_THEORY:" + theory.name()));
        if (!descriptor.supportedRelations().contains(obligation.goal().relation())) {
            issues.add("UNSUPPORTED_RELATION:" + obligation.goal().relation().name());
        }
        if (!descriptor.supportedEvidence().contains(obligation.requestedEvidence())) {
            issues.add("UNSUPPORTED_EVIDENCE:" + obligation.requestedEvidence().name());
        }
        if (!supportsAssumptions && !obligation.assumptions().isEmpty()) {
            issues.add("ASSUMPTIONS_NOT_SUPPORTED");
        }
        return issues.stream().distinct().sorted().toList();
    }

    static SolverResult unsupported(
        Obligation obligation,
        BackendDescriptor descriptor,
        List<String> issues
    ) {
        return SolverResult.create(
            obligation,
            descriptor,
            ResultStatus.UNSUPPORTED,
            TranslationStatus.REJECTED,
            List.of(),
            issues,
            "backend rejected the obligation before execution",
            java.util.Map.of(),
            "");
    }
}
