package de.regelsuche.sdk.discovery;

import de.regelsuche.extension.ExtensionPoint;

/** Standard typed contribution points for Discovery providers. */
public final class DiscoveryExtensionPoints {
    public static final ExtensionPoint<DiscoveryDomainProvider> DOMAIN_PROVIDERS =
        ExtensionPoint.of("discovery.domain-provider", DiscoveryDomainProvider.class);

    private DiscoveryExtensionPoints() { }
}
