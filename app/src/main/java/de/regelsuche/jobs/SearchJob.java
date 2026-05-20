package de.regelsuche.jobs;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight snapshot of a search job. Used by {@link SearchJobManager} to
 * report job state and to persist/restore checkpoints.
 */
public record SearchJob(
    String id,
    String expression,
    String inputType,
    String profile,
    State state,
    Instant createdAt,
    Instant updatedAt,
    int discoveredSuccesses,
    int exploredStates,
    String bestExpression,
    int bestImprovement,
    List<String> notes
) {
    public SearchJob {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        notes = notes == null ? List.of() : List.copyOf(notes);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        state = state == null ? State.QUEUED : state;
        inputType = inputType == null ? "TERM" : inputType;
        profile = profile == null ? "FAST_SIMPLIFY" : profile;
    }

    public SearchJob withState(State newState) {
        return new SearchJob(id, expression, inputType, profile, newState, createdAt, Instant.now(),
            discoveredSuccesses, exploredStates, bestExpression, bestImprovement, notes);
    }

    public SearchJob withProgress(int explored, int successes, String best, int improvement) {
        return new SearchJob(id, expression, inputType, profile, state, createdAt, Instant.now(),
            successes, explored, best, improvement, notes);
    }

    public enum State {
        QUEUED,
        RUNNING,
        PAUSED,
        DONE,
        CANCELLED,
        FAILED
    }
}
