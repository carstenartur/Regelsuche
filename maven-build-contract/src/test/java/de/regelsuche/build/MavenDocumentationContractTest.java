package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDocumentationContractTest {
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern INDENTED_DISPLAY_MATH =
        Pattern.compile("^[ \\t]+\\$\\$");
    private static final Pattern MARKDOWN_TARGET =
        Pattern.compile("]\\(([^)\\n]+)\\)");
    private static final Pattern HTML_TARGET = Pattern.compile(
        "(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> IGNORED_SCHEMES = Set.of(
        "http", "https", "mailto", "data", "javascript"
    );

    @Test
    void repositoryMarkdownSatisfiesTheDocumentationContract()
            throws IOException {
        List<Finding> findings = verifyRepository(repositoryRoot());

        assertTrue(
            findings.isEmpty(),
            () -> "Found documentation problems:\n" + render(findings)
        );
    }

    @Test
    void parserRetainsThePlainCheckoutCharacterization() {
        List<String> lines = List.of(
            "```md", "  $$", "x^2", "  $$", "```", "",
            "  $$", "x^2", "  $$", "~~~text", "  $$", "~~~"
        );
        assertEquals(List.of(7, 9), indentedDisplayMath(lines));
        assertNull(localTarget("https://example.org/docs"));
        assertEquals("guide.md", localTarget("guide.md#section"));

        String fenced = "before\n```md\n[fake](missing.md)\n```\nafter\n";
        String visible = maskFencedCode(fenced);
        assertFalse(visible.contains("missing.md"));
        assertEquals(newlines(fenced, fenced.length()),
            newlines(visible, visible.length()));
    }

    @Test
    void linkChecksAcceptValidTargetsAndRejectMissingOrEscapingOnes(
        @TempDir Path tempDir
    ) throws IOException {
        Path root = tempDir.resolve("repository");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("README.md"), "# Root\n");
        Files.writeString(root.resolve("docs/asset file.png"), "asset");
        Files.writeString(
            root.resolve("docs/valid.md"),
            """
            [root](../README.md "Root")
            [asset](<asset%20file.png>)
            <a href="asset%20file.png">asset</a>
            [external](https://example.org/docs)
            ```md
            [fenced](missing.md)
            ```
            """
        );
        Files.writeString(tempDir.resolve("outside.md"), "outside");
        Files.writeString(
            root.resolve("docs/invalid.md"),
            "[missing](missing.md)\n[escape](../../outside.md)\n"
        );
        Files.writeString(
            root.resolve("docs/README.legacy.md"),
            "[historical](missing.md)\n  $$\n"
        );

        List<Finding> findings = verifyRepository(root);

        assertEquals(3, findings.size(), render(findings));
        assertEquals("indented display math `$$`", findings.get(0).message());
        assertTrue(findings.stream().anyMatch(finding ->
            finding.message().startsWith("missing local target:")));
        assertTrue(findings.stream().anyMatch(finding ->
            finding.message().startsWith("link escapes repository:")));
    }

    private static List<Finding> verifyRepository(Path repositoryRoot)
            throws IOException {
        Path root = repositoryRoot.toRealPath();
        Map<Path, Path> markdown = new LinkedHashMap<>();
        Path docs = root.resolve("docs");
        if (Files.isDirectory(docs)) {
            try (Stream<Path> paths = Files.walk(docs)) {
                paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> putResolved(markdown, path));
            }
        }
        Path readme = root.resolve("README.md");
        if (Files.isRegularFile(readme)) {
            putResolved(markdown, readme);
        }

        List<Finding> findings = new ArrayList<>();
        for (Path file : markdown.values().stream()
                .sorted(Comparator.comparing(Path::toString)).toList()) {
            findings.addAll(verifyMarkdown(file, root));
        }
        return List.copyOf(findings);
    }

    private static void putResolved(Map<Path, Path> markdown, Path path) {
        try {
            markdown.put(path.toRealPath(), path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot resolve " + path, exception);
        }
    }

    private static List<Finding> verifyMarkdown(Path file, Path root)
            throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<Finding> findings = new ArrayList<>();
        for (int line : indentedDisplayMath(text.lines().toList())) {
            findings.add(new Finding(file, line, "indented display math `$$`"));
        }
        if (file.getFileName().toString().equals("README.legacy.md")) {
            return List.copyOf(findings);
        }

        String visible = maskFencedCode(text);
        for (Pattern pattern : List.of(MARKDOWN_TARGET, HTML_TARGET)) {
            Matcher matcher = pattern.matcher(visible);
            while (matcher.find()) {
                String relative = localTarget(matcher.group(1));
                if (relative != null) {
                    checkTarget(file, root, matcher, relative, visible, findings);
                }
            }
        }
        return List.copyOf(findings);
    }

    private static void checkTarget(
        Path file,
        Path root,
        Matcher matcher,
        String relative,
        String visible,
        List<Finding> findings
    ) throws IOException {
        int line = newlines(visible, matcher.start()) + 1;
        Path target = file.getParent().resolve(relative)
            .toAbsolutePath().normalize();
        String raw = matcher.group(1);
        if (!target.startsWith(root)) {
            findings.add(new Finding(file, line,
                "link escapes repository: " + raw));
        } else if (!Files.exists(target)) {
            findings.add(new Finding(file, line,
                "missing local target: " + raw));
        } else if (!target.toRealPath().startsWith(root)) {
            findings.add(new Finding(file, line,
                "link escapes repository: " + raw));
        }
    }

    private static List<Integer> indentedDisplayMath(List<String> lines) {
        String fence = null;
        List<Integer> failures = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            Matcher marker = FENCE.matcher(lines.get(index));
            if (marker.find()) {
                fence = fence == null ? marker.group(1)
                    : marker.group(1).equals(fence) ? null : fence;
            } else if (fence == null
                    && INDENTED_DISPLAY_MATH.matcher(lines.get(index)).find()) {
                failures.add(index + 1);
            }
        }
        return List.copyOf(failures);
    }

    private static String maskFencedCode(String text) {
        String fence = null;
        StringBuilder visible = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int newline = text.indexOf('\n', offset);
            int end = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(offset, end);
            Matcher marker = FENCE.matcher(line);
            if (marker.find()) {
                fence = fence == null ? marker.group(1)
                    : marker.group(1).equals(fence) ? null : fence;
            } else if (fence == null) {
                visible.append(line);
                offset = end;
                continue;
            }
            if (line.endsWith("\n")) {
                visible.append('\n');
            }
            offset = end;
        }
        return visible.toString();
    }

    private static String localTarget(String raw) {
        String target = raw.trim();
        if (target.startsWith("<") && target.contains(">")) {
            target = target.substring(1, target.indexOf('>'));
        } else {
            target = target.split("\\s+", 2)[0];
        }
        if (target.isEmpty() || target.startsWith("#")) {
            return null;
        }
        try {
            URI uri = new URI(target.replace(" ", "%20"));
            String scheme = uri.getScheme();
            if ((scheme != null && IGNORED_SCHEMES.contains(
                    scheme.toLowerCase(Locale.ROOT)))
                    || uri.getAuthority() != null) {
                return null;
            }
            String path = uri.getPath();
            return path == null || path.isEmpty() ? null : path;
        } catch (URISyntaxException exception) {
            String path = target.split("[?#]", 2)[0];
            return path.isEmpty() ? null : path;
        }
    }

    private static String render(List<Finding> findings) {
        return findings.stream().map(Finding::toString)
            .reduce((left, right) -> left + "\n" + right).orElse("none");
    }

    private static int newlines(String text, int end) {
        int count = 0;
        for (int index = 0; index < end; index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(configured,
            "Maven must expose maven.multiModuleProjectDirectory to tests");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private record Finding(Path file, int line, String message) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + message;
        }
    }
}
