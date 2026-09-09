package de.regelsuche.sdk.discovery;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Host-observed bytes of the provider code source. This is provenance, not a
 * signature, sandbox, dependency-closure attestation or mathematical authority.
 * Directory digests include sorted relative names and bytes, never local paths.
 */
public record ProviderProvenance(
    String implementationClass, String artifactKind, String artifactSha256
) {
    public ProviderProvenance {
        if (implementationClass == null || implementationClass.isBlank()) {
            throw new IllegalArgumentException("implementationClass is required");
        }
        if (!"JAR".equals(artifactKind) && !"DIRECTORY".equals(artifactKind)) {
            throw new IllegalArgumentException("unsupported provider artifact kind");
        }
        if (artifactSha256 == null || !artifactSha256.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("provider artifact SHA-256 is required");
        }
    }

    static ProviderProvenance capture(Class<?> implementation) {
        try {
            var source = implementation.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) {
                throw new IllegalArgumentException("provider needs a local JAR or classes directory");
            }
            Path path = Path.of(source.getLocation().toURI());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String kind;
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                kind = "JAR";
                try (var in = Files.newInputStream(path);
                     var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                    in.transferTo(out);
                }
            } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                kind = "DIRECTORY";
                try (var entries = Files.walk(path);
                     var out = new DataOutputStream(new DigestOutputStream(
                         OutputStream.nullOutputStream(), digest))) {
                    var files = entries.filter(entry -> !entry.equals(path))
                        .sorted(java.util.Comparator.comparing(entry -> relative(path, entry))).toList();
                    for (Path file : files) {
                        if (Files.isSymbolicLink(file)) {
                            throw new IOException("symbolic links are not provider artifacts");
                        }
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("non-regular provider artifact");
                        }
                        byte[] name = relative(path, file).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.writeInt(name.length);
                        out.write(name);
                        out.writeLong(Files.size(file));
                        try (var in = Files.newInputStream(file)) { in.transferTo(out); }
                    }
                }
            } else {
                throw new IOException("provider code source is unavailable");
            }
            return new ProviderProvenance(implementation.getName(), kind,
                "sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (java.net.URISyntaxException | java.security.NoSuchAlgorithmException | IOException ex) {
            throw new IllegalStateException("cannot fingerprint provider " + implementation.getName(), ex);
        }
    }

    private static String relative(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }

    Map<String, String> properties(String id, String version, String source) {
        var properties = new TreeMap<String, String>();
        properties.put("sdk.provider.id", id);
        properties.put("sdk.provider.version", version);
        properties.put("sdk.provider.apiVersion", DiscoveryApi.VERSION);
        properties.put("sdk.provider.sourceReference", source);
        properties.put("sdk.provider.implementationClass", implementationClass);
        properties.put("sdk.provider.artifactKind", artifactKind);
        properties.put("sdk.provider.artifactSha256", artifactSha256);
        properties.put("sdk.provider.authority", "NOT_EVALUATED");
        return Map.copyOf(properties);
    }
}
