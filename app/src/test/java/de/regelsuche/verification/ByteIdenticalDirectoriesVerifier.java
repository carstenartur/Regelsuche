package de.regelsuche.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ByteIdenticalDirectoriesVerifier {

    record ChangedFile(String name, String leftSha256, String rightSha256) {
    }

    record Comparison(
        int comparedFiles,
        List<String> missing,
        List<String> extra,
        List<ChangedFile> changed
    ) {
        Comparison {
            missing = List.copyOf(missing);
            extra = List.copyOf(extra);
            changed = List.copyOf(changed);
        }

        boolean identical() {
            return missing.isEmpty() && extra.isEmpty() && changed.isEmpty();
        }

        String describe(Path leftRoot, Path rightRoot, String pattern) {
            if (identical()) {
                return "byte-identical=" + comparedFiles + " files; left="
                    + leftRoot + "; right=" + rightRoot + "; pattern=" + pattern;
            }
            List<String> details = new ArrayList<>();
            if (!missing.isEmpty()) {
                details.add("missing=" + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                details.add("extra=" + String.join(", ", extra));
            }
            if (!changed.isEmpty()) {
                details.add("changed=" + changed.stream()
                    .map(file -> file.name() + " (" + file.leftSha256()
                        + " != " + file.rightSha256() + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(""));
            }
            return "directory evidence differs: " + String.join("; ", details);
        }
    }

    private ByteIdenticalDirectoriesVerifier() {
    }

    static Comparison compare(Path leftRoot, Path rightRoot, String pattern)
            throws IOException {
        Path normalizedLeft = leftRoot.toAbsolutePath().normalize();
        Path normalizedRight = rightRoot.toAbsolutePath().normalize();
        Map<String, byte[]> left = collect(normalizedLeft, pattern);
        Map<String, byte[]> right = collect(normalizedRight, pattern);

        List<String> missing = left.keySet().stream()
            .filter(name -> !right.containsKey(name))
            .sorted()
            .toList();
        List<String> extra = right.keySet().stream()
            .filter(name -> !left.containsKey(name))
            .sorted()
            .toList();
        List<ChangedFile> changed = left.keySet().stream()
            .filter(right::containsKey)
            .filter(name -> !Arrays.equals(left.get(name), right.get(name)))
            .sorted()
            .map(name -> new ChangedFile(
                name,
                sha256(left.get(name)),
                sha256(right.get(name))))
            .toList();

        return new Comparison(left.size(), missing, extra, changed);
    }

    private static Map<String, byte[]> collect(Path root, String pattern)
            throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("missing directory: " + root);
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("include pattern must not be blank");
        }

        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + pattern);
        Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path relative = root.relativize(path);
                if (matcher.matches(relative)
                        || matcher.matches(relative.getFileName())) {
                    files.put(relative.toString().replace('\\', '/'),
                        Files.readAllBytes(path));
                }
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException(
                "no files matching '" + pattern + "' under " + root);
        }
        return files;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
