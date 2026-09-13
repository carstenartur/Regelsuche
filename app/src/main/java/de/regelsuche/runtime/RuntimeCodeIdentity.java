package de.regelsuche.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Binds delivered class bytes, independent of Git, absolute paths and JAR timestamps. */
public final class RuntimeCodeIdentity {
    public static final String REVISION = "regelsuche.runtime-class-content/v1";
    private RuntimeCodeIdentity() { }

    public static String fingerprint(Collection<Path> codeSources) {
        try {
            Map<String, byte[]> classes = new TreeMap<>();
            for (Path source : codeSources.stream().distinct().toList()) {
                if (Files.isDirectory(source)) {
                    try (var files = Files.walk(source)) {
                        for (Path file : files.filter(path -> path.toString().endsWith(".class")
                                && !path.getFileName().toString().equals("module-info.class")).sorted().toList()) {
                            put(classes, source.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                        }
                    }
                } else {
                    try (var jar = new JarFile(source.toFile())) {
                        for (var entry : jar.stream().filter(item -> item.getName().endsWith(".class")
                                && !item.getName().endsWith("module-info.class")).sorted(java.util.Comparator.comparing(java.util.jar.JarEntry::getName)).toList()) {
                            try (var stream = jar.getInputStream(entry)) { put(classes, entry.getName(), stream.readAllBytes()); }
                        }
                    }
                }
            }
            if (classes.isEmpty()) throw new IllegalArgumentException("runtime has no identifiable classes");
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(REVISION.getBytes(StandardCharsets.UTF_8));
            for (var entry : classes.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(MessageDigest.getInstance("SHA-256").digest(entry.getValue()));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("cannot bind delivered runtime code", exception);
        }
    }

    static Path source(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException | NullPointerException exception) {
            throw new IllegalStateException("runtime class has no identifiable code source: " + type.getName(), exception);
        }
    }

    private static void put(Map<String, byte[]> classes, String name, byte[] bytes) {
        byte[] prior = classes.putIfAbsent(name, bytes);
        if (prior != null && !java.util.Arrays.equals(prior, bytes)) {
            throw new IllegalArgumentException("ambiguous runtime class: " + name);
        }
    }
}
