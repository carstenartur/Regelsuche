package de.regelsuche.extension;

import de.regelsuche.api.StableApi;

/** Generic plugin lifecycle independent of any specific extension point. */
@StableApi(since = "2")
public interface RegelsuchePlugin {
    /** Immutable plugin identity and compatibility declaration. */
    PluginDescriptor descriptor();

    /** Synchronously contributes extensions to the supplied staging context. */
    void contribute(ExtensionContext context);
}
