package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.extension.runtime.ExtensionRuntime;
import de.regelsuche.extension.runtime.ExtensionRuntimeConfig;
import org.junit.jupiter.api.Test;

class ExtensionRuntimeConsumerTest {
    @Test
    void classpathPluginContributesTypedExtensionWithStableReloadIdentity() {
        var config = ExtensionRuntimeConfig.classpath(getClass().getClassLoader());
        try (var runtime = ExtensionRuntime.open(config)) {
            var registration = runtime.catalog()
                .find(GreetingPlugin.POINT, "hello")
                .orElseThrow();
            assertEquals("Hello Ada", registration.implementation().greet("Ada"));
            assertEquals("greeting-plugin", registration.origin().sourceId());
            String before = runtime.catalog().contentHash();

            var result = runtime.reload(config);

            assertTrue(result.applied(), result::diagnostic);
            assertEquals(before, result.currentCatalogHash());
            assertEquals(before, runtime.catalog().contentHash());
        }
    }
}
