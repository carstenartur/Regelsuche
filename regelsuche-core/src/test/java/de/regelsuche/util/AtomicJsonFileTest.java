package de.regelsuche.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
