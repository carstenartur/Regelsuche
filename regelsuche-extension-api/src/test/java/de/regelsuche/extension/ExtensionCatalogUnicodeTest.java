package de.regelsuche.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExtensionCatalogUnicodeTest {
    private static final List<String> MALFORMED = List.of(
        "\uD800", "\uDC00", "a\uD800z", "\uD800\uD800",
        "\uDC00\uD800", "\uD800a\uDC00", "\uD83D\uDE00\uD800");
    private static final String SUPPLEMENTARY = "\uD835\uDD61 \uD83D\uDE00";
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("unicode.runnable", Runnable.class);

    @Test
    void malformedUnicodeCannotEnterRequiredMetadata() {
        for (String value : MALFORMED) {
            assertThrows(IllegalArgumentException.class,
                () -> new ExtensionDescriptor("r", value, List.of()));
            assertThrows(IllegalArgumentException.class,
                () -> pluginDescriptor(value, Set.of(), "fixture"));
            assertThrows(IllegalArgumentException.class,
                () -> ExtensionOrigin.classpathPlugin("fixture", value, "fixture"));
        }
    }

    @Test
    void malformedUnicodeCannotEnterTagsOrCapabilities() {
        for (String value : MALFORMED) {
            assertThrows(IllegalArgumentException.class,
                () -> new ExtensionDescriptor("r", "Valid", List.of(value)));
            assertThrows(IllegalArgumentException.class,
                () -> pluginDescriptor("Valid", Set.of(value), "fixture"));
        }
    }

    @Test
    void malformedUnicodeCannotEnterOptionalProvenance() {
        for (String value : MALFORMED) {
            assertThrows(IllegalArgumentException.class,
                () -> ExtensionOrigin.classpathPlugin("fixture", "1", value));
            assertThrows(IllegalArgumentException.class,
                () -> pluginDescriptor("Valid", Set.of(), value));
        }
    }

    @Test
    void supplementaryCharactersHaveExactUtf8ManifestAndIndependentGoldenHash() {
        var descriptor = new ExtensionDescriptor("r", " " + SUPPLEMENTARY + " ",
            List.of(SUPPLEMENTARY, " a ", SUPPLEMENTARY));
        var origin = ExtensionOrigin.classpathPlugin("fixture", "1", "literal?");
        var catalog = ExtensionCatalogs.of(List.of(new RegisteredExtension<>(
            POINT, descriptor, (Runnable) () -> {}, origin)));
        String expected = "{\"extensions\":[{\"point\":\"unicode.runnable\","
            + "\"contract\":\"java.lang.Runnable\",\"id\":\"r\",\"name\":\""
            + SUPPLEMENTARY + "\",\"tags\":[\"a\",\"" + SUPPLEMENTARY
            + "\"],\"origin\":{\"kind\":\"CLASSPATH_PLUGIN\",\"sourceId\":\"fixture\","
            + "\"sourceVersion\":\"1\",\"sourceReference\":\"literal?\","
            + "\"artifactSha256\":\"\",\"trustEvidenceSha256\":\"\"}}]}";

        assertEquals(expected, catalog.canonicalManifest());
        assertEquals(expected, new String(expected.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8));
        // Computed independently from the exact UTF-8 JSON, not by the catalog implementation.
        assertEquals("sha256:a7dee2a81519763a36b695fd7ec29c0c3a19dfec499ad59b6d717a4eff2a12a1",
            catalog.contentHash());
    }

    @Test
    void legitimateQuestionMarkRemainsValidButCannotAliasAnUnpairedSurrogate() {
        var catalog = ExtensionCatalogs.of(List.of(new RegisteredExtension<>(POINT,
            new ExtensionDescriptor("r", "?", List.of()), (Runnable) () -> {},
            ExtensionOrigin.classpathPlugin("fixture", "1", null))));
        assertTrue(catalog.canonicalManifest().contains("\"name\":\"?\""));
        assertThrows(IllegalArgumentException.class,
            () -> new ExtensionDescriptor("r", "\uD800", List.of()));
    }

    private static PluginDescriptor pluginDescriptor(
        String name, Set<String> capabilities, String provenance
    ) {
        return new PluginDescriptor("fixture", name, "1", ExtensionApi.VERSION,
            ExtensionApi.CORE_COMPATIBILITY_VERSION, capabilities, List.of(), provenance);
    }
}
