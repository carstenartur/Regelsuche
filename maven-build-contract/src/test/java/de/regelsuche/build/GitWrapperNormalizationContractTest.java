package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git stores LF even when attributes produce a CRLF Windows checkout. */
class GitWrapperNormalizationContractTest {
    @TempDir Path archive;

    @Test void wrapperScriptsAreNormalizedForAReproducibleCleanCheckout() throws Exception {
        checkWrappers(MavenPomTestSupport.repositoryRoot(),
                Boolean.getBoolean("regelsuche.checkoutContracts"), this::indexedBlob);
    }

    @Test void sourceArchiveWithNoGitNeverRequestsAnIndexedBlob() throws Exception {
        Files.writeString(archive.resolve("gradlew"), "#!/bin/sh\n");
        Files.writeString(archive.resolve("gradlew.bat"), "@echo off\r\n");
        assertDoesNotThrow(() -> checkWrappers(archive, false, script -> {
            throw new AssertionError("Source archives have no Git executable or index");
        }));
    }

    @Test void explicitCheckoutModeChecksTheIndexRatherThanOnlyWorkingTreeBytes() throws Exception {
        Files.writeString(archive.resolve("gradlew"), "#!/bin/sh\n");
        Files.writeString(archive.resolve("gradlew.bat"), "@echo off\r\n");
        assertThrows(AssertionError.class, () -> checkWrappers(archive, true,
                script -> "wrong Git blob\r\n".getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> checkWrappers(archive, true,
                script -> "normalized Git blob\n".getBytes(StandardCharsets.UTF_8)));
    }

    private static void checkWrappers(Path root, boolean checkout, IndexedBlobReader indexed) throws Exception {
        for (String script : new String[] {"gradlew", "gradlew.bat"}) {
            String text = Files.readString(root.resolve(script), StandardCharsets.UTF_8);
            assertNormalized(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8), script);
            if (checkout) assertNormalized(indexed.read(script), script);
        }
    }

    private byte[] indexedBlob(String script) throws Exception {
        var process = new ProcessBuilder("git", "show", ":" + script)
                .directory(MavenPomTestSupport.repositoryRoot().toFile()).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), error);
        return bytes;
    }

    @FunctionalInterface private interface IndexedBlobReader {
        byte[] read(String script) throws Exception;
    }

    @Test void crlfAndLoneCarriageReturnsAreNotValidGitTextBlobs() {
        assertThrows(AssertionError.class, () -> assertNormalized(new byte[] {'a', '\r', '\n'}, "crlf"));
        assertThrows(AssertionError.class, () -> assertNormalized(new byte[] {'a', '\r'}, "cr"));
        assertDoesNotThrow(() -> assertNormalized(new byte[] {'a', '\n'}, "lf"));
    }

    private static void assertNormalized(byte[] bytes, String script) {
        assertTrue(bytes.length > 0, script + " is empty");
        for (byte value : bytes) {
            assertNotEquals((byte) '\r', value,
                    script + " must use LF in Git; .gitattributes supplies checkout line endings");
        }
        assertEquals((byte) '\n', bytes[bytes.length - 1], script + " must end with LF");
    }
}
