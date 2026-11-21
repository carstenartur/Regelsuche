package de.regelsuche.solver.ir;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Solver-neutral receiver port for a scoped proof proposal.
 * A confirmed receipt must retain both the kernel execution and the independent
 * reconstruction. It is not a synonym for an unverified search candidate.
 */
@FunctionalInterface
public interface ScopedProofExecutor {
    VerifiedReplay verify(ScopedProofDag proposal);

    record VerifiedReplay(String proposalHash, SolverIr.ResultStatus status, String message,
                          String kernelHash, Path kernelDirectory,
                          String rebuildHash, Path rebuildDirectory) {
        public VerifiedReplay {
            Objects.requireNonNull(proposalHash, "proposalHash");
            Objects.requireNonNull(status, "status");
            message = Objects.requireNonNullElse(message, "");
            kernelHash = Objects.requireNonNullElse(kernelHash, "");
            rebuildHash = Objects.requireNonNullElse(rebuildHash, "");
            if (status == SolverIr.ResultStatus.CONFIRMED
                && (kernelHash.isBlank() || rebuildHash.isBlank()
                    || kernelDirectory == null || rebuildDirectory == null)) {
                throw new IllegalArgumentException(
                    "A confirmed scoped proof requires kernel and independent replay evidence");
            }
        }
    }
}
