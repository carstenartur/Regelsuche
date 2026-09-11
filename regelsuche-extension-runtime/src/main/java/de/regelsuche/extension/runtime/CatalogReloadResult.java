package de.regelsuche.extension.runtime;

import de.regelsuche.api.StableApi;

/** Result of a transactional catalog reload attempt. */
@StableApi(since = "2")
public record CatalogReloadResult(
    boolean applied,
    String previousCatalogHash,
    String currentCatalogHash,
    String diagnostic
) {
    public CatalogReloadResult {
        if (previousCatalogHash == null || currentCatalogHash == null) {
            throw new NullPointerException("catalog hashes");
        }
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}
