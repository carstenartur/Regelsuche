package de.regelsuche.literature;

import static de.regelsuche.literature.TheoremSearchClient.Status.FAILED;
import static de.regelsuche.literature.TheoremSearchClient.Status.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.literature.LiteratureReviewRepository.Receipt;
import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import de.regelsuche.literature.TheoremSearchClient.Hit;
import de.regelsuche.literature.TheoremSearchClient.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiteratureReviewRepositoryTest {
    private static final Subject SUBJECT = new Subject(hash("a"), hash("b"), hash("c"));
    private static final String EXACT_QUERY = "  Faltung über \u211a\n& (x + y)  ";
    private static final Clock START = Clock.fixed(Instant.parse("2026-11-08T12:34:56.123456789Z"), ZoneOffset.UTC);
    private static final Clock END = Clock.fixed(Instant.parse("2026-11-08T12:35:57.234567891Z"), ZoneOffset.UTC);

    @TempDir Path work;

    @Test
    void interruptedAttemptIsRetainedAsPendingAcrossRepositoryInstances() throws IOException {
        var pending = repository(START).begin(SUBJECT, EXACT_QUERY);

        assertEquals(List.of(pending), repository(END).list(SUBJECT));
        assertTrue(pending.pending());
        assertNull(pending.result());
        assertNull(pending.completedAt());
    }

    @Test
    void roundTripsExactQueryProviderAndRankedHits() throws IOException {
        var pending = repository(START).begin(SUBJECT, EXACT_QUERY);
        var result = new Result(SUCCESS, List.of(
            new Hit("Euler", "https://example.org/second", "Later title", "Journal B", 2024),
            new Hit("Bernoulli", "https://example.org/first", "Earlier title", "\u00a0", null)
        ), 0);

        var completed = repository(END).complete(pending, result);
        var reopened = repository(START).list(SUBJECT).getFirst();
        assertEquals(completed, reopened);
        assertEquals(SUBJECT, reopened.subject());
        assertEquals(EXACT_QUERY, reopened.query());
        assertEquals(TheoremSearchClient.ENDPOINT.toString(), reopened.endpoint());
        assertEquals(TheoremSearchClient.RESULT_LIMIT, reopened.resultLimit());
        assertEquals("2026-11-08T12:34:56.123456789Z", reopened.startedAt());
        assertEquals("2026-11-08T12:35:57.234567891Z", reopened.completedAt());
        assertEquals(result, reopened.result());
        assertFalse(reopened.pending());
    }

    @Test
    void isolatesEverySubjectDimensionAndRefusesStaleCompletion() throws IOException {
        var pending = repository(START).begin(SUBJECT, EXACT_QUERY);
        for (var other : List.of(
            new Subject(hash("d"), SUBJECT.candidateId(), SUBJECT.artifactHash()),
            new Subject(SUBJECT.runId(), hash("d"), SUBJECT.artifactHash()),
            new Subject(SUBJECT.runId(), SUBJECT.candidateId(), hash("d"))
        )) {
            assertEquals(List.of(), repository(END).list(other));
            var forged = new Receipt(pending.schema(), pending.id(), other, pending.startedAt(),
                pending.query(), pending.endpoint(), pending.resultLimit(), null, null);
            assertThrows(IOException.class, () -> repository(END).complete(forged, emptySuccess()));
        }

        var staleQuery = new Receipt(pending.schema(), pending.id(), SUBJECT, pending.startedAt(),
            "a different query", pending.endpoint(), pending.resultLimit(), null, null);
        assertThrows(IOException.class, () -> repository(END).complete(staleQuery, emptySuccess()));
        assertEquals(List.of(pending), repository(END).list(SUBJECT));
        assertThrows(IllegalArgumentException.class, () -> new Subject("../runs", hash("b"), hash("c")));
    }

    @Test
    void aRacingCompletionCannotReplaceAnExistingTerminalResult() throws Exception {
        var pending = repository(START).begin(SUBJECT, EXACT_QUERY);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(completeAfter(start, pending, emptySuccess()));
            var second = executor.submit(completeAfter(start, pending, new Result(FAILED, List.of(), 0)));
            start.countDown();
            var one = first.get();
            var two = second.get();
            assertTrue((one == null) ^ (two == null));
            var winner = one == null ? two : one;
            assertEquals(List.of(winner), repository(END).list(SUBJECT));
            assertEquals(winner, repository(START).complete(pending, winner.result()));
        }
    }

    @Test
    void rejectsSymlinksAndUsesOwnerOnlyPermissionsWhereSupported() throws IOException {
        if (!Files.getFileStore(work).supportsFileAttributeView("posix")) return;
        var root = work.resolve("receipts");
        Files.createSymbolicLink(root, Files.createDirectory(work.resolve("external")));
        assertThrows(IOException.class, () -> repository(START).begin(SUBJECT, EXACT_QUERY));
        assertThrows(IOException.class, () -> repository(START).list(SUBJECT));
        Files.delete(root);

        repository(START).begin(SUBJECT, EXACT_QUERY);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                var expected = Files.isDirectory(path)
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                assertEquals(expected, Files.getPosixFilePermissions(path));
            }
        }
    }

    @Test
    void corruptedReceiptsAreNeverReportedAsAnEmptySearch() throws IOException {
        var pending = repository(START).begin(SUBJECT, "valid query");
        Path receipt;
        try (var paths = Files.walk(work.resolve("receipts"))) {
            receipt = paths.filter(path -> path.getFileName().toString().equals(pending.id() + ".json"))
                .findFirst().orElseThrow();
        }
        String original = Files.readString(receipt);
        Files.writeString(receipt, original.replace("\"valid query\"", "123"));
        assertThrows(IOException.class, () -> repository(END).list(SUBJECT));
        Files.writeString(receipt, original + "{}");
        assertThrows(IOException.class, () -> repository(END).list(SUBJECT));
        assertThrows(IOException.class, () -> repository(END).complete(pending, emptySuccess()));
        Files.writeString(receipt, original + " ".repeat(1_048_576));
        assertThrows(IOException.class, () -> repository(END).list(SUBJECT));
    }

    private Callable<Receipt> completeAfter(CountDownLatch start, Receipt pending, Result result) {
        return () -> {
            start.await();
            try {
                return repository(END).complete(pending, result);
            } catch (IOException conflict) {
                return null;
            }
        };
    }

    private LiteratureReviewRepository repository(Clock clock) {
        return new LiteratureReviewRepository(work.resolve("receipts"), clock);
    }

    private static Result emptySuccess() {
        return new Result(SUCCESS, List.of(), 0);
    }

    private static String hash(String digit) {
        return "sha256:" + digit.repeat(64);
    }
}
