package de.regelsuche.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExtensionApiContractTest {
    @Test
    void invalidPointIdsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionPoint.of("bad id", Runnable.class));
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionPoint.of("", Runnable.class));
    }

    @Test
    void pointRetainsRuntimeContractType() {
        var point = ExtensionPoint.of("example.runnable", Runnable.class);
        assertEquals("example.runnable", point.id());
        assertSame(Runnable.class, point.contractType());
    }

    @Test
    void descriptorCanonicalizesTagsDefensively() {
        var source = new ArrayList<>(List.of(" z ", "a", "a"));
        var descriptor = new ExtensionDescriptor("sample", "Sample", source);
        source.clear();
        assertEquals(List.of("a", "z"), descriptor.tags());
        assertThrows(UnsupportedOperationException.class,
            () -> descriptor.tags().add("new"));
    }

    @Test
    void genericPluginContractContainsOnlyGenericLifecycleMethods() {
        assertEquals("2", ExtensionApi.VERSION);
        assertEquals(Set.of("descriptor", "contribute"),
            Arrays.stream(RegelsuchePlugin.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet()));
    }

    @Test
    void externalOriginRequiresArtifactHashWhenTrustHashIsPresent() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExtensionOrigin(
                ExtensionOrigin.OriginKind.EXTERNAL_PLUGIN,
                "p", "1", "fixture.jar", "", "sha256:" + "a".repeat(64)));
    }

    @Test
    void pluginDescriptorNormalizesCapabilitiesAndDependencies() {
        var descriptor = new PluginDescriptor(
            "Plugin-A", "Plugin A", "1.2.3", ExtensionApi.VERSION, "1.0.0",
            Set.of(" z ", "a"),
            List.of(
                new PluginDependency("DEP-B", "2", false),
                new PluginDependency("dep-a", null, true)),
            " fixture ");

        assertEquals("plugin-a", descriptor.id());
        assertEquals(Set.of("a", "z"), descriptor.capabilities());
        assertEquals(List.of("dep-a", "dep-b"), descriptor.dependencies().stream()
            .map(PluginDependency::pluginId).toList());
        assertEquals("fixture", descriptor.provenance());
    }
}
