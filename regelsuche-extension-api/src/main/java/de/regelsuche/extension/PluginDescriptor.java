package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable plugin identity and compatibility declaration. */
@StableApi(since = "2")
public record PluginDescriptor(
    String id,
    String name,
    String version,
    String apiVersion,
    String minimumCoreVersion,
    Set<String> capabilities,
    List<PluginDependency> dependencies,
    String provenance
) {
    public PluginDescriptor {
        id = ExtensionIdentifiers.identifier(id, "plugin id").toLowerCase(Locale.ROOT);
        name = ExtensionIdentifiers.requiredText(name, "plugin name");
        version = ExtensionIdentifiers.requiredText(version, "plugin version");
        apiVersion = ExtensionIdentifiers.requiredText(apiVersion, "apiVersion");
        // Only an absent declaration defaults; supplied text must reach the strict parser.
        minimumCoreVersion = minimumCoreVersion == null ? "0.0.0" : minimumCoreVersion;
        capabilities = Collections.unmodifiableSet(
            new TreeSet<>(ExtensionIdentifiers.normalizedStrings(capabilities, "capabilities"))
        );
        dependencies = dependencies == null
            ? List.of()
            : dependencies.stream()
                .map(dependency -> Objects.requireNonNull(dependency, "dependency"))
                .sorted(Comparator.comparing(PluginDependency::pluginId)
                    .thenComparing(PluginDependency::versionConstraint)
                    .thenComparing(PluginDependency::optional))
                .toList();
        provenance = ExtensionIdentifiers.optionalText(provenance);
    }
}
