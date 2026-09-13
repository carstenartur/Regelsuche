package de.regelsuche.extension;

import de.regelsuche.api.StableApi;

/**
 * Synchronous staging capability supplied to one plugin contribution callback.
 *
 * <p>The runtime may make this context thread-confined and close it immediately
 * after {@link RegelsuchePlugin#contribute(ExtensionContext)} returns.</p>
 */
@StableApi(since = "2")
public interface ExtensionContext {
    /** Stages one typed contribution. */
    <T> void contribute(
        ExtensionPoint<T> point,
        ExtensionDescriptor descriptor,
        T implementation
    );
}
