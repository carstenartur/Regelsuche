package de.regelsuche.plugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Aggregate logical payload budget, shared by all cooperating writers of one private cache. */
public record PluginCacheQuota(long bytes, int entries) {
    public static final PluginCacheQuota DEFAULT = new PluginCacheQuota(1L << 30, 65536);
    private static final String SCHEMA = "regelsuche.plugin-cache-quota/v1";
    private static final String POLICY = ".cache-quota";
    private static final String LOCK = ".cache-quota.lock";
    // Bounded stripes avoid both overlapping JVM file locks and an ever-growing root registry.
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];
    static { Arrays.setAll(LOCKS, ignored -> new ReentrantLock()); }

    public PluginCacheQuota {
        if (bytes < 1 || entries < 1) throw new IllegalArgumentException("cache quotas must be positive");
    }

    /** Provision once, or verify the existing policy. Changing an established policy needs operator reconciliation. */
    public void configure(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        PluginInstallationStore.checkDirectories(root.getParent());
        PluginInstallationStore.privateDirectory(root);
        locked(root, () -> {
            if (Files.exists(root.resolve(POLICY), LinkOption.NOFOLLOW_LINKS)) {
                if (!equals(readPolicy(root))) throw new SecurityException("cache quota already provisioned with different limits");
            } else {
                requireSpace(root, 0, 0);
                writePolicy(root);
            }
            return null;
        });
    }

    /** Caller holds the root lock. Old caches adopt a finite default before any new payload. */
    static PluginCacheQuota policy(Path root) throws IOException {
        if (Files.exists(root.resolve(POLICY), LinkOption.NOFOLLOW_LINKS)) return readPolicy(root);
        DEFAULT.requireSpace(root, 0, 0);
        DEFAULT.writePolicy(root);
        return DEFAULT;
    }

    private String canonical() { return SCHEMA + "\n" + bytes + "\n" + entries + "\n"; }

    private void writePolicy(Path root) throws IOException {
        try (var output = FileChannel.open(root.resolve(POLICY),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = ByteBuffer.wrap(canonical().getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        PluginInstallationStore.forceDirectory(root);
    }

    private static PluginCacheQuota readPolicy(Path root) throws IOException {
        Path path = root.resolve(POLICY);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new SecurityException("invalid cache quota file");
        try (var input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.size() > 128) throw new SecurityException("oversized cache quota file");
            var buffer = ByteBuffer.allocate(129);
            while (buffer.hasRemaining() && input.read(buffer) != -1) { }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            String[] parts = text.split("\n", -1);
            if (parts.length != 4 || !parts[0].equals(SCHEMA) || !parts[3].isEmpty()) throw new SecurityException("invalid cache quota file");
            try {
                var quota = new PluginCacheQuota(Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
                if (!text.equals(quota.canonical())) throw new SecurityException("noncanonical cache quota file");
                return quota;
            } catch (IllegalArgumentException malformed) {
                throw new SecurityException("invalid cache quota limits", malformed);
            }
        }
    }

    /** No cached counter: interrupted stages and retained generations are charged on every admission. */
    void requireSpace(Path root, long additionalBytes, int additionalEntries) throws IOException {
        if (additionalBytes < 0 || additionalEntries < 0 || additionalBytes > bytes || additionalEntries > entries)
            throw new SecurityException("aggregate plugin cache quota exceeded");
        long usedBytes = 0;
        int usedEntries = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(root) || path.equals(root.resolve(POLICY)) || path.equals(root.resolve(LOCK))) continue;
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() && !attributes.isRegularFile()) throw new SecurityException("non-regular cache entry");
                if (path.equals(root.resolve("generations")) && attributes.isDirectory()) continue; // fixed container
                if (usedEntries >= entries - additionalEntries) throw new SecurityException("aggregate plugin cache entry quota exceeded");
                usedEntries++;
                if (attributes.isRegularFile()) {
                    long size = attributes.size();
                    if (size > bytes - additionalBytes - usedBytes) throw new SecurityException("aggregate plugin cache byte quota exceeded");
                    usedBytes += size;
                }
            }
        }
    }

    @FunctionalInterface interface IOAction<T> { T run() throws IOException; }

    static <T> T locked(Path root, IOAction<T> action) throws IOException {
        ReentrantLock local = LOCKS[(root.hashCode() & Integer.MAX_VALUE) % LOCKS.length];
        try { local.lockInterruptibly(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("interrupted awaiting plugin cache admission", interrupted);
        }
        try {
            PluginInstallationStore.checkDirectories(root);
            Path lock = root.resolve(LOCK);
            if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("invalid cache quota lock file");
            try (var channel = FileChannel.open(lock,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                 var held = channel.lock()) {
                if (!held.isValid() || channel.size() != 0) throw new SecurityException("invalid cache quota lock");
                return action.run();
            }
        } finally { local.unlock(); }
    }
}
