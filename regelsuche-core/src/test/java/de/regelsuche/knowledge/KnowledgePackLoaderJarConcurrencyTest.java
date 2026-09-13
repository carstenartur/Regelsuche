package de.regelsuche.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class KnowledgePackLoaderJarConcurrencyTest {
    @TempDir Path temporary;
    private Path archive;
    private URLClassLoader resources;
    private Object expected;
    private byte[] originalArchive;

    @BeforeEach void realPackJar() throws Exception {
        Path directory = temporary.resolve("directory-packs");
        Files.createDirectories(directory.resolve("nested"));
        var files = new TreeMap<String, byte[]>();
        for (var entry : Map.of("a.rules.yaml", "sympy-polynomial-basic.rules.yaml",
                "nested/z.rules.yaml", "sympy-rational-basic.rules.yaml").entrySet()) {
            try (var input = KnowledgePackLoader.class.getResourceAsStream("/rules/packs/" + entry.getValue())) {
                assertNotNull(input);
                byte[] bytes = input.readAllBytes();
                files.put(entry.getKey(), bytes);
                Files.write(directory.resolve(entry.getKey()), bytes);
            }
        }
        expected = observation(new KnowledgePackLoader().loadAll(directory));
        archive = temporary.resolve("pack copies ! ü #.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (String name : List.of("rules/", "rules/packs/", "rules/packs/nested/")) {
                output.putNextEntry(new JarEntry(name)); output.closeEntry();
            }
            for (var entry : files.entrySet()) {
                output.putNextEntry(new JarEntry("rules/packs/" + entry.getKey()));
                output.write(entry.getValue()); output.closeEntry();
            }
        }
        originalArchive = Files.readAllBytes(archive);
        resources = new URLClassLoader(new URL[] {archive.toUri().toURL()}, null);
        assertEquals("jar", resources.getResource("rules/packs").getProtocol());
    }

    @AfterEach void closeAndCheckOriginalBytes() throws Exception {
        if (resources != null) resources.close();
        if (originalArchive != null) assertArrayEquals(originalArchive, Files.readAllBytes(archive));
    }

    @Test void aSecondLoaderCompletesWhileTheFirstStillOwnsItsJarFileSystem() throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstLoader = new KnowledgePackLoader() {
            @Override public List<KnowledgePack> loadAll(Path directory) {
                assertEquals("jar", directory.getFileSystem().provider().getScheme());
                inside.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS), "first JAR owner was not released"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return super.loadAll(directory);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> load(firstLoader));
            try {
                assertTrue(inside.await(10, TimeUnit.SECONDS), "first loader did not open the real JAR");
                var second = executor.submit(() -> load(new KnowledgePackLoader()));
                var loaded = assertDoesNotThrow(() -> second.get(10, TimeUnit.SECONDS));
                assertEquals(expected, observation(loaded));
                assertFalse(first.isDone(), "second load must complete before the first owner closes");
            } finally { release.countDown(); }
            assertEquals(expected, observation(first.get(10, TimeUnit.SECONDS)));
        }
    }

    @Test void loadingNeitherBorrowsNorClosesAnExistingUriRegisteredFileSystem() throws Exception {
        URI uri = URI.create("jar:" + archive.toUri());
        try (var existing = FileSystems.newFileSystem(uri, Map.of())) {
            var loader = new KnowledgePackLoader() {
                @Override public List<KnowledgePack> loadAll(Path directory) {
                    assertNotSame(existing, directory.getFileSystem());
                    return super.loadAll(directory);
                }
            };
            assertEquals(expected, observation(assertDoesNotThrow(() -> load(loader))));
            assertTrue(existing.isOpen());
            assertSame(existing, FileSystems.getFileSystem(uri));
            assertTrue(Files.size(existing.getPath("/rules/packs/a.rules.yaml")) > 0);
        }
    }

    @Test void repeatedJarLoadsPreserveDirectoryOrderRulesRecognitionAndProvenance() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            var loaded = load(new KnowledgePackLoader());
            assertEquals(2, loaded.size());
            assertEquals(List.of("sympy-polynomial-basic", "sympy-rational-basic"),
                loaded.stream().map(KnowledgePack::packId).toList());
            assertEquals(expected, observation(loaded));
        }
    }

    private List<KnowledgePack> load(KnowledgePackLoader loader) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(resources);
        try { return loader.loadClasspathPacks(); }
        finally { thread.setContextClassLoader(previous); }
    }

    private static Object observation(List<KnowledgePack> packs) {
        return packs.stream().map(pack -> List.of(pack.packId(), pack.displayName(), pack.sourceProject(), pack.license(),
            pack.sourceUrl(), pack.sourceVersion(), pack.sourceReference(), pack.enabledByDefault(), pack.maturity(), pack.tier(),
            pack.categories(), pack.rules().stream().map(RuleInventoryFingerprint::ruleContentHash).toList(),
            pack.knownStructures().stream().map(structure -> List.of(structure.id(), structure.domainId(),
                structure.matcher().canonicalDescriptor(), structure.requiredAssumptions(), structure.consequenceIds(),
                structure.metadata().canonicalDescriptor())).toList())).toList();
    }
}
