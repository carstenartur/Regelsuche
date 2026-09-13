package de.regelsuche.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCodeIdentityTest {
    @Test void directoryAndDeliveredJarBindTheSameClassBytesWithoutGit(@TempDir Path temporary) throws Exception {
        String name = "de/regelsuche/runtime/RuntimeCodeIdentity.class";
        byte[] bytes;
        try (var source = RuntimeCodeIdentity.class.getResourceAsStream("/" + name)) { bytes = source.readAllBytes(); }
        Path directory = temporary.resolve("relocated-classes");
        Files.createDirectories(directory.resolve(name).getParent());
        Files.write(directory.resolve(name), bytes);
        Path jar = temporary.resolve("release.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            var entry = new JarEntry(name); entry.setTime(1234); output.putNextEntry(entry); output.write(bytes); output.closeEntry();
            output.putNextEntry(new JarEntry("README.txt")); output.write("unrelated packaging metadata".getBytes()); output.closeEntry();
        }
        String identity = RuntimeCodeIdentity.fingerprint(List.of(directory));
        assertEquals(identity, RuntimeCodeIdentity.fingerprint(List.of(jar)));
        assertEquals(identity, RuntimeCodeIdentity.fingerprint(List.of(jar, directory)));
        assertFalse(Files.exists(temporary.resolve(".git")));
        Path foreignPlugin = directory.resolve("org/example/Plugin.class");
        Files.createDirectories(foreignPlugin.getParent());
        Files.write(foreignPlugin, bytes);
        assertNotEquals(identity, RuntimeCodeIdentity.fingerprint(List.of(directory)), "plugin implementation packages must also be bound");
        Files.delete(foreignPlugin);
        bytes[bytes.length - 1] ^= 1;
        Files.write(directory.resolve(name), bytes);
        assertNotEquals(identity, RuntimeCodeIdentity.fingerprint(List.of(directory)));
        assertThrows(IllegalArgumentException.class, () -> RuntimeCodeIdentity.fingerprint(List.of(jar, directory)));
    }
}
