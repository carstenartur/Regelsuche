package de.regelsuche.quality.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** JDK-only source-launcher entrypoint for coverage producer commit/tree receipts. */
public final class NativeCoverageSourceReceipt {
    private NativeCoverageSourceReceipt() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 3 && arguments[0].equals("capture")) {
            capture(Path.of(arguments[1]), Path.of(arguments[2]));
        } else if (arguments.length == 4 && arguments[0].equals("confirm")) {
            confirm(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]));
        } else {
            throw new IllegalArgumentException("Expected capture <root> <temporary-receipt> or confirm <root> <temporary-receipt> <retained-receipt>");
        }
    }

    static void capture(Path root, Path temporary) throws IOException, InterruptedException {
        git(root, "diff", "--quiet", "HEAD");
        verifyInputs(root);
        write(root.resolve(temporary), source(root));
    }

    static void confirm(Path root, Path temporary, Path retained) throws IOException, InterruptedException {
        String captured = Files.readString(root.resolve(temporary), StandardCharsets.UTF_8);
        if (!captured.equals(source(root))) throw new IOException("Coverage source changed since capture or receipt is malformed");
        verifyInputs(root);
        write(root.resolve(retained), captured);
    }

    private static String source(Path root) throws IOException, InterruptedException {
        return hash(git(root, "rev-parse", "--verify", "HEAD")) + "\n"
            + hash(git(root, "rev-parse", "--verify", "HEAD^{tree}")) + "\n";
    }

    private static void write(Path target, String source) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
    }

    static void verifyInputs(Path root) throws IOException, InterruptedException {
        git(root, "diff", "--quiet", "HEAD", "--", "*.java", "config");
        if (!git(root, "ls-files", "--others", "--", ":(glob)*/src/main/java/**").isEmpty())
            throw new IOException("Untracked Java inputs are absent from measured source");
    }
    static String hash(String value) throws IOException {
        if (!value.matches("[0-9a-f]{40}")) throw new IOException("Invalid source commit SHA");
        return value;
    }
    static String git(Path root, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Timed out resolving coverage source");
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IOException(arguments[0].equals("diff")
                ? "Tracked Java/config inputs differ from measured source" : "Cannot resolve coverage source: " + text);
            return text;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
