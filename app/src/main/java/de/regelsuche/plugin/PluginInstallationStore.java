package de.regelsuche.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Private immutable package cache; it never decides which generation is active. */
final class PluginInstallationStore {
    private final Path root;
    private final Path generations;
    private final PluginDistributionClient.Limits limits;

    PluginInstallationStore(Path directory, PluginDistributionClient.Limits limits) throws IOException {
        this.root = directory.toAbsolutePath().normalize();
        this.limits = limits;
        checkDirectories(root.getParent());
        privateDirectory(root);
        this.generations = root.resolve("generations");
        privateDirectory(generations);
    }

    Work work() throws IOException {
        checkDirectories(root);
        return new Work(Files.createTempDirectory(root, "stage-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))));
    }

    Snapshot load(String hash) throws IOException {
        PluginSignatureManifest.requireSha256(hash, "installationHash");
        Path directory = generations.resolve(hash.substring(7));
        checkDirectories(directory);
        final PluginInstallationEvidence evidence;
        byte[] installationBytes = read(directory.resolve("installation.json"),
            Math.min(limits.metadataBytes(), limits.totalBytes()));
        try {
            evidence = PluginInstallationEvidence.read(installationBytes);
        } catch (IllegalArgumentException malformed) {
            throw new SecurityException("invalid installation evidence", malformed);
        }
        if (!hash.equals(evidence.contentHash()) || evidence.artifacts().size() > limits.maximumArtifacts()
                || evidence.files().size() > 9L + 4L * limits.maximumArtifacts()) {
            throw new SecurityException("installation generation identity or size mismatch");
        }
        if (!Arrays.equals(installationBytes, evidence.toCanonicalJson().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("retained installation evidence is not canonical");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        long remaining = limits.totalBytes() - installationBytes.length;
        for (var entry : evidence.files().entrySet()) {
            Path path = directory.resolve(PluginInstallationEvidence.requirePath(entry.getKey()));
            checkDirectories(path.getParent());
            long maximum = artifactPath(evidence, entry.getKey()) ? limits.artifactBytes() : limits.metadataBytes();
            byte[] bytes = read(path, Math.min(maximum, remaining));
            remaining -= bytes.length;
            if (!entry.getValue().equals(PluginArtifactVerifier.sha256(bytes))) {
                throw new SecurityException("retained installation file hash mismatch: " + entry.getKey());
            }
            files.put(entry.getKey(), bytes);
        }
        return new Snapshot(evidence, files);
    }

    void persist(PluginInstallationEvidence evidence, Map<String, byte[]> files) throws IOException {
        try (Work work = work()) {
            for (var entry : files.entrySet()) {
                write(work.directory(), entry.getKey(), entry.getValue());
            }
            write(work.directory(), "installation.json", evidence.toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (var paths = Files.walk(work.directory())) {
                for (Path path : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                    forceDirectory(path);
                }
            }
            checkDirectories(generations);
            Path target = generations.resolve(evidence.contentHash().substring(7));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireExisting(evidence);
                forceDirectory(generations);
                forceDirectory(root);
                return;
            }
            try {
                Files.move(work.directory(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException concurrent) {
                requireExisting(evidence);
            }
            forceDirectory(generations);
            forceDirectory(root);
        }
    }

    private void requireExisting(PluginInstallationEvidence evidence) throws IOException {
        if (!load(evidence.contentHash()).evidence().equals(evidence)) {
            throw new SecurityException("conflicting immutable installation generation");
        }
    }

    static Map<String, String> hashes(Map<String, byte[]> files) {
        Map<String, String> hashes = new TreeMap<>();
        files.forEach((path, bytes) -> hashes.put(path, PluginArtifactVerifier.sha256(bytes)));
        return hashes;
    }

    static void write(Path directory, String relative, byte[] bytes) throws IOException {
        Path target = directory.resolve(PluginInstallationEvidence.requirePath(relative));
        if (!Files.exists(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        checkDirectories(target.getParent());
        try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
            output.force(true);
        }
    }

    private static byte[] read(Path path, long maximum) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("installation file is absent or not a regular file: " + path.getFileName());
        }
        if (maximum < 0 || Files.size(path) > maximum) {
            throw new SecurityException("retained installation exceeds byte budget");
        }
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > maximum - bytes.size()) {
                    throw new SecurityException("retained installation exceeds byte budget");
                }
                bytes.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return bytes.toByteArray();
        }
    }

    private static boolean artifactPath(PluginInstallationEvidence evidence, String path) {
        return evidence.artifacts().stream().anyMatch(artifact -> artifact.path().equals(path));
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            checkDirectories(directory);
            if (!Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new SecurityException("installation directory must have owner-only permissions: " + directory);
            }
        } else {
            Files.createDirectory(directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
    }

    private static void checkDirectories(Path path) throws IOException {
        if (path == null) {
            throw new SecurityException("installation directory must have an existing parent");
        }
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("installation path contains a missing or non-directory component");
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    record Snapshot(PluginInstallationEvidence evidence, Map<String, byte[]> files) { }

    record Work(Path directory) implements AutoCloseable {
        @Override
        public void close() {
            // Cleanup must never turn a completed authority commit into an apparent failure.
            try {
                if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    try (var paths = Files.walk(directory)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Inactive private staging can be removed by the operator after reconciliation.
            }
        }
    }
}
