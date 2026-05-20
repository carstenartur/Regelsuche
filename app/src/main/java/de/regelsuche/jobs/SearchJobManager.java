package de.regelsuche.jobs;

import de.regelsuche.checkpoint.InMemorySearchCheckpointRepository;
import de.regelsuche.checkpoint.SearchCheckpoint;
import de.regelsuche.checkpoint.SearchCheckpointRepository;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.search.SimplificationSuccess;
import de.regelsuche.search.TransformationSearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Coordinates {@link SearchJob}s on top of {@link TransformationSearchService}s.
 *
 * <p>The manager intentionally does not bundle the search infrastructure
 * itself: callers provide a {@link Function} that turns a {@link SearchJob}
 * into a freshly configured {@link TransformationSearchService} (with the
 * appropriate profile / heuristic / engine). The manager then runs the job
 * asynchronously and tracks lifecycle state.</p>
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@link #submit(String, InputType, String, java.util.List)} creates a {@link SearchJob.State#QUEUED} job
 *       and starts it on the shared executor.</li>
 *   <li>{@link #pause(String)} requests cooperative pausing; the running search
 *       continues until the next checkpoint, after which the job state flips
 *       to {@link SearchJob.State#PAUSED}.</li>
 *   <li>{@link #resume(String)} restarts a paused job from the latest checkpoint.</li>
 *   <li>{@link #cancel(String)} sets the state to {@link SearchJob.State#CANCELLED}
 *       and shuts down the service.</li>
 *   <li>{@link #checkpoint(Path)} writes all job states to disk; {@link #restore(Path)}
 *       restores them (without re-running unless {@link #resume(String)} is invoked).</li>
 * </ul>
 *
 * <p>The implementation is deliberately self-contained — it never blocks on
 * external resources beyond the search service the caller supplies — so it
 * fits within the existing minimal-dependency style of the project.</p>
 */
public class SearchJobManager {
    private final Map<String, JobHandle> jobs = new ConcurrentHashMap<>();
    private final Function<SearchJob, TransformationSearchService> serviceFactory;
    private final SearchCheckpointRepository checkpointRepository;

    public SearchJobManager(Function<SearchJob, TransformationSearchService> serviceFactory) {
        this(serviceFactory, new InMemorySearchCheckpointRepository());
    }

    public SearchJobManager(
        Function<SearchJob, TransformationSearchService> serviceFactory,
        SearchCheckpointRepository checkpointRepository
    ) {
        this.serviceFactory = serviceFactory;
        this.checkpointRepository = checkpointRepository;
    }

    public SearchCheckpointRepository checkpointRepository() {
        return checkpointRepository;
    }

    public SearchJob submit(String expression, InputType inputType, String profile, List<String> notes) {
        SearchJob job = new SearchJob(
            UUID.randomUUID().toString(),
            expression,
            inputType.name(),
            profile,
            SearchJob.State.QUEUED,
            null,
            null,
            0,
            0,
            null,
            0,
            notes == null ? List.of() : notes
        );
        JobHandle handle = new JobHandle(job);
        jobs.put(job.id(), handle);
        handle.start();
        return handle.snapshot();
    }

    public List<SearchJob> list() {
        return jobs.values().stream()
            .map(JobHandle::snapshot)
            .sorted(Comparator.comparing(SearchJob::createdAt))
            .toList();
    }

    public Optional<SearchJob> get(String id) {
        JobHandle handle = jobs.get(id);
        return handle == null ? Optional.empty() : Optional.of(handle.snapshot());
    }

    public Optional<SearchJob> pause(String id) {
        JobHandle handle = jobs.get(id);
        if (handle == null) {
            return Optional.empty();
        }
        handle.requestPause();
        return Optional.of(handle.snapshot());
    }

    public Optional<SearchJob> resume(String id) {
        JobHandle handle = jobs.get(id);
        if (handle == null) {
            return Optional.empty();
        }
        handle.resumeFromCheckpoint();
        return Optional.of(handle.snapshot());
    }

    public Optional<SearchJob> cancel(String id) {
        JobHandle handle = jobs.get(id);
        if (handle == null) {
            return Optional.empty();
        }
        handle.cancel();
        return Optional.of(handle.snapshot());
    }

    public void shutdown() {
        for (JobHandle handle : jobs.values()) {
            handle.cancel();
        }
    }

    /** Persist all job snapshots to {@code file} as JSON. */
    public synchronized void checkpoint(Path file) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"jobs\": [\n");
        List<SearchJob> all = list();
        for (int i = 0; i < all.size(); i++) {
            SearchJob job = all.get(i);
            builder.append("    {");
            builder.append("\"id\":").append(quote(job.id()));
            builder.append(",\"expression\":").append(quote(job.expression()));
            builder.append(",\"inputType\":").append(quote(job.inputType()));
            builder.append(",\"profile\":").append(quote(job.profile()));
            builder.append(",\"state\":").append(quote(job.state().name()));
            builder.append(",\"createdAt\":").append(quote(job.createdAt().toString()));
            builder.append(",\"updatedAt\":").append(quote(job.updatedAt().toString()));
            builder.append(",\"exploredStates\":").append(job.exploredStates());
            builder.append(",\"discoveredSuccesses\":").append(job.discoveredSuccesses());
            builder.append(",\"bestExpression\":").append(quote(job.bestExpression()));
            builder.append(",\"bestImprovement\":").append(job.bestImprovement());
            builder.append('}');
            if (i < all.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Re-hydrate jobs from a checkpoint file written by
     * {@link #checkpoint(Path)}. Restored jobs are <strong>not</strong>
     * automatically resumed — call {@link #resume(String)} for the ones you
     * want to continue.
     */
    public synchronized void restore(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        // Hand-rolled minimal parsing matching the checkpoint format.
        int arrayStart = json.indexOf("\"jobs\"");
        if (arrayStart < 0) {
            return;
        }
        int bracket = json.indexOf('[', arrayStart);
        int close = json.lastIndexOf(']');
        if (bracket < 0 || close < bracket) {
            return;
        }
        String body = json.substring(bracket + 1, close);
        int position = 0;
        while (position < body.length()) {
            int braceStart = body.indexOf('{', position);
            if (braceStart < 0) {
                break;
            }
            int braceEnd = matchingBrace(body, braceStart);
            if (braceEnd < 0) {
                break;
            }
            String entry = body.substring(braceStart, braceEnd + 1);
            Map<String, String> raw = parseFlat(entry);
            SearchJob job = new SearchJob(
                raw.get("id"),
                raw.getOrDefault("expression", ""),
                raw.getOrDefault("inputType", "TERM"),
                raw.getOrDefault("profile", "FAST_SIMPLIFY"),
                SearchJob.State.valueOf(raw.getOrDefault("state", "QUEUED")),
                raw.containsKey("createdAt") ? java.time.Instant.parse(raw.get("createdAt")) : null,
                raw.containsKey("updatedAt") ? java.time.Instant.parse(raw.get("updatedAt")) : null,
                parseInt(raw.get("discoveredSuccesses")),
                parseInt(raw.get("exploredStates")),
                raw.getOrDefault("bestExpression", null),
                parseInt(raw.get("bestImprovement")),
                List.of()
            );
            jobs.put(job.id(), new JobHandle(job).pausedFromRestore());
            position = braceEnd + 1;
        }
    }

    private static int matchingBrace(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Map<String, String> parseFlat(String entry) {
        Map<String, String> result = new LinkedHashMap<>();
        int position = 1; // skip leading '{'
        while (position < entry.length() - 1) {
            position = skipWhitespaceAndCommas(entry, position);
            if (position >= entry.length() - 1) {
                break;
            }
            if (entry.charAt(position) != '"') {
                break;
            }
            int keyEnd = findStringEnd(entry, position + 1);
            String key = entry.substring(position + 1, keyEnd);
            position = keyEnd + 1;
            while (position < entry.length() && entry.charAt(position) != ':') {
                position++;
            }
            position++;
            position = skipWhitespaceAndCommas(entry, position);
            if (position >= entry.length()) {
                break;
            }
            String value;
            if (entry.charAt(position) == '"') {
                int valueEnd = findStringEnd(entry, position + 1);
                value = entry.substring(position + 1, valueEnd);
                position = valueEnd + 1;
            } else {
                int valueStart = position;
                while (position < entry.length() && ",}\n\r".indexOf(entry.charAt(position)) < 0) {
                    position++;
                }
                value = entry.substring(valueStart, position).trim();
            }
            result.put(key, value);
        }
        return result;
    }

    private static int skipWhitespaceAndCommas(String entry, int position) {
        while (position < entry.length() && (Character.isWhitespace(entry.charAt(position)) || entry.charAt(position) == ',')) {
            position++;
        }
        return position;
    }

    private static int findStringEnd(String entry, int start) {
        int position = start;
        while (position < entry.length()) {
            char c = entry.charAt(position);
            if (c == '\\') {
                position += 2;
                continue;
            }
            if (c == '"') {
                return position;
            }
            position++;
        }
        return entry.length();
    }

    private static int parseInt(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("null")) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private final class JobHandle {
        private final AtomicReference<SearchJob> state;
        private volatile TransformationSearchService activeService;
        private volatile CompletableFuture<Void> activeFuture;
        private volatile boolean pauseRequested;

        JobHandle(SearchJob initial) {
            this.state = new AtomicReference<>(initial);
        }

        JobHandle pausedFromRestore() {
            state.updateAndGet(current -> current.withState(SearchJob.State.PAUSED));
            return this;
        }

        SearchJob snapshot() {
            return state.get();
        }

        void start() {
            SearchJob current = state.updateAndGet(snapshot -> snapshot.withState(SearchJob.State.RUNNING));
            launch(current);
        }

        void resume() {
            SearchJob current = state.get();
            if (current.state() == SearchJob.State.RUNNING) {
                return;
            }
            pauseRequested = false;
            SearchJob running = state.updateAndGet(snapshot -> snapshot.withState(SearchJob.State.RUNNING));
            launch(running);
        }

        /**
         * Resume from the latest stored {@link SearchCheckpoint} if any. The
         * job's effective input is rebuilt from the checkpoint's
         * {@link SearchCheckpoint#resumeSeed() resume seed} so that the
         * resumed search continues from progress instead of starting over.
         */
        void resumeFromCheckpoint() {
            SearchJob current = state.get();
            if (current.state() == SearchJob.State.RUNNING) {
                return;
            }
            pauseRequested = false;
            Optional<SearchCheckpoint> checkpoint = checkpointRepository.findByJobId(current.id());
            if (checkpoint.isPresent()) {
                SearchCheckpoint cp = checkpoint.get();
                SearchJob resumed = new SearchJob(
                    current.id(),
                    cp.resumeSeed(),
                    current.inputType(),
                    current.profile(),
                    SearchJob.State.RUNNING,
                    current.createdAt(),
                    Instant.now(),
                    current.discoveredSuccesses(),
                    current.exploredStates(),
                    current.bestExpression(),
                    current.bestImprovement(),
                    current.notes()
                );
                state.set(resumed);
                launch(resumed);
            } else {
                SearchJob running = state.updateAndGet(snapshot -> snapshot.withState(SearchJob.State.RUNNING));
                launch(running);
            }
        }

        void requestPause() {
            pauseRequested = true;
            TransformationSearchService service = activeService;
            if (service != null) {
                service.shutdown();
            }
            state.updateAndGet(current -> current.withState(SearchJob.State.PAUSED));
        }

        void cancel() {
            pauseRequested = true;
            TransformationSearchService service = activeService;
            if (service != null) {
                service.shutdown();
            }
            state.updateAndGet(current -> current.withState(SearchJob.State.CANCELLED));
        }

        private void launch(SearchJob current) {
            TransformationSearchService service = serviceFactory.apply(current);
            activeService = service;
            CompletableFuture<Void> future = service.submit(
                new InputRequest(InputType.valueOf(current.inputType()), current.expression())
            );
            activeFuture = future;
            future.whenComplete((ignored, error) -> finalizeRun(service, error));
        }

        private void finalizeRun(TransformationSearchService service, Throwable error) {
            try {
                List<SimplificationSuccess> successes = service.getSuccesses();
                int explored = service.getGraphSnapshot().nodes().size();
                Optional<SimplificationSuccess> best = service.getBestSolution();
                String bestExpr = best.map(SimplificationSuccess::simplifiedExpression).orElse(null);
                int improvement = best.map(SimplificationSuccess::improvement).orElse(0);
                state.updateAndGet(current ->
                    current.withProgress(explored, successes.size(), bestExpr, improvement));

                // Always persist a checkpoint so a later resume can pick up.
                writeCheckpoint(service, successes);

                if (error != null) {
                    state.updateAndGet(current -> current.withState(SearchJob.State.FAILED));
                } else if (pauseRequested && state.get().state() != SearchJob.State.CANCELLED) {
                    state.updateAndGet(current -> current.withState(SearchJob.State.PAUSED));
                } else if (state.get().state() == SearchJob.State.CANCELLED) {
                    // keep cancelled
                } else {
                    state.updateAndGet(current -> current.withState(SearchJob.State.DONE));
                }
            } finally {
                service.shutdown();
            }
        }

        private void writeCheckpoint(TransformationSearchService service, List<SimplificationSuccess> successes) {
            SearchJob current = state.get();
            // Use the visited node set from the graph snapshot as a proxy for
            // already-explored states. The frontier is the best successes (sorted
            // by improvement descending) so resume can re-seed from the most
            // promising state.
            Set<String> visited = new LinkedHashSet<>(service.getGraphSnapshot().nodes());
            List<SearchCheckpoint.BestPath> bestPaths = successes.stream()
                .sorted(Comparator.comparingInt(SimplificationSuccess::improvement).reversed())
                .limit(16)
                .map(success -> new SearchCheckpoint.BestPath(
                    success.simplifiedExpression(),
                    success.improvement(),
                    success.transformationRule() == null ? "" : success.transformationRule()))
                .toList();
            List<String> frontier = bestPaths.stream()
                .map(SearchCheckpoint.BestPath::expression)
                .toList();
            SearchCheckpoint checkpoint = new SearchCheckpoint(
                current.id(),
                current.expression(),
                current.profile(),
                "default",
                frontier,
                List.copyOf(visited),
                bestPaths,
                current.id().hashCode(),
                current.createdAt(),
                Instant.now()
            );
            checkpointRepository.save(checkpoint);
        }
    }

    /** Convenience: render an unmodifiable snapshot map for diagnostic UIs. */
    public Map<String, SearchJob> snapshotMap() {
        Map<String, SearchJob> map = new LinkedHashMap<>();
        for (Map.Entry<String, JobHandle> entry : jobs.entrySet()) {
            map.put(entry.getKey(), entry.getValue().snapshot());
        }
        return Collections.unmodifiableMap(map);
    }

    /** Compare two job lists by id for diff-based UIs. */
    public static List<String> diffIds(List<SearchJob> previous, List<SearchJob> current) {
        List<String> diff = new ArrayList<>();
        for (SearchJob job : current) {
            boolean exists = previous.stream().anyMatch(other -> other.id().equals(job.id()));
            if (!exists) {
                diff.add(job.id());
            }
        }
        return diff;
    }
}
