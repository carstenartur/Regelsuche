package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationScreenshotsTest {
    private static final String VALIDATOR = "regelsuche-validation/src/main/java/Validator.java";
    private static final String SCREENSHOT = "docs/assets/screenshots/result.png";

    @TempDir Path repository;
    private String revision;

    @BeforeEach
    void initializeRepository() throws Exception {
        try (var git = Git.init().setDirectory(repository.toFile()).call()) {
            write(VALIDATOR, "class Validator {}\n");
            write("build.gradle", "plugins {}\n");
            write(SCREENSHOT, "previous screenshot");
            git.add().addFilepattern(".").call();
            revision = git.commit().setMessage("Capture source fixture")
                .setAuthor("Documentation Test", "docs-test@example.invalid")
                .setCommitter("Documentation Test", "docs-test@example.invalid").setSign(false).call().name();
        }
    }

    @Test
    void rejectsAnUncommittedValidatorChange() throws IOException {
        write(VALIDATOR, "class Validator { boolean valid; }\n");
        assertThrows(IOException.class, () -> DocumentationScreenshots.captureSourceRevision(repository));
    }

    @Test
    void rejectsAStagedBuildInputChange() throws Exception {
        write("build.gradle", "plugins { id 'java' }\n");
        try (var git = Git.open(repository.toFile())) {
            git.add().addFilepattern("build.gradle").call();
        }
        assertThrows(IOException.class, () -> DocumentationScreenshots.captureSourceRevision(repository));
    }

    @Test
    void acceptsModifiedScreenshotsAndNewCaptureOutputs() throws IOException {
        write(SCREENSHOT, "new screenshot");
        write("docs/assets/screenshots/result.json", "{}\n");
        write("docs/assets/videos/run.webm", "new recording");
        assertEquals(revision, DocumentationScreenshots.captureSourceRevision(repository));
    }

    @Test
    void rejectsUntrackedInputsBesideTheCaptureDirectories() throws IOException {
        write("docs/assets/screenshots-extra/source.svg", "untracked source");
        assertThrows(IOException.class, () -> DocumentationScreenshots.captureSourceRevision(repository));
    }

    @Test
    void rejectsDeletedInputs() throws IOException {
        Files.delete(repository.resolve(VALIDATOR));
        assertThrows(IOException.class, () -> DocumentationScreenshots.captureSourceRevision(repository));
    }

    private void write(String fileName, String content) throws IOException {
        Path target = repository.resolve(fileName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
