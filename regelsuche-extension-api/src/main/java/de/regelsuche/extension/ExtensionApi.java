package de.regelsuche.extension;

import de.regelsuche.api.StableApi;

/** Version constants for the generic extension contract. */
@StableApi(since = "2")
public final class ExtensionApi {
    /** Generic extension/plugin API revision. */
    public static final String VERSION = "2";

    /** Core compatibility level exposed to extension descriptors. */
    public static final String CORE_COMPATIBILITY_VERSION = "1.0.0";

    private ExtensionApi() {
    }
}
