package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainModelProofRepositoryTest {
    @TempDir Path temporary;

    @Test void retainsOnlyIssuerProducedReceiptsForTheExactRetainedSource() throws Exception {
        Path directory = temporary.resolve("exports");
        var sources = new DomainExportWorkspaceRepository(directory);
        var workspace = retainSource(sources, false);
        var foreign = retainSource(sources, true);
        var proofs = new DomainModelProofRepository(directory);
        var receipt = DomainModelProof.prove(workspace);
        assertThrows(IllegalArgumentException.class, () -> proofs.retain(foreign, receipt));
        proofs.retain(workspace, receipt);
        proofs.retain(workspace, receipt);
        assertArrayEquals(receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8),
            proofs.find(workspace.runId(), receipt.contentHash()).orElseThrow());
        assertTrue(proofs.find(foreign.runId(), receipt.contentHash()).isEmpty());
        assertEquals(workspace.toCanonicalJson(), sources.find(workspace.runId()).orElseThrow().toCanonicalJson());
        try (var files = Files.list(proofDirectory(workspace))) { assertEquals(1, files.count()); }
        var unretained = new DomainModelProofRepository(temporary.resolve("missing"));
        assertThrows(IllegalStateException.class, () -> unretained.retain(workspace, receipt));
    }

    @Test void boundsDistinctReceiptsWithoutPreventingIdempotentRetention() throws Exception {
        Path directory = temporary.resolve("exports");
        var workspace = retainSource(new DomainExportWorkspaceRepository(directory), false);
        var proofs = new DomainModelProofRepository(directory);
        DomainModelProof.Receipt first = null;
        for (int index = 0; index < 16; index++) {
            var receipt = DomainModelProof.prove(workspace, new DomainModelProof.Budget(0, 512,
                DomainFiniteDataCheck.Budget.defaults(), 200_000 - index, 200_000, 200_000));
            if (first == null) first = receipt;
            proofs.retain(workspace, receipt);
        }
        proofs.retain(workspace, first);
        var extra = DomainModelProof.prove(workspace);
        assertThrows(IllegalStateException.class, () -> proofs.retain(workspace, extra));
        try (var files = Files.list(proofDirectory(workspace))) { assertEquals(16, files.count()); }
    }

    @Test void rejectsOversizedOrChangedReceiptBytesAndMalformedIdentities() throws Exception {
        Path directory = temporary.resolve("exports");
        var workspace = retainSource(new DomainExportWorkspaceRepository(directory), false);
        var proofs = new DomainModelProofRepository(directory);
        var receipt = DomainModelProof.prove(workspace);
        proofs.retain(workspace, receipt);
        Path file = proofDirectory(workspace).resolve(receipt.contentHash().substring(7) + ".json");
        Files.writeString(file, " ".repeat(1_048_577));
        assertThrows(IllegalStateException.class, () -> proofs.find(workspace.runId(), receipt.contentHash()));
        Files.writeString(file, "{}");
        assertThrows(IllegalStateException.class, () -> proofs.find(workspace.runId(), receipt.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> proofs.find(workspace.runId(), "../outside"));
        assertThrows(IllegalArgumentException.class, () -> proofs.find("bad", receipt.contentHash()));
    }

    @Test void doesNotFollowReceiptOrParentSymlinks() throws Exception {
        Path directory = temporary.resolve("exports");
        var workspace = retainSource(new DomainExportWorkspaceRepository(directory), false);
        var proofs = new DomainModelProofRepository(directory);
        var receipt = DomainModelProof.prove(workspace);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path parent = proofDirectory(workspace);
        Files.createDirectories(parent.getParent());
        Files.createSymbolicLink(parent, outside);
        assertThrows(IllegalStateException.class, () -> proofs.retain(workspace, receipt));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        Files.delete(parent);
        proofs.retain(workspace, receipt);
        Path file = parent.resolve(receipt.contentHash().substring(7) + ".json");
        Path outsideFile = outside.resolve("receipt.json");
        Files.move(file, outsideFile);
        Files.createSymbolicLink(file, outsideFile);
        assertThrows(IllegalStateException.class, () -> proofs.find(workspace.runId(), receipt.contentHash()));
        assertEquals(receipt.toCanonicalJson(), Files.readString(outsideFile));
    }

    @Test void independentJavaWritersRespectOneFilesystemQuotaLock() throws Exception {
        Path directory = temporary.resolve("exports");
        var workspace = retainSource(new DomainExportWorkspaceRepository(directory), false);
        var proofs = new DomainModelProofRepository(directory);
        for (int index = 0; index < 15; index++) proofs.retain(workspace, DomainModelProof.prove(workspace,
            new DomainModelProof.Budget(0, 512, DomainFiniteDataCheck.Budget.defaults(), 200_000 - index, 200_000, 200_000)));
        Path lockPath = temporary.resolve("exports-model-proofs/.locks").resolve(workspace.runId().substring(7) + ".lock");
        Files.createDirectories(lockPath.getParent());
        Process first = null, second = null;
        try {
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                assertTrue(lock.isValid());
                first = writer(directory, workspace.runId(), 150_000);
                second = writer(directory, workspace.runId(), 140_000);
                awaitReady(first);
                awaitReady(second);
                assertFalse(first.waitFor(1, TimeUnit.SECONDS), "writer bypassed the held cross-process quota lock");
                assertFalse(second.waitFor(1, TimeUnit.SECONDS), "second writer bypassed the held quota lock");
            }
            assertTrue(first.waitFor(15, TimeUnit.SECONDS), "first writer did not finish after lock release");
            assertTrue(second.waitFor(15, TimeUnit.SECONDS), "second writer did not finish after lock release");
            assertArrayEquals(new int[]{0, 2}, java.util.stream.IntStream.of(first.exitValue(), second.exitValue()).sorted().toArray());
            try (var files = Files.list(proofDirectory(workspace))) { assertEquals(16, files.count()); }
        } finally {
            if (first != null && first.isAlive()) first.destroyForcibly().waitFor();
            if (second != null && second.isAlive()) second.destroyForcibly().waitFor();
        }
    }

    private Process writer(Path directory, String runId, int budget) throws Exception {
        var entries = new LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = Writer.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
            }
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-cp", String.join(java.io.File.pathSeparator, entries), Writer.class.getName(),
            directory.toString(), runId, Integer.toString(budget))
            .redirectError(temporary.resolve("writer-" + budget + ".log").toFile()).start();
    }

    private static void awaitReady(Process process) throws Exception {
        var line = CompletableFuture.supplyAsync(() -> {
            try { return process.inputReader().readLine(); }
            catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
        });
        assertEquals("READY", line.get(15, TimeUnit.SECONDS));
    }

    public static final class Writer {
        private Writer() { }
        public static void main(String[] arguments) throws Exception {
            Path directory = Path.of(arguments[0]);
            var workspace = new DomainExportWorkspaceRepository(directory).find(arguments[1]).orElseThrow();
            var receipt = DomainModelProof.prove(workspace, new DomainModelProof.Budget(0, 512,
                DomainFiniteDataCheck.Budget.defaults(), Integer.parseInt(arguments[2]), 200_000, 200_000));
            System.out.println("READY");
            System.out.flush();
            try { new DomainModelProofRepository(directory).retain(workspace, receipt); }
            catch (IllegalStateException exception) {
                if (!exception.getMessage().contains("limit")) throw exception;
                System.exit(2);
            }
        }
    }

    private DomainExportWorkspace retainSource(DomainExportWorkspaceRepository sources, boolean recurrence) throws Exception {
        Path export = Files.createTempDirectory(temporary, "source-");
        new DomainDiscoveryExport().write(export, DomainDownstreamValidationTest.source(recurrence, false));
        return sources.retain(new DomainDiscoveryExportVerifier().requireVerified(export));
    }

    private Path proofDirectory(DomainExportWorkspace workspace) {
        return temporary.resolve("exports-model-proofs").resolve(workspace.runId().substring(7));
    }
}
