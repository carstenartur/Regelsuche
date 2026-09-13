package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedReleaseEvidenceFilesTest {
    @TempDir Path temporary;

    @Test
    void openedRootAndFirstBytesStayOwnedAfterPathReplacement() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.writeString(root.resolve("file.json"), "original");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("file.json"), "outside");
        try (var files = new OwnedReleaseEvidenceFiles(root)) {
            Path moved = Files.move(root, temporary.resolve("moved"));
            Files.createSymbolicLink(root, outside);
            assertEquals("original", new String(files.readBytes(Path.of("file.json")), java.nio.charset.StandardCharsets.UTF_8));
            Files.writeString(moved.resolve("file.json"), "changed");
            byte[] supplied = files.readBytes(Path.of("file.json"));
            supplied[0] = 0;
            assertEquals("original", new String(files.readBytes(Path.of("file.json")), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsSymbolicRootAncestorAndMember() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.writeString(root.resolve("file.json"), "{}");
        Files.createSymbolicLink(root.resolve("alias.json"), root.resolve("file.json"));
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), temporary);
        assertThrows(Exception.class, () -> new OwnedReleaseEvidenceFiles(alias.resolve("root")));
        try (var files = new OwnedReleaseEvidenceFiles(root)) {
            assertThrows(Exception.class, () -> files.readBytes(Path.of("alias.json")));
            assertThrows(IllegalArgumentException.class, () -> files.readBytes(Path.of("../outside")));
            assertThrows(IllegalArgumentException.class, () -> files.readBytes(Path.of(".")));
            assertThrows(IllegalArgumentException.class, () -> files.readBytes(root.resolve("file.json")));
        }
    }

    @Test
    void foreignFilesystemRootsAndMembersCannotSelectHostFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("host-root"));
        Files.writeString(root.resolve("file.json"), "host bytes");
        try (var zip = FileSystems.newFileSystem(temporary.resolve("empty.zip"), Map.of("create", "true"));
                var files = new OwnedReleaseEvidenceFiles(root)) {
            Path foreignRoot = zip.getPath(root.toString());
            Path foreignMember = zip.getPath("file.json");
            assertFalse(Files.exists(foreignRoot));
            assertFalse(Files.exists(foreignMember));
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> {
                    try (var ignored = new OwnedReleaseEvidenceFiles(foreignRoot)) { }
                }),
                () -> assertThrows(IllegalArgumentException.class, () -> files.readBytes(foreignMember)),
                () -> assertThrows(IllegalArgumentException.class, () -> files.present(foreignMember)));
            assertEquals("host bytes", new String(files.readBytes(Path.of("file.json")), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
