package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginArtifactIndexEvidenceTest {
    @Test
    void writesCanonicalIndexAndResolutionReceipts() throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        PluginArtifactResolver resolver = new PluginArtifactResolver();
        var resolved = resolver.resolve(index, ResolutionRequest.latestCompatible(
            "reference-latest-compatible",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.5.0",
            "1",
            List.of("transformations")));
        var unresolved = resolver.resolve(index, ResolutionRequest.exact(
            "reference-incompatible-exact",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.1.0",
            "1.5.0",
            "1",
            List.of()));

        Path output = Path.of("build", "reports", "plugin-artifact-index");
        Files.createDirectories(output);
        write(output.resolve("index.json"), index.toCanonicalJson());
        write(output.resolve("resolved.json"), resolved.toCanonicalJson());
        write(output.resolve("unresolved.json"), unresolved.toCanonicalJson());

        assertEquals(index.toCanonicalJson(),
            Files.readString(output.resolve("index.json")));
        assertEquals(resolved.toCanonicalJson(),
            Files.readString(output.resolve("resolved.json")));
        assertEquals(unresolved.toCanonicalJson(),
            Files.readString(output.resolve("unresolved.json")));
    }

    private static void write(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }
}
