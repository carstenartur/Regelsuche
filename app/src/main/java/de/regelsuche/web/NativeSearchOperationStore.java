package de.regelsuche.web;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Retains at most 256 bounded operation records. One writer lease protects live work across host processes. */
final class NativeSearchOperationStore {
    private static final String SCHEMA = "regelsuche.native-search-operation/v1";
    private static final String ID = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path directory;

    NativeSearchOperationStore(Path runs) {
        Path root = runs.toAbsolutePath().normalize();
        directory = root.resolveSibling(root.getFileName()+"-operations");
    }

    FileLock tryLease() throws IOException {
        checkDirectory();
        Files.createDirectories(directory);
        var channel = FileChannel.open(directory.resolve(".active"), StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException busy) {
            // Another service in this JVM owns the same repository.
        } catch (IOException failure) { channel.close(); throw failure; }
        channel.close(); return null;
    }

    static void release(FileLock lock) throws IOException {
        if (lock != null) lock.channel().close();
    }

    List<String> ids() throws IOException {
        checkDirectory();
        if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files=Files.list(directory)) {
            var names = files.map(p->p.getFileName().toString()).filter(name->name.endsWith(".json")).limit(257).sorted().toList();
            if (names.size()>256) throw new IOException("operation record limit exceeded");
            return names.stream().map(name->name.substring(0,name.length()-5)).toList();
        }
    }

    void save(NativeSearchOperation value) throws IOException {
        checkDirectory();
        AtomicJsonFile.writeUtf8(file(value.operationId()),encode(value));
    }

    static String encode(NativeSearchOperation value) throws IOException {
        var envelope = JSON.createObjectNode().put("schema",SCHEMA);
        envelope.set("operation",JSON.valueToTree(value));
        envelope.put("contentHash", hash(JSON.writeValueAsBytes(envelope)));
        return JSON.writeValueAsString(envelope);
    }

    Optional<NativeSearchOperation> find(String id) throws IOException {
        checkDirectory();
        Path path=file(id);
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid retained operation");
        try (var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            var bytes=input.readNBytes(32769);
            if (bytes.length>32768) throw new IOException("oversized operation record");
            if (!(JSON.readTree(bytes) instanceof com.fasterxml.jackson.databind.node.ObjectNode tree) || tree.size() != 3
                    || !tree.path("contentHash").isTextual() || !tree.path("operation").isObject()) {
                throw new IOException("invalid retained operation envelope");
            }
            String expected=tree.path("contentHash").textValue();
            tree.remove("contentHash");
            if (!SCHEMA.equals(tree.path("schema").asText()) || !hash(JSON.writeValueAsBytes(tree)).equals(expected)) {
                throw new IOException("operation record identity mismatch");
            }
            var result=JSON.treeToValue(tree.required("operation"),NativeSearchOperation.class);
            if (!id.equals(result.operationId())) throw new IOException("wrong retained operation");
            return Optional.of(result);
        }
    }

    private void checkDirectory() throws IOException {
        for (Path p=directory; p!=null; p=p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("symbolic operation repository path");
        }
    }

    private Path file(String id) {
        if (id==null || !id.matches(ID)) throw new IllegalArgumentException("invalid operation identifier");
        return directory.resolve(id+".json");
    }

    private static String hash(byte[] bytes) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
