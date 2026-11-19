package de.regelsuche.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Opt-in documentation output from browser flows that already assert their real results. */
final class DocumentationScreenshots {
    private DocumentationScreenshots() { }

    static void capture(Page page, Locator subject, String fileName, String scenario,
            String inputDescription) throws IOException {
        if (!enabled()) return;
        if (!fileName.matches("[a-z0-9-]+\\.png")) {
            throw new IllegalArgumentException("Use a simple PNG filename");
        }
        Path root = repositoryRoot();
        String revision = captureSourceRevision(root);
        Path directory = root.resolve("docs/assets/screenshots");
        Files.createDirectories(directory);
        page.evaluate("() => document.fonts.ready");
        subject.scrollIntoViewIfNeeded();
        byte[] png = subject.screenshot(new Locator.ScreenshotOptions().setType(ScreenshotType.PNG));
        Files.write(directory.resolve(fileName), png);

        URI uri = URI.create(page.url());
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schema", "regelsuche.documentation-screenshot/v1");
        metadata.put("file", fileName);
        metadata.put("capturedAt", Instant.now().toString());
        metadata.put("sourceRevision", revision);
        metadata.put("scenario", scenario);
        metadata.put("input", inputDescription);
        metadata.put("route", uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
            + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
        metadata.put("viewport", page.evaluate("() => ({width: innerWidth, height: innerHeight})"));
        metadata.put("sha256", sha256(png));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
            directory.resolve(fileName.replace(".png", ".json")).toFile(), metadata);
    }

    static String sourceRevisionOr(String fallback) throws IOException {
        return enabled() ? captureSourceRevision(repositoryRoot()) : fallback;
    }

    static String captureSourceRevision(Path root) throws IOException {
        String changes = git(root, "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none",
            "--", ".", ":(top,exclude)docs/assets/screenshots", ":(top,exclude)docs/assets/videos");
        if (!changes.isBlank()) {
            throw new IOException("Commit repository changes before recording documentation");
        }
        String revision = git(root, "rev-parse", "HEAD").trim();
        if (!revision.matches("[0-9a-f]{40}")) throw new IOException("Cannot identify capture source revision");
        return revision;
    }

    private static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("regelsuche.recordDocs", "false"));
    }

    private static Path repositoryRoot() throws IOException {
        Path path = Path.of(System.getProperty("regelsuche.repositoryRoot", ".")).toAbsolutePath().normalize();
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(current.resolve("README.md"))) return current;
        }
        throw new IOException("Cannot locate the Regelsuche checkout from " + path);
    }

    private static String git(Path root, String... arguments) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) throw new IOException("Cannot read capture source revision: " + output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading capture source revision", exception);
        }
        return output;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
