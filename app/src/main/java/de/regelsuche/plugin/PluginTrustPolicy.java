package de.regelsuche.plugin;

import java.util.Objects;

/**
 * Admission policy applied before external plugin bytecode enters a class loader.
 */
public enum PluginTrustPolicy {
    /**
     * Admit every readable JAR, but retain verification failures and warnings.
     * This preserves the historical permissive runtime behaviour.
     */
    WARN,

    /** Admit only artifacts with a valid signature from a trusted, non-revoked key. */
    REQUIRE_VERIFIED;

    public boolean permits(PluginArtifactVerification verification) {
        Objects.requireNonNull(verification, "verification");
        if (!verification.readable()) {
            return false;
        }
        return this == WARN || verification.trusted();
    }
}
