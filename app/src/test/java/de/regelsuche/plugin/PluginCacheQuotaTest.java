package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class PluginCacheQuotaTest {
    @TempDir Path temporary;
    static PluginDistributionClient.Limits limits() {
        return new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64);
    }
    private Path root(long bytes, int entries) throws IOException {
        Path root = temporary.resolve("packages");
        new PluginInstallationStore(root, limits());
        // A retained, already provisioned policy: admission must honour it after restart.
        Files.writeString(root.resolve(".cache-quota"), "regelsuche.plugin-cache-quota/v1\n" + bytes + "\n" + entries + "\n",
            StandardOpenOption.CREATE_NEW);
        return root;
    }
    @Test void exactByteBoundaryRejectsOneMoreByteWithoutWritingIt() throws Exception {
        var store = new PluginInstallationStore(root(128, 20), limits());
        try (var work = store.work()) {
            PluginInstallationStore.write(work.directory(), "payload", new byte[128]);
            assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "extra", new byte[1]));
            assertFalse(Files.exists(work.directory().resolve("extra")));
            assertEquals(128, Files.size(work.directory().resolve("payload")));
        }
    }
    @Test void directoryAndEmptyFileEntriesCannotBypassCapacity() throws Exception {
        var store = new PluginInstallationStore(root(128, 3), limits());
        try (var work = store.work()) {
            PluginInstallationStore.write(work.directory(), "nested/empty", new byte[0]);
            assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "more/empty", new byte[0]));
            assertFalse(Files.exists(work.directory().resolve("more")));
        }
    }
    @Test void twoThreadsCannotSpendTheSameLastBytes() throws Exception {
        Path root = root(128, 20);
        var first = new PluginInstallationStore(root, limits());
        var second = new PluginInstallationStore(root, limits());
        try (var a = first.work(); var b = second.work(); var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = List.of(pool.submit(() -> writeAfter(start, a.directory())), pool.submit(() -> writeAfter(start, b.directory())));
            start.countDown();
            assertEquals(1, results.get(0).get() + results.get(1).get());
        }
    }
    private static int writeAfter(CountDownLatch start, Path directory) throws Exception {
        start.await();
        try { PluginInstallationStore.write(directory, "payload", new byte[128]); return 1; }
        catch (SecurityException full) { return 0; }
    }
    @Test void twoProcessesCannotSpendTheSameLastBytes() throws Exception {
        Path root = root(128, 20);
        Process a = child(root, "race"), b = child(root, "race");
        try {
            var ar = a.inputReader(); var br = b.inputReader();
            assertEquals("READY", ar.readLine()); assertEquals("READY", br.readLine());
            a.outputWriter().write("go\n"); a.outputWriter().flush();
            b.outputWriter().write("go\n"); b.outputWriter().flush();
            var result = new ArrayList<>(List.of(ar.readLine(), br.readLine()));
            Collections.sort(result);
            assertEquals(List.of("ADMITTED", "FULL"), result);
            // Keep both stages alive until both admission decisions have been observed.
            a.outputWriter().write("close\n"); a.outputWriter().flush();
            b.outputWriter().write("close\n"); b.outputWriter().flush();
            assertTrue(a.waitFor(10, TimeUnit.SECONDS)); assertTrue(b.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, a.exitValue()); assertEquals(0, b.exitValue());
        } finally { a.destroyForcibly(); b.destroyForcibly(); }
    }
    @Test void processDeathLeavesStagingChargedAfterRestart() throws Exception {
        Path root = root(128, 20);
        Process process = child(root, "crash");
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
            var store = new PluginInstallationStore(root, limits());
            try (var work = store.work()) {
                assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "extra", new byte[33]));
                PluginInstallationStore.write(work.directory(), "fits", new byte[32]);
            }
        } finally { process.destroyForcibly(); }
    }
    @Test void symbolicLinksAndCorruptPolicyFailClosed() throws Exception {
        Path root = root(128, 20);
        var store = new PluginInstallationStore(root, limits());
        try (var work = store.work()) {
            Files.createSymbolicLink(root.resolve("foreign"), temporary);
            assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "payload", new byte[1]));
            Files.delete(root.resolve("foreign"));
            Files.writeString(root.resolve(".cache-quota"), "damaged");
            assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "payload", new byte[1]));
        }
    }
    @Test void defaultAdmissionIsFiniteAndControlFileCorruptionDoesNotAllowWrites() throws Exception {
        Path root = temporary.resolve("default");
        var store = new PluginInstallationStore(root, limits());
        try (var work = store.work()) {
            assertEquals(PluginCacheQuota.DEFAULT, PluginCacheQuota.locked(root, () -> PluginCacheQuota.policy(root)));
            assertTrue(PluginCacheQuota.DEFAULT.bytes() > 0);
            assertTrue(PluginCacheQuota.DEFAULT.entries() > 0);
            Files.delete(root.resolve(".cache-quota"));
            Files.createSymbolicLink(root.resolve(".cache-quota"), temporary);
            assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "data", new byte[1]));
            Files.delete(root.resolve(".cache-quota"));
            Files.writeString(root.resolve(".cache-quota.lock"), "damaged");
            assertThrows(SecurityException.class, () -> PluginInstallationStore.write(work.directory(), "data", new byte[1]));
            Files.writeString(root.resolve(".cache-quota.lock"), "");
        }
    }
    private static Process child(Path root, String mode) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", childClasspath(), Child.class.getName(), root.toString(), mode)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static String childClasspath() {
        var entries = new LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        // Gradle may load tests outside the worker JVM's java.class.path.
        for (ClassLoader loader = Child.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        try { entries.add(Path.of(url.toURI()).toString()); }
                        catch (java.net.URISyntaxException bad) { throw new IllegalStateException(bad); }
                    }
                }
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            var store = new PluginInstallationStore(Path.of(args[0]), limits());
            try (var work = store.work()) {
                if (args[1].equals("crash")) {
                    PluginInstallationStore.write(work.directory(), "payload", new byte[96]);
                    Runtime.getRuntime().halt(0);
                }
                System.out.println("READY");
                var commands = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                commands.readLine();
                try { PluginInstallationStore.write(work.directory(), "payload", new byte[128]); System.out.println("ADMITTED"); }
                catch (SecurityException full) { System.out.println("FULL"); }
                commands.readLine();
            }
        }
    }
}
