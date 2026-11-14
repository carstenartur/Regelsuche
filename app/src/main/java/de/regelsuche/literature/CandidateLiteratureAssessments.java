package de.regelsuche.literature;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import de.regelsuche.literature.RuleLiteratureCatalog.Relation;
import de.regelsuche.literature.RuleLiteratureCatalog.Source;
import java.io.IOException;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Explicit human assessments of a candidate's literature context, stored privately. */
public final class CandidateLiteratureAssessments {
    public static final String SCHEMA = "regelsuche.literature-assessment/v1";
    public static final String COVERAGE_SCHEMA = "regelsuche.literature-assessment/v2";
    public static final int MAX_BYTES = 128 * 1024;

    private static final int MAX_ENTRIES = 1024;
    private static final String LOCK_FILE = ".lock";
    private static final Object LOCAL_WRITES = new Object();
    private static final Pattern CONTENT_FILE = Pattern.compile("[0-9a-f]{64}-[0-9a-f]{64}\\.json");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final Path directory;

    public CandidateLiteratureAssessments(Path directory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        if (this.directory.getParent() == null) throw new IllegalArgumentException("assessment directory needs a parent");
    }

    public enum Status { PRIOR_ART_FOUND, NO_PRIOR_ART_FOUND_WITHIN_SCOPE, UNRESOLVED }

    public enum Access { SEARCHED, PARTIAL, INACCESSIBLE }

    public record UnresolvedHit(String identifier, String reason) {
        public UnresolvedHit {
            requireText(identifier, 1000);
            requireText(reason, 2000);
        }
    }

    public record Coverage(Access status, String limitations, List<UnresolvedHit> unresolvedHits) {
        public Coverage {
            Objects.requireNonNull(status, "source access status");
            requireText(limitations, 2000);
            unresolvedHits = boundedList(unresolvedHits, 0, 32);
        }

        private boolean complete() {
            return status == Access.SEARCHED && unresolvedHits.isEmpty();
        }
    }

    public record Scope(String source, String versionOrDate,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Coverage coverage) {
        public Scope(String source, String versionOrDate) {
            this(source, versionOrDate, null);
        }

        public Scope {
            requireText(source, 500);
            requireText(versionOrDate, 300);
        }
    }

    public record Match(Source source, Relation relation, List<String> assumptions, String rationale,
                        String entailmentRationale,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Integer scopeIndex) {
        public Match(Source source, Relation relation, List<String> assumptions, String rationale,
                     String entailmentRationale) {
            this(source, relation, assumptions, rationale, entailmentRationale, null);
        }

        public Match {
            Objects.requireNonNull(source, "match source");
            Objects.requireNonNull(relation, "match relation");
            if (source.authors().size() > 32) throw new IllegalArgumentException("too many source authors");
            assumptions = boundedList(assumptions, 0, 24);
            assumptions.forEach(assumption -> requireText(assumption, 1000));
            requireText(rationale, 3000);
            if (entailmentRationale != null) requireText(entailmentRationale, 3000);
        }

        private boolean establishesPriorArt() {
            return relation == Relation.EXACT || relation == Relation.EQUIVALENT
                || relation == Relation.SOURCE_GENERALIZATION && entailmentRationale != null;
        }
    }

    public record Assessment(String schema, Subject subject, String reviewedOn, String reviewedBy, List<Scope> scope,
                             List<String> queries, Status status, List<Match> matches) {
        public Assessment {
            boolean coverageRequired = COVERAGE_SCHEMA.equals(schema);
            if (!coverageRequired && !SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("invalid literature assessment schema");
            }
            Objects.requireNonNull(subject, "assessment subject");
            Objects.requireNonNull(status, "assessment status");
            requireText(reviewedBy, 300);
            requireDate(reviewedOn);
            scope = boundedList(scope, 1, 24);
            if (scope.stream().anyMatch(source -> (source.coverage() != null) != coverageRequired)) {
                throw new IllegalArgumentException("source coverage differs from assessment schema");
            }
            boolean allInaccessible = coverageRequired && scope.stream()
                .allMatch(source -> source.coverage().status() == Access.INACCESSIBLE);
            queries = boundedList(queries, allInaccessible ? 0 : 1, 32);
            queries.forEach(query -> requireText(query, 1000));
            matches = boundedList(matches, 0, 32);
            if (coverageRequired && status == Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE
                && (scope.stream().anyMatch(source -> !source.coverage().complete())
                    || matches.stream().anyMatch(match -> match.relation() == Relation.SOURCE_GENERALIZATION))) {
                throw new IllegalArgumentException("incomplete coverage or unresolved generalization in scoped absence");
            }
            for (Match match : matches) {
                Integer index = match.scopeIndex();
                if (coverageRequired && (index == null || index < 0 || index >= scope.size()
                    || scope.get(index).coverage().status() == Access.INACCESSIBLE)) {
                    throw new IllegalArgumentException("reviewed match requires one accessible declared source");
                }
                if (!coverageRequired && index != null) {
                    throw new IllegalArgumentException("source index requires the coverage schema");
                }
            }
            boolean priorArt = matches.stream().anyMatch(Match::establishesPriorArt);
            if ((status == Status.PRIOR_ART_FOUND && !priorArt)
                || (status == Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE && priorArt)) {
                throw new IllegalArgumentException("literature assessment status conflicts with its matches");
            }
        }
    }

    public static final class InvalidAssessmentException extends IOException {
        private static final long serialVersionUID = 1L;

        private InvalidAssessmentException(String message) {
            super(message);
        }

        private InvalidAssessmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Assessment retain(Subject expected, byte[] json) throws IOException {
        Objects.requireNonNull(expected);
        Assessment assessment = decode(json, expected);
        byte[] canonical = JSON.writeValueAsBytes(assessment);
        if (canonical.length > MAX_BYTES) throw new InvalidAssessmentException("literature assessment is too large");
        Path target = directory.resolve(fileName(expected, canonical));
        // File locks are JVM-wide. Also serialize calls within this process.
        synchronized (LOCAL_WRITES) {
            checkDirectory(true);
            Path lockFile = directory.resolve(LOCK_FILE);
            try {
                Files.createFile(lockFile, permissions(directory, "rw-------"));
            } catch (FileAlreadyExistsException existing) {
                // Other repository instances use the same lock.
            }
            requirePrivateFile(lockFile);
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, NOFOLLOW_LINKS);
                 var lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("literature assessment lock unavailable");
                if (attributes(target) != null) {
                    Assessment retained = read(target, expected);
                    if (!retained.equals(assessment)) throw new IOException("literature assessment identity conflict");
                    return retained;
                }
                try (var entries = Files.newDirectoryStream(directory)) {
                    int count = 0;
                    for (Path entry : entries) {
                        if (!entry.getFileName().toString().equals(LOCK_FILE) && ++count >= MAX_ENTRIES) {
                            throw new IOException("literature assessment storage limit reached");
                        }
                    }
                } catch (DirectoryIteratorException e) {
                    throw e.getCause();
                }
                write(target, canonical);
            }
        }
        return assessment;
    }

    public List<Assessment> list(Subject subject) throws IOException {
        Objects.requireNonNull(subject);
        if (!checkDirectory(false)) return List.of();
        var assessments = new ArrayList<Assessment>();
        // Scan names in deterministic content order so the date sort has a stable tie order.
        var paths = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(directory, subjectKey(subject) + "-*.json")) {
            for (Path path : entries) {
                if (paths.size() == MAX_ENTRIES) throw new IOException("literature assessment storage limit reached");
                if (!CONTENT_FILE.matcher(path.getFileName().toString()).matches()) {
                    throw new IOException("invalid literature assessment filename");
                }
                paths.add(path);
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        paths.sort(Comparator.naturalOrder());
        for (Path path : paths) assessments.add(read(path, subject));
        assessments.sort(Comparator.comparing(Assessment::reviewedOn).reversed());
        return List.copyOf(assessments);
    }

    private static Assessment decode(byte[] bytes, Subject expected) throws InvalidAssessmentException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new InvalidAssessmentException("invalid literature assessment size");
        }
        Assessment result;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            result = JSON.readValue(json, Assessment.class);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new InvalidAssessmentException("invalid literature assessment JSON", invalid);
        }
        if (result == null || !expected.equals(result.subject())) {
            throw new InvalidAssessmentException("literature assessment does not match the retained evidence");
        }
        return result;
    }

    private static Assessment read(Path path, Subject subject) throws IOException {
        requirePrivateFile(path);
        try (var input = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            Assessment result = decode(input.readNBytes(MAX_BYTES + 1), subject);
            if (!path.getFileName().toString().equals(fileName(subject, JSON.writeValueAsBytes(result)))) {
                throw new IOException("literature assessment content differs from its storage identity");
            }
            return result;
        } catch (InvalidAssessmentException invalid) {
            throw new IOException("invalid retained literature assessment", invalid);
        }
    }

    private void write(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".assessment-", ".tmp", permissions(directory, "rw-------"));
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean checkDirectory(boolean create) throws IOException {
        Path current = directory.getRoot();
        for (Path part : directory) {
            current = current.resolve(part);
            BasicFileAttributes attrs = attributes(current);
            if (attrs == null) {
                if (!create) return false;
                try {
                    Files.createDirectory(current, permissions(current.getParent(), "rwx------"));
                    forceDirectory(current.getParent());
                } catch (FileAlreadyExistsException existing) {
                    attrs = attributes(current);
                    if (attrs == null || !attrs.isDirectory()) throw existing;
                }
            } else if (!attrs.isDirectory()) {
                throw new IOException("invalid literature assessment directory component");
            }
        }
        requirePrivate(directory);
        return true;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void requirePrivateFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw new IOException("invalid literature assessment file");
        requirePrivate(path);
    }

    private static void requirePrivate(Path path) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        if (view != null && view.readAttributes().permissions().stream()
            .anyMatch(permission -> !permission.name().startsWith("OWNER_"))) {
            throw new IOException("literature assessment storage is not owner-only");
        }
    }

    private static FileAttribute<?>[] permissions(Path parent, String mode) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView("posix")
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))}
            : new FileAttribute<?>[0];
    }

    private static void forceDirectory(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }
    }

    private static String fileName(Subject subject, byte[] canonical) {
        return subjectKey(subject) + "-" + sha256(canonical) + ".json";
    }

    private static String subjectKey(Subject subject) {
        String key = subject.runId() + "\n" + subject.candidateId() + "\n" + subject.artifactHash();
        return sha256(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static <T> List<T> boundedList(List<T> values, int min, int max) {
        if (values == null || values.size() < min || values.size() > max) {
            throw new IllegalArgumentException("invalid literature assessment list size");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.length() > limit || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
            || value.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0x85)
            || value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw new IllegalArgumentException("invalid literature assessment text");
        }
    }

    private static void requireDate(String value) {
        if (value == null || value.length() != 10) throw new IllegalArgumentException("invalid literature assessment date");
        try {
            var date = LocalDate.parse(value);
            if (date.getYear() < 1 || !date.toString().equals(value)) {
                throw new IllegalArgumentException("invalid literature assessment date");
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid literature assessment date", e);
        }
    }
}
