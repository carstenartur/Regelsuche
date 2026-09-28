package de.regelsuche.search.moves;

import java.util.Optional;

/** Trusted installed-code SPI: recognize an implementation, never a public ID or receipt claim. */
public interface NativeVerifierProvider {
    Optional<NativeVerifier> recognize(NativeMoveProvider provider);
}
