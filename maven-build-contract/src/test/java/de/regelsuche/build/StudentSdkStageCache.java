package de.regelsuche.build;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Seeds only dependency-cache files into a new SDK staging repository. */
public final class StudentSdkStageCache {
    private static final Path PROJECT_NAMESPACE = Path.of("io", "github", "carstenartur");

    private StudentSdkStageCache() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Expected cache and fresh staging paths");
        System.out.println("studentSdkDependencyCacheFiles=" + seed(Path.of(args[0]), Path.of(args[1])));
    }

    static long seed(Path cache, Path staging) throws IOException {
        Path source = cache.toAbsolutePath().normalize();
        Path target = staging.toAbsolutePath().normalize();
        if (source.startsWith(target) || target.startsWith(source)) {
            throw new IOException("Dependency cache and SDK staging must be disjoint");
        }
        // Refuse reuse, including an empty directory or a symlink to another repository.
        Files.createDirectory(target);
        if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) return 0;
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dependency cache must be a real directory");
        }
        var visitor = new DependencyVisitor(source, target);
        Files.walkFileTree(source, visitor);
        return visitor.copied;
    }

    private static final class DependencyVisitor extends SimpleFileVisitor<Path> {
        private final Path source;
        private final Path target;
        private long copied;

        DependencyVisitor(Path source, Path target) {
            this.source = source;
            this.target = target;
        }

        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return source.relativize(directory).startsWith(PROJECT_NAMESPACE)
                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            Path relative = source.relativize(file);
            String name = file.getFileName().toString();
            if (attributes.isRegularFile() && !relative.startsWith(PROJECT_NAMESPACE) && !transientFile(name)) {
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS);
                copied++;
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private static boolean transientFile(String name) {
        return name.endsWith(".lastUpdated") || name.endsWith(".part") || name.endsWith(".lock")
            || name.equals("maven-metadata-local.xml") || name.equals("resolver-status.properties");
    }
}
