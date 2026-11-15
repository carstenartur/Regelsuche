package de.regelsuche.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates checkout-relative links from unique source declarations in the strategy index. */
public final class StrategyCodeReferences {
    private static final Path INDEX = Path.of("docs/research/strategies.md");
    private static final String COMMAND =
        "java regelsuche-core/src/test/java/de/regelsuche/docs/StrategyCodeReferences.java";
    private static final Pattern PREFIX = Pattern.compile("<!-- strategy-code:");
    private static final Pattern REFERENCE = Pattern.compile(
        "(?m)^(?:- \\[`[A-Za-z0-9_.-]+`\\]\\([A-Za-z0-9_./-]+#L[1-9][0-9]*\\) )?"
            + "(<!-- strategy-code: ([A-Za-z0-9_./-]+) \\| ([^\\r\\n]+?) -->)$");

    private StrategyCodeReferences() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !(args[0].equals("--write") || args[0].equals("--check"))) {
            throw new IllegalArgumentException("Usage: " + COMMAND + " (--check | --write)");
        }
        refresh(repositoryRoot(Path.of("")), args[0].equals("--write"));
    }

    static Path repositoryRoot(Path from) {
        for (Path path = from.toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("docs/documentation-conventions.md"))) {
                return path;
            }
        }
        throw new IllegalStateException("Regelsuche checkout not found from " + from.toAbsolutePath());
    }

    static void refresh(Path root, boolean write) throws IOException {
        root = root.toRealPath();
        var index = root.resolve(INDEX);
        var original = Files.readString(index);
        var references = REFERENCE.matcher(original);
        var updated = new StringBuilder();
        var stale = new ArrayList<String>();
        int count = 0;
        while (references.find()) {
            count++;
            var path = references.group(2);
            var declaration = references.group(3);
            var source = root.resolve(path).normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source) || !source.toRealPath().startsWith(root)) {
                throw new IllegalStateException(INDEX + ": source file not found within checkout: " + path);
            }
            var lines = Files.readAllLines(source);
            int matches = 0;
            int line = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().equals(declaration)) {
                    matches++;
                    line = i + 1;
                }
            }
            if (matches != 1) {
                throw new IllegalStateException(INDEX + ": " + path + " has " + matches
                    + " matches for declaration \"" + declaration + "\". Update the strategy-code marker.");
            }
            var relative = index.getParent().relativize(source).toString().replace('\\', '/');
            var link = "- [`" + source.getFileName() + "`](" + relative + "#L" + line + ") " + references.group(1);
            if (!link.equals(references.group())) {
                stale.add(path + "#L" + line);
            }
            references.appendReplacement(updated, Matcher.quoteReplacement(link));
        }
        if (count == 0 || count != PREFIX.matcher(original).results().count()) {
            throw new IllegalStateException(INDEX + ": missing or malformed strategy-code marker");
        }
        if (stale.isEmpty()) {
            return;
        }
        if (!write) {
            throw new IllegalStateException(INDEX + ": refresh code links with " + COMMAND + " --write:\n"
                + String.join("\n", stale));
        }
        references.appendTail(updated);
        Files.writeString(index, updated);
    }
}
