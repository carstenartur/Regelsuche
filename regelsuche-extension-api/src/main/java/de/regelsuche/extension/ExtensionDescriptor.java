package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.List;

/** Stable identity and display metadata for one contribution. */
@StableApi(since = "2")
public record ExtensionDescriptor(String id, String name, List<String> tags) {
    public ExtensionDescriptor {
        id = ExtensionIdentifiers.identifier(id, "extension id");
        name = ExtensionIdentifiers.requiredText(name, "extension name");
        tags = ExtensionIdentifiers.normalizedStrings(tags, "tags");
    }

    /** Creates a descriptor whose display name equals its id. */
    public static ExtensionDescriptor named(String id) {
        return new ExtensionDescriptor(id, id, List.of());
    }
}
