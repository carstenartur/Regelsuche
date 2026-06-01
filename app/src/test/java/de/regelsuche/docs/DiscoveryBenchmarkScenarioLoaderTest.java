package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

class DiscoveryBenchmarkScenarioLoaderTest {
    @Test
    void loadAllSupportsJarClasspathResources() throws Exception {
        Path jar = Files.createTempFile("scenarios", ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            addEntry(
                    jarOut,
                    "discovery-scenario-rules/test-pack.yaml",
                    """
                    id: test-pack
                    rules:
                      - id: test_rule
                        from: a + b
                        to: b + a
                        effects: [normalizing]
                        family: synthetic
                    """);
            addEntry(
                    jarOut,
                    "discovery-scenarios/test.yaml",
                    """
                    id: test
                    displayName: Test Scenario
                    inputExpression: a+b
                    targetExpression: b+a
                    expectations: [BRIDGE_REQUIRED]
                    enabledRulePacks: [test-pack]
                    requiredBridgeEffects: [normalizing]
                    budgets:
                      maxDepth: 2
                      maxStates: 10
                      timeoutMillis: 1000
                    gallery:
                      generateSvg: false
                      preferredPathCount: 1
                      minVisibleNodes: 1
                    """);
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(loader);
            List<DiscoveryBenchmarkScenario> scenarios =
                    new DiscoveryBenchmarkScenarioLoader().loadAll("discovery-scenarios");
            assertEquals(1, scenarios.size());
            assertEquals("test", scenarios.getFirst().id());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static void addEntry(JarOutputStream out, String path, String content) throws Exception {
        out.putNextEntry(new JarEntry(path));
        out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
