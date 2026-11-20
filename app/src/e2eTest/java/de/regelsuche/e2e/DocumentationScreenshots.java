package de.regelsuche.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;

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
        if (!Files.exists(root.resolve(".git"))) throw new IOException("Cannot identify capture checkout");
        var builder = new FileRepositoryBuilder().setMustExist(true).setWorkTree(root.toFile()).findGitDir(root.toFile());
        try (var repository = builder.build(); var git = new Git(repository)) {
            var status = git.status().setIgnoreSubmodules(IgnoreSubmoduleMode.NONE).call();
            if (Stream.concat(status.getUncommittedChanges().stream(), status.getUntracked().stream())
                    .anyMatch(path -> !isCaptureOutput(path))) {
                throw new IOException("Commit repository changes before recording documentation");
            }
            var head = repository.resolve(Constants.HEAD);
            if (head == null) throw new IOException("Cannot identify capture source revision");
            return repository.parseCommit(head).name();
        } catch (GitAPIException exception) {
            throw new IOException("Cannot read capture source revision", exception);
        }
    }

    private static boolean isCaptureOutput(String path) {
        return path.equals("docs/assets/screenshots") || path.startsWith("docs/assets/screenshots/")
            || path.equals("docs/assets/videos") || path.startsWith("docs/assets/videos/");
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
