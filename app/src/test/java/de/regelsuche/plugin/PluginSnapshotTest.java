package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PluginSnapshotTest {
    @Test
    void snapshotIncludesTrustInputs() {
        PluginRuntime.LoadedPlugin plugin = new PluginRuntime.LoadedPlugin(
            "demo",
            "Demo Plugin",
            "1.0.0",
            "plugins",
            true,
            "1",
            "1.0.0",
            List.of("rules"),
            List.of(new PluginCatalogDependency("algebra-core", ">=1.0.0", false, "version-not-checked")),
            "compatible",
            List.of("Dependency version not checked: algebra-core (>=1.0.0)"),
            "https://example.test/release",
            true,
            false,
            false,
            List.of("SIGNATURE_NOT_VERIFIED", "UNKNOWN_SOURCE")
        );

        PluginSnapshot snapshot = PluginSnapshot.from(plugin);

        assertEquals(List.of("rules"), snapshot.capabilities());
        assertEquals("version-not-checked", snapshot.dependencies().getFirst().status());
        assertEquals(List.of("Dependency version not checked: algebra-core (>=1.0.0)"), snapshot.compatibilityIssues());
        assertEquals("https://example.test/release", snapshot.provenance());
        assertTrue(snapshot.signaturePresent());
        assertFalse(snapshot.signatureVerified());
        assertEquals(List.of("SIGNATURE_NOT_VERIFIED", "UNKNOWN_SOURCE"), snapshot.trustWarnings());
    }

    @Test
    void snapshotEqualityTracksCatalogRelevantFields() {
        PluginRuntime.LoadedPlugin base = new PluginRuntime.LoadedPlugin(
            "demo",
            "Demo Plugin",
            "1.0.0",
            "plugins",
            true,
            "1",
            "1.0.0",
            List.of("rules"),
            List.of(new PluginCatalogDependency("algebra-core", ">=1.0.0", false, "version-not-checked")),
            "not-checked",
            List.of("Dependency version not checked: algebra-core (>=1.0.0)"),
            "https://example.test/release-a",
            true,
            false,
            false,
            List.of("SIGNATURE_NOT_VERIFIED", "UNKNOWN_SOURCE")
        );
        PluginRuntime.LoadedPlugin changed = new PluginRuntime.LoadedPlugin(
            "demo",
            "Demo Plugin",
            "1.0.0",
            "plugins",
            true,
            "1",
            "1.0.0",
            List.of("rules", "transformations"),
            List.of(new PluginCatalogDependency("algebra-core", ">=1.0.0", false, "present")),
            "compatible",
            List.of(),
            "https://example.test/release-b",
            false,
            false,
            false,
            List.of("MISSING_SIGNATURE_METADATA", "MISSING_PROVENANCE")
        );

        assertNotEquals(PluginSnapshot.from(base), PluginSnapshot.from(changed));
    }
}
