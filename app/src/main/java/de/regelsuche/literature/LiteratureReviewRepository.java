package de.regelsuche.literature;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import de.regelsuche.literature.TheoremSearchClient.Hit;
import de.regelsuche.literature.TheoremSearchClient.Result;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Private receipts for explicit literature lookups, separate from mathematical evidence. */
public final class LiteratureReviewRepository {
    public static final String SCHEMA = "regelsuche.literature-search-receipt/v1";

    private static final int MAX_BYTES = 131_072;
    private static final int MAX_SUBJECTS = 256;
    private static final int MAX_RECEIPTS_PER_SUBJECT = 32;
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    private static final Pattern RECEIPT_FILE = Pattern.compile(ID.pattern() + "\\.json");
    private static final String LOCK_FILE = ".lock";
    // Java file locks are JVM-wide; serialize requests here before taking the interprocess lock.
    private static final ReentrantLock LOCAL_LOCK = new ReentrantLock();
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final Path directory;
    private final Clock clock;

    public LiteratureReviewRepository(Path directory) {
        this(directory, Clock.systemUTC());
    }

    LiteratureReviewRepository(Path directory, Clock clock) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        if (this.directory.getParent() == null) throw new IllegalArgumentException("receipt directory needs a parent");
        this.clock = Objects.requireNonNull(clock);
    }

    /** The identity and exact retained evidence to which a lookup was attached. */
    public record Subject(String runId, String candidateId, String artifactHash) {
        public Subject {
            requireHash(runId, "runId");
            requireHash(candidateId, "candidateId");
            requireHash(artifactHash, "artifactHash");
        }
    }

    /** A null result and completion time mean the terminal result was not recorded. */
    public record Receipt(String schema, String id, Subject subject, String startedAt, String query,
                          String endpoint, int resultLimit, Result result, String completedAt) {
        public Receipt {
            if (!SCHEMA.equals(schema) || id == null || !ID.matcher(id).matches() || subject == null
                || !TheoremSearchClient.ENDPOINT.toString().equals(endpoint)
                || resultLimit != TheoremSearchClient.RESULT_LIMIT || (result == null) != (completedAt == null)) {
                throw new IllegalArgumentException("invalid literature search receipt");
            }
            requireTimestamp(startedAt);
            requireQuery(query);
            if (result != null) {
                requireTimestamp(completedAt);
                requireTerminalResult(result);
            }
        }

        public boolean pending() {
            return result == null;
        }

        private Receipt asPending() {
            return pending() ? this : new Receipt(schema, id, subject, startedAt, query, endpoint, resultLimit, null, null);
        }
    }

    /** Persist the query before the caller contacts the provider. */
    public Receipt begin(Subject subject, String exactQuery) throws IOException {
        Objects.requireNonNull(subject);
        requireQuery(exactQuery);
        return withRepository(true, null, () -> {
            Path scope = subjectDirectory(subject);
            if (!isPrivateDirectory(scope)) {
                if (entries(directory).stream().filter(entry -> !entry.getFileName().toString().equals(LOCK_FILE)).count()
                    >= MAX_SUBJECTS) {
                    throw new IOException("literature receipt repository has reached its subject limit");
                }
                createPrivateDirectory(scope);
            }
            if (entries(scope).size() >= MAX_RECEIPTS_PER_SUBJECT) {
                throw new IOException("literature receipt subject has reached its attempt limit");
            }
            var receipt = new Receipt(SCHEMA, UUID.randomUUID().toString(), subject, clock.instant().toString(),
                exactQuery, TheoremSearchClient.ENDPOINT.toString(), TheoremSearchClient.RESULT_LIMIT, null, null);
            Path target = receiptPath(scope, receipt.id());
            if (attributes(target) != null) throw new IOException("literature receipt ID collision");
            write(target, receipt, false);
            return receipt;
        });
    }

    /** Atomically replace the matching pending receipt, leaving any existing terminal result intact. */
    public Receipt complete(Receipt pending, Result result) throws IOException {
        Objects.requireNonNull(pending);
        requireTerminalResult(result);
        if (!pending.pending()) throw new IOException("literature receipt is not pending");
        return withRepository(false, null, () -> {
            Path scope = subjectDirectory(pending.subject());
            if (!isPrivateDirectory(scope)) throw new IOException("literature receipt subject does not exist");
            Path target = receiptPath(scope, pending.id());
            Receipt existing = read(target, pending.subject(), pending.id());
            if (!existing.asPending().equals(pending)) throw new IOException("literature receipt does not match pending attempt");
            if (!existing.pending()) {
                if (existing.result().equals(result)) return existing;
                throw new IOException("literature receipt already has a different terminal result");
            }
            var completed = new Receipt(pending.schema(), pending.id(), pending.subject(), pending.startedAt(),
                pending.query(), pending.endpoint(), pending.resultLimit(), result, clock.instant().toString());
            write(target, completed, true);
            return completed;
        });
    }

    /** Return only receipts for this exact subject, newest attempt first. */
    public List<Receipt> list(Subject subject) throws IOException {
        Objects.requireNonNull(subject);
        return withRepository(false, List.of(), () -> {
            Path scope = subjectDirectory(subject);
            if (!isPrivateDirectory(scope)) return List.of();
            var receipts = new ArrayList<Receipt>();
            for (Path path : entries(scope)) {
                String name = path.getFileName().toString();
                if (RECEIPT_FILE.matcher(name).matches()) {
                    receipts.add(read(path, subject, name.substring(0, name.length() - ".json".length())));
                }
            }
            receipts.sort(Comparator.comparing((Receipt receipt) -> Instant.parse(receipt.startedAt()))
                .reversed().thenComparing(Receipt::id));
            return List.copyOf(receipts);
        });
    }

    private <T> T withRepository(boolean create, T missing, IoOperation<T> operation) throws IOException {
        LOCAL_LOCK.lock();
        try {
            if (!checkDirectoryTree(create)) {
                if (missing != null) return missing;
                throw new IOException("literature receipt repository does not exist");
            }
            Path lockPath = directory.resolve(LOCK_FILE);
            try {
                Files.createFile(lockPath, privateAttributes(directory, "rw-------"));
            } catch (FileAlreadyExistsException alreadyExists) {
                // Another instance can create the lock after the directory check.
            }
            requirePrivateRegularFile(lockPath);
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, NOFOLLOW_LINKS);
                 var lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("literature receipt lock unavailable");
                return operation.run();
            }
        } finally {
            LOCAL_LOCK.unlock();
        }
    }

    private boolean checkDirectoryTree(boolean create) throws IOException {
        Path current = directory.getRoot();
        for (Path part : directory) {
            current = current.resolve(part);
            BasicFileAttributes attrs = attributes(current);
            if (attrs == null) {
                if (!create) return false;
                createPrivateDirectory(current);
            } else if (!attrs.isDirectory()) {
                throw new IOException("invalid literature receipt directory component");
            }
        }
        requirePrivate(directory);
        return true;
    }

    private static boolean isPrivateDirectory(Path path) throws IOException {
        BasicFileAttributes attrs = attributes(path);
        if (attrs == null) return false;
        if (!attrs.isDirectory()) throw new IOException("invalid literature receipt subject directory");
        requirePrivate(path);
        return true;
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path, privateAttributes(path.getParent(), "rwx------"));
            forceDirectory(path.getParent());
        } catch (FileAlreadyExistsException alreadyExists) {
            if (!isPrivateDirectory(path)) throw alreadyExists;
        }
    }

    private List<Path> entries(Path path) throws IOException {
        int max = path.equals(directory) ? MAX_SUBJECTS + 1 : MAX_RECEIPTS_PER_SUBJECT;
        var result = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                if (result.size() == max) throw new IOException("literature receipt repository exceeds its entry limit");
                result.add(entry);
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        return result;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void requirePrivate(Path path) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        if (view != null && view.readAttributes().permissions().stream()
            .anyMatch(permission -> !permission.name().startsWith("OWNER_"))) {
            throw new IOException("literature receipt storage is not owner-only");
        }
    }

    private static BasicFileAttributes requirePrivateRegularFile(Path path) throws IOException {
        BasicFileAttributes attrs = attributes(path);
        if (attrs == null || !attrs.isRegularFile()) throw new IOException("invalid literature receipt file");
        requirePrivate(path);
        return attrs;
    }

    private static FileAttribute<?>[] privateAttributes(Path parent, String permissions) throws IOException {
        if (!Files.getFileStore(parent).supportsFileAttributeView("posix")) return new FileAttribute<?>[0];
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))};
    }

    private static Receipt read(Path path, Subject expectedSubject, String expectedId) throws IOException {
        if (requirePrivateRegularFile(path).size() > MAX_BYTES) throw new IOException("literature receipt too large");
        try (var input = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("literature receipt too large");
            Receipt receipt = JSON.readValue(bytes, Receipt.class);
            if (receipt == null || !expectedSubject.equals(receipt.subject()) || !expectedId.equals(receipt.id())) {
                throw new IOException("literature receipt does not match its storage identity");
            }
            return receipt;
        }
    }

    private static void write(Path path, Receipt receipt, boolean replace) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(receipt);
        if (bytes.length > MAX_BYTES) throw new IOException("literature receipt too large");
        Path parent = path.getParent();
        Path temporary = Files.createTempFile(parent, ".receipt-", ".tmp", privateAttributes(parent, "rw-------"));
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            if (replace) Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path path) throws IOException {
        // POSIX directory metadata must also reach disk before an outbound request can proceed.
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            try (var directoryChannel = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                directoryChannel.force(true);
            }
        }
    }

    private Path subjectDirectory(Subject subject) {
        String value = subject.runId() + '\n' + subject.candidateId() + '\n' + subject.artifactHash();
        try {
            return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Path receiptPath(Path scope, String id) {
        return scope.resolve(id + ".json");
    }

    private static void requireHash(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 identity");
        }
    }

    private static void requireTimestamp(String value) {
        if (value == null || value.length() > 40 || !Instant.parse(value).toString().equals(value)) {
            throw new IllegalArgumentException("invalid literature receipt timestamp");
        }
    }

    private static void requireQuery(String value) {
        if (value == null || value.length() > TheoremSearchClient.MAX_QUERY_CHARS || !validUnicode(value)
            || value.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0x85)) {
            throw new IllegalArgumentException("invalid literature receipt query");
        }
    }

    private static boolean validText(String value, int maxLength) {
        return value.length() <= maxLength && !value.isBlank() && validUnicode(value)
            && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validUnicode(String value) {
        return StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static void requireTerminalResult(Result result) {
        if (result == null || result.status() == null || result.hits().size() > TheoremSearchClient.RESULT_LIMIT
            || result.retryAfterSeconds() < 0) {
            throw new IllegalArgumentException("invalid literature search result");
        }
        boolean valid = switch (result.status()) {
            case SUCCESS -> result.retryAfterSeconds() == 0 && result.hits().stream().allMatch(LiteratureReviewRepository::validHit);
            case FAILED -> result.hits().isEmpty() && result.retryAfterSeconds() == 0;
            case RATE_LIMITED -> result.hits().isEmpty();
            case DISABLED, CONFIRMATION_REQUIRED -> false;
        };
        if (!valid) throw new IllegalArgumentException("invalid literature search result");
    }

    private static boolean validHit(Hit hit) {
        return hit != null && (hit.name() != null || hit.link() != null || hit.title() != null || hit.source() != null)
            && (hit.name() == null || validText(hit.name(), 240))
            && (hit.title() == null || validText(hit.title(), 360))
            && (hit.source() == null || validText(hit.source(), 160))
            && (hit.year() == null || hit.year() >= 1 && hit.year() <= 9999)
            && (hit.link() == null || validLink(hit.link()));
    }

    private static boolean validLink(String value) {
        if (!validText(value, 2048)) return false;
        try {
            var uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
