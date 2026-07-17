package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class PluginArtifactIndexFixtures {
    private PluginArtifactIndexFixtures() {
    }

    static PluginArtifactIndex referenceIndex() {
        Entry rules = entry(
            "core-rules-1.0.0",
            ArtifactKind.RULE_PACKAGE,
            "core-rules",
            "1.0.0",
            "1",
            "0.9.0",
            "",
            List.of("rules"),
            List.of());
        Entry stable = entry(
            "advanced-tools-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.0.0",
            "1",
            "1.0.0",
            "2.0.0",
            List.of("rules", "transformations"),
            List.of(
                new Dependency(
                    ArtifactKind.RULE_PACKAGE,
                    "core-rules",
                    "=1.0.0",
                    false),
                new Dependency(
                    ArtifactKind.KNOWLEDGE_PACK,
                    "optional-examples",
                    "any",
                    true)));
        Entry future = entry(
            "advanced-tools-1.1.0",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.1.0",
            "1",
            "2.0.0",
            "3.0.0",
            List.of("rules", "transformations", "visitors"),
            List.of(new Dependency(
                ArtifactKind.RULE_PACKAGE,
                "core-rules",
                "=1.0.0",
                false)));
        Entry knowledge = entry(
            "number-theory-pack-1.0.0",
            ArtifactKind.KNOWLEDGE_PACK,
            "number-theory-pack",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of("examples"),
            List.of());
        return PluginArtifactIndex.create(
            "regelsuche-community-index",
            "2026-07-17.1",
            "regelsuche-curators",
            List.of(future, knowledge, stable, rules));
    }

    static Entry entry(
        String artifactId,
        ArtifactKind kind,
        String componentId,
        String version,
        String apiVersion,
        String minimumCoreVersion,
        String maximumCoreVersionExclusive,
        List<String> capabilities,
        List<Dependency> dependencies
    ) {
        String extension = switch (kind) {
            case JAVA_PLUGIN -> ".jar";
            case RULE_PACKAGE -> ".regelsuche";
            case KNOWLEDGE_PACK -> ".json";
        };
        String fileName = componentId + "-" + version + extension;
        String base = "https://plugins.example.test/artifacts/" + fileName;
        String signature = kind == ArtifactKind.JAVA_PLUGIN
            ? base + ".sig.json"
            : "";
        return Entry.create(
            artifactId,
            kind,
            componentId,
            version,
            apiVersion,
            minimumCoreVersion,
            maximumCoreVersionExclusive,
            capabilities,
            dependencies,
            fileName,
            hash(artifactId + "-bytes"),
            base,
            signature,
            "https://plugins.example.test/source/" + artifactId,
            "org.regelsuche.community");
    }

    static String hash(String material) {
        return PluginArtifactVerifier.sha256(material.getBytes(StandardCharsets.UTF_8));
    }
}
