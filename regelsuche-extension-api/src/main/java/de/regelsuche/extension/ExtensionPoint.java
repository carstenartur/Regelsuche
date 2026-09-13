package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.Objects;

/** A globally named extension contract with a runtime type token. */
@StableApi(since = "2")
public record ExtensionPoint<T>(String id, Class<T> contractType) {
    public ExtensionPoint {
        id = ExtensionIdentifiers.identifier(id, "extension point id");
        Objects.requireNonNull(contractType, "contractType");
    }

    /** Creates a typed extension point. */
    public static <T> ExtensionPoint<T> of(String id, Class<T> contractType) {
        return new ExtensionPoint<>(id, contractType);
    }
}
