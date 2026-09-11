package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.List;
import java.util.Optional;

/** Immutable snapshot of accepted extension contributions. */
@StableApi(since = "2")
public interface ExtensionCatalog {
    /** Returns all contributions for exactly this point id and contract type. */
    <T> List<RegisteredExtension<T>> registrations(ExtensionPoint<T> point);

    /** Finds one contribution by point and contribution id. */
    <T> Optional<RegisteredExtension<T>> find(ExtensionPoint<T> point, String id);

    /** Deterministic metadata-only representation of this snapshot. */
    String canonicalManifest();

    /** SHA-256 identity of {@link #canonicalManifest()}. */
    String contentHash();
}
