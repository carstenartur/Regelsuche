package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeCoverageSourceReceiptTest {
    private static final String SOURCE = "module-a/src/main/java/p/A.java";
    private static final String POLICY = "config/quality/coverage-policy.json";
    @TempDir Path root;
    private String expected;

    @BeforeEach void prepareRepository() throws Exception {
        write(SOURCE, "package p; class A {}\n");
        write(POLICY, "{}\n");
        write("README.md", "Fixture\n");
        write(".gitignore", "**/build/\n");
        git("init", "-q");
        git("add", ".");
        commit();
        expected = git("rev-parse", "HEAD") + "\n" + git("rev-parse", "HEAD^{tree}") + "\n";
    }

    @Test void captureWritesExactlyTheCurrentCommitAndTree() throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        assertEquals(expected, Files.readString(temporary()));
    }

    @ParameterizedTest @ValueSource(strings = {SOURCE, POLICY, "README.md"})
    void captureRequiresEveryTrackedFileToBeClean(String path) throws Exception {
        write(path, "changed\n");
        assertThrows(IOException.class, () -> NativeCoverageSourceReceipt.capture(root, temporary()));
        assertFalse(Files.exists(temporary()));
    }

    @ParameterizedTest @ValueSource(strings = {SOURCE, POLICY})
    void confirmRejectsChangedMeasuredInputs(String path) throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        write(path, "changed\n");
        rejected();
    }

    @Test void stagedSourceChangesCannotPassEitherGuard() throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        write(SOURCE, "package p; class Changed {}\n");
        git("add", SOURCE);
        assertThrows(IOException.class, () -> NativeCoverageSourceReceipt.capture(root, temporary()));
        assertEquals(expected, Files.readString(temporary()));
        rejected();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void untrackedCanonicalJavaIsRejectedEvenWhenIgnored(boolean ignored) throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        String path = "module-a/src/main/java/p/B.java";
        write(path, "package p; class B {}\n");
        if (ignored) write(".git/info/exclude", path + "\n");
        assertThrows(IOException.class, () -> NativeCoverageSourceReceipt.capture(root, temporary()));
        rejected();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aNewCommitCannotConfirmAnEarlierCaptureEvenWithTheSameTree(boolean changedTree) throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        if (changedTree) {
            write(SOURCE, "package p; class Changed {}\n");
            git("add", SOURCE);
        }
        commit();
        assertNotEquals(expected, git("rev-parse", "HEAD") + "\n" + git("rev-parse", "HEAD^{tree}") + "\n");
        rejected();
    }

    @ParameterizedTest @ValueSource(strings = {"missing-tree", "extra-line", "missing-newline", "crlf", "wrong-tree", "invalid-hash"})
    void confirmRequiresTheExactTwoLineReceipt(String shape) throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        String malformed = switch (shape) {
            case "missing-tree" -> expected.substring(0, 41);
            case "extra-line" -> expected + "extra\n";
            case "missing-newline" -> expected.stripTrailing();
            case "crlf" -> expected.replace("\n", "\r\n");
            case "wrong-tree" -> expected.substring(0, 41) + "0".repeat(40) + "\n";
            default -> "x" + expected.substring(1);
        };
        Files.writeString(temporary(), malformed);
        rejected();
    }

    @Test void generatedBuildCopiesDoNotBecomeCanonicalJavaInputs() throws Exception {
        write("build/reproduction/module-a/src/main/java/p/A.java", "class EvidenceCopy {}\n");
        write("module-a/build/generated/src/main/java/p/A.java", "class GeneratedCopy {}\n");
        NativeCoverageSourceReceipt.capture(root, temporary());
        NativeCoverageSourceReceipt.confirm(root, temporary(), retained());
        assertEquals(expected, Files.readString(retained()));
    }

    @Test void aRejectedConfirmationDoesNotOverwriteAnExistingRetainedFile() throws Exception {
        NativeCoverageSourceReceipt.capture(root, temporary());
        write("build/retained/source.txt", "previous evidence\n");
        write(POLICY, "changed\n");
        assertThrows(IOException.class, () -> NativeCoverageSourceReceipt.confirm(root, temporary(), retained()));
        assertEquals("previous evidence\n", Files.readString(retained()));
    }

    @Test void invalidCommandsAndArityFailBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> NativeCoverageSourceReceipt.main(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> NativeCoverageSourceReceipt.main(new String[]{"capture", root.toString()}));
        assertThrows(IllegalArgumentException.class, () -> NativeCoverageSourceReceipt.main(new String[]{"confirm", root.toString(), temporary().toString()}));
        assertThrows(IllegalArgumentException.class, () -> NativeCoverageSourceReceipt.main(new String[]{"other", root.toString(), temporary().toString()}));
        assertFalse(Files.exists(temporary()));
    }

    @Test void sourceLauncherCapturesAndConfirmsWithoutAdditionalClasspath() throws Exception {
        sourceLauncher("capture", "build/temporary-source.txt");
        assertEquals(expected, Files.readString(root.resolve("build/temporary-source.txt")));
        write("build/reproduction/module-a/src/main/java/p/A.java", "class EvidenceCopy {}\n");
        sourceLauncher("confirm", "build/temporary-source.txt", "build/retained/source.txt");
        assertEquals(expected, Files.readString(root.resolve("build/retained/source.txt")));
    }

    private Path temporary() { return root.resolve("build/temporary-source.txt"); }
    private Path retained() { return root.resolve("build/retained/source.txt"); }

    private void rejected() {
        assertThrows(IOException.class, () -> NativeCoverageSourceReceipt.confirm(root, temporary(), retained()));
        assertFalse(Files.exists(retained()));
    }

    private void sourceLauncher(String command, String... receipts) throws Exception {
        Path source = Path.of(System.getProperty("regelsuche.repositoryRoot"))
            .resolve("regelsuche-quality/src/main/java/de/regelsuche/quality/coverage/NativeCoverageSourceReceipt.java");
        List<String> arguments = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            source.toString(), command, root.toString()));
        arguments.addAll(List.of(receipts));
        execute(arguments);
    }

    private void write(String path, String content) throws Exception {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void commit() throws Exception {
        git("-c", "user.name=Coverage test", "-c", "user.email=coverage@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "fixture");
    }

    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        return execute(command).strip();
    }

    private String execute(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fixture command timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
