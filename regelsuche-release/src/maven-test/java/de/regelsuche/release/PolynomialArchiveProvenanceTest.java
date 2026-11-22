package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialArchiveProvenanceTest {
    @Test void rejectsTamperedBytesModesAndUnregisteredImplementation(@TempDir Path directory) throws Exception {
        Path checkout = Files.createDirectory(directory.resolve("checkout"));
        Files.writeString(checkout.resolve(".gitignore"), "build/\n");
        Path script = Files.writeString(checkout.resolve("script.sh"), "#!/bin/sh\n");
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.copy(checkout.resolve(".gitignore"), source.resolve(".gitignore"));
        Path archivedScript = Files.copy(script, source.resolve("script.sh"));
        String revision;
        try (var git = Git.init().setDirectory(checkout.toFile()).call()) {
            git.add().addFilepattern(".").call();
            revision = git.commit().setMessage("source").setAuthor("Test", "test@example.org").call().name();
            assertThrows(IOException.class, () -> PolynomialArchiveProvenanceMain.verify(source));
            QualityArchiveRegistration.write(checkout, source.resolve(QualityArchiveRegistration.RECEIPT));
        }
        assertEquals(revision, PolynomialArchiveProvenanceMain.verify(source).required("revision").asText());
        Files.writeString(archivedScript, "changed\n");
        assertThrows(IOException.class, () -> PolynomialArchiveProvenanceMain.verify(source));
        Files.copy(script, archivedScript, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var permissions = Files.getPosixFilePermissions(archivedScript);
        Files.setPosixFilePermissions(archivedScript, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> PolynomialArchiveProvenanceMain.verify(source));
        Files.setPosixFilePermissions(archivedScript, permissions);
        Path unregistered = Files.writeString(source.resolve("replacement.py"), "unregistered\n");
        assertThrows(IOException.class, () -> PolynomialArchiveProvenanceMain.verify(source));
        Files.delete(unregistered);
        Files.writeString(source.resolve("build/ignored.py"), "diagnostic\n");
        assertEquals(revision, PolynomialArchiveProvenanceMain.verify(source).required("revision").asText());
    }
}
