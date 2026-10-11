package de.regelsuche.search.moves;

import java.util.Optional;

/** Trusted installed-code SPI: recognize an implementation, never a public ID or receipt claim. */
public interface NativeVerifierProvider {
    Optional<NativeVerifier> recognize(NativeMoveProvider provider);

    /**
     * Optional cost contract of a trusted installed checker, separate from proof validity.
     * Only the checker returned by the installed SPI and retained by Registered is consulted.
     * Provider claims and directly supplied verifier implementations cannot grant coverage.
     * Implementations must bind actual immutable ownership and every nested callback, pay
     * inspection through RetainedOperation, and return null for undeclared execution paths.
     */
    interface ExecutionInventory {
        String executionRevision(NativeMoveProvider provider);
    }
}
