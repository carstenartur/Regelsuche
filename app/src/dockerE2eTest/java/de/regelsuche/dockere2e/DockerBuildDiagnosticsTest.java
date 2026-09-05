package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.images.builder.ImageFromDockerfile;

class DockerBuildDiagnosticsTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void successfulBuildRemainsLazyMemoizedAndOnTheCallerThread() {
        AtomicInteger builds = new AtomicInteger();
        Thread caller = Thread.currentThread();
        var image = DockerBuildDiagnostics.observe(() -> {
            assertSame(caller, Thread.currentThread());
            builds.incrementAndGet();
            return "fixture-image";
        }, CLOCK, (started, failure) -> {
            throw new AssertionError("successful build must not query Docker events");
        });
        assertEquals(0, builds.get());
        assertEquals("fixture-image", image.get());
        assertEquals("fixture-image", image.get());
        assertEquals(1, builds.get());
    }

    @Test
    void preservesOriginalFailureAndNeverAddsABuildRetry() {
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger diagnoses = new AtomicInteger();
        var original = new IllegalStateException("missing intermediate image");
        var image = DockerBuildDiagnostics.observe(() -> {
            builds.incrementAndGet();
            throw original;
        }, CLOCK, (started, failure) -> {
            diagnoses.incrementAndGet();
            assertEquals(CLOCK.instant(), started);
            assertSame(original, failure);
        });
        assertSame(original, assertThrows(IllegalStateException.class, image::get));
        assertEquals(1, builds.get());
        assertEquals(1, diagnoses.get());
    }

    @Test
    void diagnosticFailureCannotReplaceTheBuildFailure() {
        var original = new IllegalStateException("build failed");
        var diagnostic = new IllegalArgumentException("diagnostic unavailable");
        var image = DockerBuildDiagnostics.observe(() -> {
            throw original;
        }, CLOCK, (started, failure) -> {
            throw diagnostic;
        });
        assertSame(original, assertThrows(IllegalStateException.class, image::get));
        assertSame(diagnostic, original.getSuppressed()[0]);
    }

    @Test
    void eventRowsAreBoundedSnapshotsWithoutUnrelatedPayload() throws IOException {
        try (var events = new DockerBuildDiagnostics.EventBuffer()) {
            Event mutable = new Event().withId("before").withAction("delete\nforged")
                .withFrom("DO_NOT_RETAIN_IMAGE_NAMES_OR_OTHER_PAYLOAD")
                .withType(EventType.IMAGE).withTime(1L);
            events.onNext(mutable);
            mutable.withId("after");
            assertTrue(events.lines().getFirst().endsWith("id=before"));
            assertTrue(events.lines().getFirst().contains("action=delete_forged"));
            assertFalse(events.lines().getFirst().contains("DO_NOT_RETAIN"));
            assertFalse(events.lines().getFirst().contains("\n"));
            for (int index = 0; index < DockerBuildDiagnostics.MAX_EVENTS + 3; index++) {
                events.onNext(new Event().withId(Integer.toString(index)));
            }
            assertEquals(DockerBuildDiagnostics.MAX_EVENTS, events.lines().size());
            assertTrue(events.truncated());
            assertTrue(events.lines().getFirst().endsWith("id=3"));
            assertTrue(events.lines().getLast().endsWith("id=258"));
        }
    }

    @Test
    void closesLateResponsesAndIgnoresEventsAfterTheDeadline() throws Exception {
        var events = new DockerBuildDiagnostics.EventBuffer();
        assertFalse(events.await(0, TimeUnit.NANOSECONDS));
        events.close();
        AtomicInteger closes = new AtomicInteger();
        events.onStart(() -> closes.incrementAndGet());
        events.onNext(new Event().withId("too-late"));
        events.close();
        assertEquals(1, closes.get());
        assertTrue(events.await(0, TimeUnit.NANOSECONDS));
        assertTrue(events.lines().isEmpty());
        assertEquals("NOT_COMPLETED", events.status());
    }

    @Test
    void reportsApiErrorsAndClosesTheResponseExactlyOnce() throws Exception {
        var events = new DockerBuildDiagnostics.EventBuffer();
        AtomicInteger closes = new AtomicInteger();
        events.onStart(() -> closes.incrementAndGet());
        events.onError(new IllegalStateException("not part of retained output"));
        events.onComplete();
        events.close();
        assertTrue(events.await(0, TimeUnit.NANOSECONDS));
        assertEquals("ERROR_IllegalStateException", events.status());
        assertEquals(1, closes.get());
    }

    @Test
    void interruptedCallerDoesNotReconnectToDockerOrLoseItsInterrupt() {
        var lines = new ArrayList<String>();
        Thread.currentThread().interrupt();
        try {
            DockerBuildDiagnostics.report("fixture", "Dockerfile.proof",
                CLOCK.instant(), CLOCK.instant(), new IllegalStateException(), lines::add);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(lines.getLast().contains("query=INTERRUPTED"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(30)
    void actualFailedDockerBuildRetainsAReadOnlyEventWindow() {
        var lines = new ArrayList<String>();
        var delegate = new ImageFromDockerfile().withFileFromString("Dockerfile",
            "FROM scratch\nCOPY deliberately-missing-diagnostic-fixture /missing\n");
        var image = DockerBuildDiagnostics.observe(delegate,
            "diagnostic-negative-fixture", lines::add);

        assertThrows(RuntimeException.class, image::get);
        assertTrue(delegate.isDeleteOnExit(), "ordinary Testcontainers cleanup stays enabled");
        assertTrue(lines.getFirst().contains("dockerfile=diagnostic-negative-fixture"));
        assertTrue(lines.getLast().contains("query=WINDOW_COMPLETE"),
            () -> "healthy Docker must serve the bounded event query: " + lines);
        assertTrue(lines.getLast().contains("history=BOUNDED_CLIENT_CLOCK_WINDOW"));
    }
}
