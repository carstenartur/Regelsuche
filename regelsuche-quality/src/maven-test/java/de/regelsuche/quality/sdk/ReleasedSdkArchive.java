package de.regelsuche.quality.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

final class ReleasedSdkArchive {
    static final String VERSION = "0.5.2";
    static final String PREFIX = "regelsuche-sdk-" + VERSION;
    static final String SHA256 = "0746b8c6088539c6db3d228131952b30e8066ac8569215b6a508d08df601d9cb";
    static final int CHECKSUM_COUNT = 4034;
    static final long MAX_DOWNLOAD = 64L << 20;
    private static final long MAX_EXPANDED = 256L << 20;
    private static final Pattern CHECKSUM = Pattern.compile("([0-9a-f]{64})  (.+)");

    private ReleasedSdkArchive() {}

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    static String digest(Path file) throws IOException {
        noSymlinks(file);
        try (var in = Files.newInputStream(file)) { return digest(in, Long.MAX_VALUE); }
    }

    private static String digest(InputStream input, long limit) throws IOException {
        final MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        byte[] bytes = new byte[64 * 1024];
        long total = 0;
        for (int n; (n = input.read(bytes)) >= 0;) {
            total += n;
            require(total <= limit, "expanded ZIP member exceeds declared size");
            hash.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    static void noSymlinks(Path path) throws IOException {
        for (Path item = path.toAbsolutePath(); item != null; item = item.getParent()) {
            require(!Files.isSymbolicLink(item), "symbolic link in qualified path: " + item);
        }
    }

    static Path relative(String name) throws IOException {
        require(!name.isEmpty() && !name.contains("\\") && !name.contains(":"), "unsafe member " + name);
        Path path = Path.of(name);
        require(!path.isAbsolute() && !path.normalize().startsWith("..")
            && path.normalize().toString().replace('\\', '/').equals(name), "unsafe member " + name);
        return path;
    }

    static Map<String, Object> extract(Path archive, Path destination, String sha, int expectedCount, String prefix) throws IOException {
        noSymlinks(archive);
        noSymlinks(destination);
        require(Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) && Files.size(archive) <= MAX_DOWNLOAD
            && digest(archive).equals(sha), "release ZIP digest or size mismatch");
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "extraction requires a fresh directory");
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            Map<String, ZipArchiveEntry> files = new TreeMap<>();
            Set<Path> names = new HashSet<>();
            Set<Path> filePaths = new HashSet<>();
            long expanded = 0;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                Path path = relative(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
                int type = entry.getUnixMode() & 0170000;
                require(type == 0 || type == (entry.isDirectory() ? 0040000 : 0100000), "unsupported ZIP entry type: " + name);
                require(path.getNameCount() >= 2 && path.getName(0).toString().equals(prefix) && names.add(path),
                    "duplicate or outside release prefix: " + name);
                require(entry.getSize() >= 0 && entry.getSize() <= MAX_EXPANDED - expanded && names.size() <= 5000,
                    "release expansion limit exceeded");
                expanded += entry.getSize();
                if (!entry.isDirectory()) {
                    filePaths.add(path);
                    files.put(path.subpath(1, path.getNameCount()).toString().replace('\\', '/'), entry);
                }
            }
            for (Path member : names) {
                for (Path ancestor = member.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                    require(!filePaths.contains(ancestor), "file used as ZIP directory: " + ancestor);
                }
            }
            var manifest = files.get("SHA256SUMS.txt");
            require(manifest != null && manifest.getSize() <= 4L << 20, "missing or excessive SDK checksum manifest");
            Map<String, String> checksums = new HashMap<>();
            try (var stream = zip.getInputStream(manifest);
                    var lines = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()))) {
                for (String line; (line = lines.readLine()) != null;) {
                    var match = CHECKSUM.matcher(line);
                    require(match.matches(), "malformed SDK checksum");
                    relative(match.group(2));
                    require(checksums.putIfAbsent(match.group(2), match.group(1)) == null, "duplicate SDK checksum");
                }
            }
            Set<String> expectedFiles = new HashSet<>(files.keySet());
            expectedFiles.remove("SHA256SUMS.txt");
            require(checksums.size() == expectedCount && checksums.keySet().equals(expectedFiles),
                "incomplete internal SDK checksum inventory");
            for (var checksum : checksums.entrySet()) {
                var entry = files.get(checksum.getKey());
                try (var in = zip.getInputStream(entry)) {
                    require(digest(in, entry.getSize()).equals(checksum.getValue()), "internal checksum mismatch: " + checksum.getKey());
                }
            }
            Files.createDirectory(destination);
            for (Path member : names.stream().sorted().toList()) {
                Path child = destination.resolve(member);
                String relative = member.subpath(1, member.getNameCount()).toString().replace('\\', '/');
                ZipArchiveEntry entry = files.get(relative);
                if (entry == null) Files.createDirectories(child);
                else {
                    Files.createDirectories(child.getParent());
                    try (var in = zip.getInputStream(entry)) { Files.copy(in, child); }
                }
            }
            return Map.of("sha256", sha, "verifiedInternalChecksums", checksums.size(),
                "archiveBytes", Files.size(archive), "prefix", prefix);
        }
    }
}
