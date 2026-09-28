package de.regelsuche.evolution;

import de.regelsuche.search.moves.*;
import java.util.Optional;

/** Installed checker recognizes only the final provider owned by the loaded, independently proved model. */
public final class CheckedSchemaNativeVerifierProvider implements NativeVerifierProvider {
    @Override public Optional<NativeVerifier> recognize(NativeMoveProvider provider) {
        if(provider instanceof CheckedLearnedSchemaModel.NativeProvider checked)return Optional.of(checked.independentVerifier());
        if(provider instanceof CheckedSchemaMatcherPlan.NativeProvider checked)return Optional.of(checked.independentVerifier());
        return Optional.empty();
    }
}
