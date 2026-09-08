package de.regelsuche.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicJsonFileTest {

    @Test
    void writesUtf8AndCreatesParentDirectories(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("nested/dir/payload.json");
        AtomicJsonFile.writeUtf8(target, "{\"k\":\"vä\"}");
        assertTrue(Files.exists(target));
        assertEquals("{\"k\":\"vä\"}",
            Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void overwritesExistingFileAtomically(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("payload.json");
        AtomicJsonFile.writeUtf8(target, "first");
        AtomicJsonFile.writeUtf8(target, "second");
        assertEquals("second", Files.readString(target, StandardCharsets.UTF_8));
        // tmp file should not linger after a successful write
        assertFalse(Files.exists(tmp.resolve("payload.json.tmp")));
    }

    @Test
    void doesNotOverwriteAnotherWritersTemporaryFile(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("payload.json");
        Path otherWriter = tmp.resolve("payload.json.tmp");
        Files.writeString(otherWriter, "another writer's payload");
        AtomicJsonFile.writeUtf8(target, "new payload");
        assertEquals("another writer's payload", Files.readString(otherWriter));
        assertEquals("new payload", Files.readString(target));
    }

    @Test
    void failedReplacementLeavesOriginalAndNoTemporaryFile(@TempDir Path tmp) throws IOException {
        Path target = Files.createDirectory(tmp.resolve("nonempty.json"));
        Files.writeString(target.resolve("original"), "retained");
        assertThrows(IOException.class, () -> AtomicJsonFile.writeUtf8(target, "new payload"));
        assertEquals("retained", Files.readString(target.resolve("original")));
        try (var files = Files.list(tmp)) {
            assertEquals(java.util.List.of(target), files.toList());
        }
    }

    @Test
    void concurrentWritersPublishWholePayloadsAndCleanUp(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("payload.json");
        Set<String> payloads = IntStream.range(0, 8)
            .mapToObj(i -> "{\"writer\":" + i + ",\"data\":\"" + ("value" + i).repeat(2_000) + "\"}")
            .collect(Collectors.toSet());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> writers = new ArrayList<>();
            for (String payload : payloads) {
                writers.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 8; i++) {
                        AtomicJsonFile.writeUtf8(target, payload);
                        assertTrue(payloads.contains(Files.readString(target)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(10, TimeUnit.SECONDS);
            }
        }
        try (var files = Files.list(tmp)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void relativeTargetsUseTheDestinationDirectory(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("relative.json");
        Path relative = Path.of("").toAbsolutePath().relativize(target.toAbsolutePath());
        AtomicJsonFile.writeUtf8(relative, "relative payload");
        assertEquals("relative payload", Files.readString(target));
    }

    @Test
    void usesNormalFileCreationPermissions(@TempDir Path tmp) throws IOException {
        Path reference = tmp.resolve("ordinary.json");
        Path target = tmp.resolve("atomic.json");
        Files.writeString(reference, "ordinary payload");
        AtomicJsonFile.writeUtf8(target, "atomic payload");
        assertEquals("atomic payload", Files.readString(target));
        if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
            assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(target),
                "container reports must retain the normal creation permissions for host readers");
        }
    }
}
