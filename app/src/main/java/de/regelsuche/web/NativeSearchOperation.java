package de.regelsuche.web;

import de.regelsuche.discovery.representation.SemanticDescriptionMeasurer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Operational observations stay distinct from the native run's mathematical event and artifact hashes. */
record NativeSearchOperation(String operationId, String source, String profile, SearchHeuristic budget,
        String repositoryRevision, String status, String terminalReason, String workspaceRunId, Progress progress) {
    private static final Set<String> LIVE = Set.of("RUNNING", "PAUSING", "PAUSED", "CANCELLING");
    private static final Set<String> TERMINAL = Set.of("CANCELLED", "INTERRUPTED", "COMPLETED", "FAILED");

    NativeSearchOperation {
        UUID.fromString(operationId);
        if (source == null || source.isBlank() || source.length() > 256 || !Set.of("small", "extended").contains(profile)
                || !repositoryRevision.matches("[a-f0-9]{40}") || budget == null || progress == null) {
            throw new IllegalArgumentException("invalid native operation sources");
        }
        boolean complete = "COMPLETED".equals(status);
        if (!LIVE.contains(status) && !TERMINAL.contains(status) || complete != (workspaceRunId != null)
                || complete && !workspaceRunId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid native operation state or workspace binding");
        }
    }

    record Minimum(int value, String expression, String stateCanonicalHash) {
        static Minimum retain(Minimum current, int value, SearchEvent visited) {
            if (current != null && current.value() <= value) return current;
            return new Minimum(value, visited.expression(), visited.canonicalHash());
        }
    }

    record Progress(long events, int visited, int queued, int generated, Map<String, Integer> pruned,
            Minimum fewestTokens, Minimum fewestAstNodes) {
        Progress {
            pruned = Collections.unmodifiableMap(new TreeMap<>(pruned));
        }

        static Progress initial() {
            return new Progress(0, 0, 0, 0, Map.of(), null, null);
        }

        Progress observed(SearchEvent event) {
            Map<String, Integer> counts = pruned;
            if (!event.pruningReason().isBlank()) {
                counts = new TreeMap<>(pruned);
                counts.merge(event.pruningReason(), 1, Integer::sum);
            }
            Minimum tokens = fewestTokens;
            Minimum ast = fewestAstNodes;
            if (event.type() == SearchEventType.STATE_VISITED) {
                var metrics = new SemanticDescriptionMeasurer().measure(event.expression());
                tokens = Minimum.retain(tokens, metrics.tokenCount(), event);
                ast = Minimum.retain(ast, metrics.astNodeCount(), event);
            }
            // Only STATE_EXPANDED carries the number returned by this engine invocation.
            // SEARCH_FINISHED reuses the event-local generatedCount field for explored states.
            int returned = generated + (event.type() == SearchEventType.STATE_EXPANDED ? event.generatedCount() : 0);
            return new Progress(events + 1, event.visitedCount(), event.frontierSize(), returned, counts, tokens, ast);
        }
    }

    boolean terminal() { return TERMINAL.contains(status); }

    NativeSearchOperation transition(String state, String reason, String runId) {
        return new NativeSearchOperation(operationId, source, profile, budget, repositoryRevision, state, reason, runId, progress);
    }

    NativeSearchOperation observe(SearchEvent event) {
        return new NativeSearchOperation(operationId, source, profile, budget, repositoryRevision, status,
            terminalReason, workspaceRunId, progress.observed(event));
    }
}
