package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
            List.of(),
            "compatible",
            List.of(),
            "https://example.test/release",
            false,
            false,
            List.of("UNSIGNED_PLUGIN_ARTIFACT")
        );

        PluginSnapshot snapshot = PluginSnapshot.from(plugin);

        assertEquals("https://example.test/release", snapshot.provenance());
        assertFalse(snapshot.signed());
    }
}
