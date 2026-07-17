package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactIndexTest {
    @Test
    void roundTripsCanonicalImmutableIndex(@TempDir Path tempDir) throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        Path path = index.write(tempDir.resolve("index.json"));

        PluginArtifactIndex replay = PluginArtifactIndex.load(path);

        assertEquals(index, replay);
        assertEquals(index.toCanonicalJson(), Files.readString(path));
        assertEquals(List.of("1.1.0", "1.0.0"),
            index.componentVersions(ArtifactKind.JAVA_PLUGIN, "advanced-tools")
                .stream().map(Entry::version).toList());
        assertTrue(index.entries().stream().allMatch(entry ->
            entry.identityHash().matches("sha256:[0-9a-f]{64}")));
    }

    @Test
    void semanticVersionOrderingSupportsZeroPrereleaseAndUnboundedNumbers() {
        assertTrue(PluginArtifactIndex.compareVersions("0.9.0", "1.0.0") < 0);
        assertTrue(PluginArtifactIndex.compareVersions(
            "1.0.0-alpha.10", "1.0.0-alpha.2") > 0);
        assertTrue(PluginArtifactIndex.compareVersions("1.0.0", "1.0.0-rc.1") > 0);
        assertEquals(0, PluginArtifactIndex.compareVersions(
            "1.0.0+build.1", "1.0.0+build.2"));
        assertTrue(PluginArtifactIndex.compareVersions(
            "2147483648.0.0", "2147483647.999999999999999999999.0") > 0);
        assertEquals(
            "999999999999999999999999999999.0.0",
            PluginArtifactIndex.requireVersion(
                "999999999999999999999999999999.0.0", "version"));
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.requireVersion("01.0.0", "version"));
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.requireVersion("1.0.0-alpha.01", "version"));
    }

    @Test
    void rejectsUnknownFieldsAndTamperedIndexHash(@TempDir Path tempDir)
            throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        String json = index.toCanonicalJson();
        Path unknown = tempDir.resolve("unknown.json");
        Files.writeString(
            unknown,
            json.replaceFirst("\\{", "{\"unknown\":true,"),
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.load(unknown));

        Path tampered = tempDir.resolve("tampered.json");
        Files.writeString(
            tampered,
            json.replace("regelsuche-curators", "different-curator"),
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.load(tampered));
    }

    @Test
    void rejectsMissingRequiredFieldsEvenWhenDtoDefaultsWouldPreserveHashes(
        @TempDir Path tempDir
    ) throws Exception {
        String json = PluginArtifactIndexFixtures.referenceIndex().toCanonicalJson();

        String missingOptional = json.replaceFirst(
            ",\"optional\":false", "");
        assertNotEquals(json, missingOptional);
        Path missingOptionalPath = tempDir.resolve("missing-optional.json");
        Files.writeString(missingOptionalPath, missingOptional, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.load(missingOptionalPath));

        String missingDependencies = json.replaceFirst(
            ",\"dependencies\":\\[\\]", "");
        assertNotEquals(json, missingDependencies);
        Path missingDependenciesPath = tempDir.resolve("missing-dependencies.json");
        Files.writeString(
            missingDependenciesPath,
            missingDependencies,
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.load(missingDependenciesPath));
    }

    @Test
    void rejectsDuplicateCoordinatesAndContentAliases() {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        Entry stable = index.componentVersions(
            ArtifactKind.JAVA_PLUGIN, "advanced-tools").get(1);
        Entry duplicateCoordinate = Entry.create(
            "advanced-tools-alias",
            stable.kind(),
            stable.componentId(),
            stable.version(),
            stable.apiVersion(),
            stable.minimumCoreVersion(),
            stable.maximumCoreVersionExclusive(),
            stable.capabilities(),
            stable.dependencies(),
            "advanced-tools-alias.jar",
            PluginArtifactIndexFixtures.hash("different-bytes"),
            "https://plugins.example.test/artifacts/advanced-tools-alias.jar",
            "https://plugins.example.test/artifacts/advanced-tools-alias.jar.sig.json",
            "https://plugins.example.test/source/advanced-tools-alias",
            stable.publisherId());
        List<Entry> duplicateEntries = new ArrayList<>(index.entries());
        duplicateEntries.add(duplicateCoordinate);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.create(
                "duplicate-index", "1.0.0", "curator", duplicateEntries));

        Entry contentAlias = Entry.create(
            "content-alias-1.0.0",
            ArtifactKind.KNOWLEDGE_PACK,
            "content-alias",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of(),
            "content-alias.json",
            stable.artifactSha256(),
            "https://plugins.example.test/artifacts/content-alias.json",
            "",
            "https://plugins.example.test/source/content-alias",
            stable.publisherId());
        List<Entry> aliasedEntries = new ArrayList<>(index.entries());
        aliasedEntries.add(contentAlias);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.create(
                "alias-index", "1.0.0", "curator", aliasedEntries));
    }

    @Test
    void rejectsMissingRequiredDependenciesAndInvalidArtifactUris() {
        Entry broken = PluginArtifactIndexFixtures.entry(
            "broken-plugin-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "broken-plugin",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of("rules"),
            List.of(new Dependency(
                ArtifactKind.RULE_PACKAGE,
                "missing-rules",
                "=1.0.0",
                false)));
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndex.create(
                "broken-index", "1.0.0", "curator", List.of(broken)));

        assertThrows(IllegalArgumentException.class, () -> Entry.create(
            "bad-uri-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "bad-uri",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of(),
            "bad-uri.jar",
            PluginArtifactIndexFixtures.hash("bad-uri"),
            "http://plugins.example.test/bad-uri.jar",
            "https://plugins.example.test/bad-uri.jar.sig.json",
            "https://plugins.example.test/source/bad-uri",
            "org.regelsuche.community"));

        assertThrows(IllegalArgumentException.class, () -> Entry.create(
            "remote-file-uri-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "remote-file-uri",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of(),
            "remote-file-uri.jar",
            PluginArtifactIndexFixtures.hash("remote-file-uri"),
            "file://remote.example.test/artifacts/remote-file-uri.jar",
            "https://plugins.example.test/remote-file-uri.jar.sig.json",
            "https://plugins.example.test/source/remote-file-uri",
            "org.regelsuche.community"));
    }

    @Test
    void identityHashChangesWithDistributionOrCompatibilityIdentity() {
        Entry base = PluginArtifactIndexFixtures.entry(
            "identity-base-1.0.0",
            ArtifactKind.RULE_PACKAGE,
            "identity-base",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of("rules"),
            List.of());
        Entry changed = Entry.create(
            base.artifactId(),
            base.kind(),
            base.componentId(),
            base.version(),
            base.apiVersion(),
            base.minimumCoreVersion(),
            "2.0.0",
            base.capabilities(),
            base.dependencies(),
            base.artifactFileName(),
            base.artifactSha256(),
            base.artifactUri(),
            base.signatureManifestUri(),
            base.provenanceUri(),
            base.publisherId());

        assertNotEquals(base.identityHash(), changed.identityHash());
    }
}
