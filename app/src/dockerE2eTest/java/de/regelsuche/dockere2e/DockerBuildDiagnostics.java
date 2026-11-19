package de.regelsuche.dockere2e;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.LazyFuture;

/** Failure-only, read-only diagnostics; never retries a build or changes cleanup. */
final class DockerBuildDiagnostics {
    static final int MAX_EVENTS = 256;
    static final int EVENT_WAIT_SECONDS = 5;

    private DockerBuildDiagnostics() {
    }

    static LazyFuture<String> observe(ImageFromDockerfile image, String dockerfile) {
        return observe(image, dockerfile, System.err::println);
    }

    static LazyFuture<String> observe(
            ImageFromDockerfile image, String dockerfile, Consumer<String> output) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(output, "output");
        return observe(image::get, Clock.systemUTC(), (started, failure) ->
            report(image.getDockerImageName(), dockerfile, started, Instant.now(),
                failure, output));
    }

    static LazyFuture<String> observe(
            Supplier<String> build, Clock clock,
            BiConsumer<Instant, RuntimeException> diagnostics) {
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new LazyFuture<>() {
            @Override
            protected String resolve() {
                Instant started = clock.instant();
                try {
                    return build.get();
                } catch (RuntimeException failure) {
                    try {
                        diagnostics.accept(started, failure);
                    } catch (RuntimeException diagnosticFailure) {
                        if (diagnosticFailure != failure) {
                            failure.addSuppressed(diagnosticFailure);
                        }
                    }
                    throw failure;
                }
            }
        };
    }

    static void report(String image, String dockerfile, Instant started,
            Instant failed, RuntimeException failure, Consumer<String> output) {
        // Round the client-clock window outward. A completed query is not a
        // complete daemon audit: old events may already have left its history.
        String since = Long.toString(started.getEpochSecond() - 1);
        String until = Long.toString(Math.max(started.getEpochSecond(),
            failed.getEpochSecond()) + 1);
        output.accept("REGELSUCHE_DOCKER_BUILD_FAILURE image=" + token(image)
            + " dockerfile=" + token(dockerfile) + " since=" + since
            + " until=" + until + " failureType=" + token(failure.getClass().getName())
            + " causeTypes=" + causeTypes(failure)
            + " failureHint=" + failureHint(failure));

        EventBuffer events = new EventBuffer();
        String query;
        try (events) {
            if (Thread.currentThread().isInterrupted()) {
                query = "INTERRUPTED";
            } else {
                try (EventsCmd command = DockerClientFactory.instance().client()
                        .eventsCmd().withEventTypeFilter("image", "container")
                        .withSince(since).withUntil(until)) {
                    command.exec(events);
                    query = events.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS)
                        ? events.status() : "TIMEOUT";
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            query = "INTERRUPTED";
        } catch (IOException | RuntimeException unavailable) {
            query = "UNAVAILABLE_" + token(unavailable.getClass().getSimpleName());
        }
        List<String> lines = events.lines();
        lines.forEach(output);
        output.accept("REGELSUCHE_DOCKER_EVENTS query=" + query
            + " retained=" + lines.size() + " truncated=" + events.truncated()
            + " history=BOUNDED_CLIENT_CLOCK_WINDOW");
    }

    /**
     * Retain only exception class names. Docker errors may contain private
     * registry URLs, image labels, tokens and build arguments in their messages.
     * A bounded causal chain is safer than logging arbitrary exception text.
     */
    static String causeTypes(Throwable failure) {
        StringBuilder types = new StringBuilder();
        for (Throwable cause : causes(failure)) {
            if (!types.isEmpty()) {
                types.append('>');
            }
            types.append(token(cause.getClass().getSimpleName()));
        }
        return types.isEmpty() ? "NONE" : types.toString();
    }

    /**
     * A heuristic diagnostic signal, not a root-cause conclusion. Never echo
     * messages from the Docker daemon or a nested exception into CI logs.
     */
    static String failureHint(Throwable failure) {
        for (Throwable cause : causes(failure)) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.substring(0, Math.min(message.length(), 4096))
                .toLowerCase(Locale.ROOT);
            if (lower.contains("no space left on device")) {
                return "DISK_SPACE";
            }
            if (lower.contains("too many requests") || lower.contains("toomanyrequests")
                    || lower.contains("rate limit")) {
                return "REGISTRY_RATE_LIMIT";
            }
            if (lower.contains("failed to compute cache key")
                    || lower.contains("failed to calculate checksum")
                    || lower.contains("no such image")) {
                return "CACHE_OR_IMAGE_REFERENCE";
            }
            if (lower.contains("copy failed") || lower.contains("no such file")) {
                return "BUILD_INPUT_REFERENCE";
            }
            if (lower.contains("timed out") || lower.contains("timeout")) {
                return "TIMEOUT";
            }
            if (lower.contains("connection refused") || lower.contains("connection reset")
                    || lower.contains("tls handshake")) {
                return "TRANSPORT";
            }
            if (lower.contains("unauthorized") || lower.contains("permission denied")) {
                return "ACCESS_DENIED";
            }
        }
        return "UNCLASSIFIED";
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new java.util.ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && chain.size() < 6 && seen.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    private static String token(Object value) {
        String text = Objects.toString(value, "unknown");
        return text.substring(0, Math.min(160, text.length()))
            .replaceAll("[^A-Za-z0-9_.:/-]", "_");
    }

    /** Stores projected strings, never mutable events, labels or container environments. */
    static final class EventBuffer implements ResultCallback<Event> {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private Closeable stream;
        private boolean closed;
        private boolean truncated;
        private String status = "NOT_COMPLETED";

        @Override
        public void onStart(Closeable incoming) {
            synchronized (this) {
                if (!closed) {
                    stream = incoming;
                    return;
                }
            }
            // A response arriving after our deadline must not reopen the stream.
            closeStream(incoming);
        }

        @Override
        public synchronized void onNext(Event event) {
            if (closed || event == null) {
                return;
            }
            if (lines.size() == MAX_EVENTS) {
                lines.removeFirst();
                truncated = true;
            }
            String id = event.getActor() == null
                ? event.getId() : event.getActor().getId();
            String action = event.getAction() == null
                ? event.getStatus() : event.getAction();
            lines.addLast("REGELSUCHE_DOCKER_EVENT time=" + token(event.getTime())
                + " timeNano=" + token(event.getTimeNano())
                + " type=" + token(event.getType())
                + " action=" + token(action) + " id=" + token(id)
                + safeExitCode(event, action));
        }

        /**
         * Docker's container "die" event can include a numeric exitCode.
         * Retain only that bounded integer: other actor attributes (image,
         * name, labels and environments) can contain private data. An exit
         * code of 137 alone does not establish an OOM kill.
         */
        private static String safeExitCode(Event event, String action) {
            if (event.getType() != EventType.CONTAINER || !"die".equals(action)
                    || event.getActor() == null
                    || event.getActor().getAttributes() == null) {
                return "";
            }
            String raw = event.getActor().getAttributes().get("exitCode");
            if (raw == null || !raw.matches("[0-9]{1,3}")) {
                return "";
            }
            int code = Integer.parseInt(raw);
            return code <= 255 ? " exitCode=" + code : "";
        }

        @Override
        public void onError(Throwable error) {
            finish("ERROR_" + token(error.getClass().getSimpleName()));
        }

        @Override
        public void onComplete() {
            finish("WINDOW_COMPLETE");
        }

        private void finish(String result) {
            try {
                closeWithStatus(result);
            } catch (IOException failedClose) {
                // closeWithStatus records the failure before releasing awaiters.
            }
        }

        @Override
        public void close() throws IOException {
            closeWithStatus(null);
        }

        private void closeWithStatus(String result) throws IOException {
            Closeable current;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                if (result != null) {
                    status = result;
                }
                current = stream;
                stream = null;
            }
            try {
                if (current != null) {
                    current.close();
                }
            } catch (IOException failedClose) {
                recordCloseFailure();
                throw failedClose;
            } finally {
                finished.countDown();
            }
        }

        private void closeStream(Closeable current) {
            try {
                current.close();
            } catch (IOException failedClose) {
                recordCloseFailure();
            }
        }

        private synchronized void recordCloseFailure() {
            status = "CLOSE_ERROR";
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }

        synchronized String status() {
            return status;
        }

        synchronized List<String> lines() {
            return List.copyOf(lines);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
