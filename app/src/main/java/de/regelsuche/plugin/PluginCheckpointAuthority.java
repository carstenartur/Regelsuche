package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.ChainCheckpoint;
import java.io.IOException;

/**
 * Operator-provided authority OUTSIDE the rollback boundary of the package files.
 * There is deliberately no filesystem or in-memory production implementation.
 *
 * <p>Implementations must durably and linearly compare-and-set the entire pair,
 * never restore an older accepted value, authenticate their caller and scope the
 * value to this installation and trust domain. A false result or exception MUST
 * mean this call did not commit; a provider must reconcile ambiguous network
 * outcomes before returning. Read failure must throw, never return genesis.
 * Provider deadlines and administrative recovery are the operator's responsibility.
 * These guarantees cannot be inferred or verified from an ordinary local file.</p>
 */
public interface PluginCheckpointAuthority {
    AcceptedState read() throws IOException;

    boolean compareAndSet(AcceptedState expected, AcceptedState update) throws IOException;

    /** The generation hash authenticates all local evidence, metadata and package bytes. */
    record AcceptedState(String installationHash, ChainCheckpoint checkpoint) {
        public AcceptedState {
            if (installationHash == null || (installationHash.isEmpty() != (checkpoint == null))) {
                throw new IllegalArgumentException("genesis requires both an empty generation and no checkpoint");
            }
            if (!installationHash.isEmpty()) {
                PluginSignatureManifest.requireSha256(installationHash, "installationHash");
            }
        }

        public static AcceptedState empty() {
            return new AcceptedState("", null);
        }
    }
}
