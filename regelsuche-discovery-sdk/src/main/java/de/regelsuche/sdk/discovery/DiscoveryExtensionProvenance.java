package de.regelsuche.sdk.discovery;

import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionOrigin;
import de.regelsuche.extension.ExtensionPoint;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable contribution metadata, distinct from the observed provider artifact.
 * Composition of this metadata does not authenticate a catalog or grant mathematical authority.
 */
public record DiscoveryExtensionProvenance(
    ExtensionPoint<DiscoveryDomainProvider> point,
    ExtensionDescriptor descriptor,
    ExtensionOrigin origin,
    String catalogSha256
) {
    public DiscoveryExtensionProvenance {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(origin, "origin");
        if (!DiscoveryExtensionPoints.DOMAIN_PROVIDERS.equals(point)) {
            throw new IllegalArgumentException("not the standard Discovery provider point");
        }
        if (catalogSha256 == null || !catalogSha256.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("catalog SHA-256 is required");
        }
    }

    Map<String, String> properties() {
        return Map.of(
            "sdk.provider.extension.pointId", point.id(),
            "sdk.provider.extension.contributionId", descriptor.id(),
            "sdk.provider.extension.sourceKind", origin.kind().name(),
            "sdk.provider.extension.sourceId", origin.sourceId(),
            "sdk.provider.extension.sourceVersion", origin.sourceVersion(),
            "sdk.provider.extension.sourceReference", origin.sourceReference(),
            "sdk.provider.extension.artifactSha256", origin.artifactSha256(),
            "sdk.provider.extension.trustEvidenceSha256", origin.trustEvidenceSha256(),
            "sdk.provider.extension.catalogSha256", catalogSha256);
    }
}
