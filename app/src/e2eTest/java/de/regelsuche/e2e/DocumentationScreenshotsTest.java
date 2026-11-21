package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        git("init", "-q");
        write(VALIDATOR, "class Validator {}\n");
        write("build.gradle", "plugins {}\n");
        write(SCREENSHOT, "previous screenshot");
        git("add", ".");
        git("-c", "user.name=Documentation Test", "-c", "user.email=docs-test@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "-qm", "Capture source fixture");
        revision = git("rev-parse", "HEAD").trim();
    }

    @Test
    void rejectsAnUncommittedValidatorChange() throws IOException {
        write(VALIDATOR, "class Validator { boolean valid; }\n");
        assertThrows(IOException.class, () -> DocumentationScreenshots.captureSourceRevision(repository));
    }

    @Test
    void rejectsAStagedBuildInputChange() throws Exception {
        write("build.gradle", "plugins { id 'java' }\n");
        git("add", "build.gradle");
        assertThrows(IOException.class, () -> DocumentationScreenshots.captureSourceRevision(repository));
    }

    @Test
    void acceptsModifiedScreenshotsAndNewCaptureOutputs() throws IOException {
        write(SCREENSHOT, "new screenshot");
        write("docs/assets/screenshots/result.json", "{}\n");
        write("docs/assets/videos/run.webm", "new recording");
        assertEquals(revision, DocumentationScreenshots.captureSourceRevision(repository));
    }

    private void write(String fileName, String content) throws IOException {
        Path target = repository.resolve(fileName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
