package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.Objects;

/** One typed contribution together with host-owned origin metadata. */
@StableApi(since = "2")
public record RegisteredExtension<T>(
    ExtensionPoint<T> point,
    ExtensionDescriptor descriptor,
    T implementation,
    ExtensionOrigin origin
) {
    public RegisteredExtension {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(origin, "origin");
        if (!point.contractType().isInstance(implementation)) {
            throw new IllegalArgumentException(
                "implementation does not implement " + point.contractType().getName()
            );
        }
    }
}
