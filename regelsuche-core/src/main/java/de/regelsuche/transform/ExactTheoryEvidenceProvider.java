package de.regelsuche.transform;

import java.util.Optional;

/**
 * Trusted installed-code SPI bridging verifier-owned capabilities into core.
 * Providers must recognize a sealed verifier-issued object, never accept its
 * public data/JSON/hash as authority. Providers are installed in META-INF/services;
 * callers cannot supply a provider to the evidence factory.
 */
public interface ExactTheoryEvidenceProvider {
    Optional<ExactTheoryEvidence.Binding> bind(Object verifierOwnedEvidence);
    default Optional<NativeExactTheoryEvidence.Binding> bindNative(Object verifierOwnedEvidence){return Optional.empty();}
    default Object exportNative(Object verifierOwnedEvidence){throw new IllegalArgumentException("unsupported native evidence export");}
}
