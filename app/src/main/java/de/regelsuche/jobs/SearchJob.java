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
    String activePhase,
    Instant createdAt,
    Instant updatedAt,
    int discoveredSuccesses,
    int exploredStates,
    String bestExpression,
    int bestImprovement,
    String lastProcessedExpression,
    boolean resumable,
    long knownStateCount,
    double estimatedBranchingFactor,
    long projectedStateCount,
    String searchSpaceRisk,
    String searchSpaceWarning,
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
        activePhase = activePhase == null ? defaultPhase(state) : activePhase;
        lastProcessedExpression = lastProcessedExpression == null ? expression : lastProcessedExpression;
        if (knownStateCount < 0) {
            throw new IllegalArgumentException("knownStateCount must not be negative");
        }
        if (projectedStateCount < 0) {
            throw new IllegalArgumentException("projectedStateCount must not be negative");
        }
        if (Double.isNaN(estimatedBranchingFactor) || estimatedBranchingFactor < 0.0) {
            throw new IllegalArgumentException("estimatedBranchingFactor must be non-negative");
        }
        searchSpaceRisk = searchSpaceRisk == null || searchSpaceRisk.isBlank() ? "LOW" : searchSpaceRisk;
    }

    public SearchJob withState(State newState) {
        boolean newResumable = switch (newState) {
            case PAUSED -> true;
            case RUNNING -> resumable;
            default -> false;
        };
        return new SearchJob(
            id,
            expression,
            inputType,
            profile,
            newState,
            defaultPhase(newState),
            createdAt,
            Instant.now(),
            discoveredSuccesses,
            exploredStates,
            bestExpression,
            bestImprovement,
            lastProcessedExpression,
            newResumable,
            knownStateCount,
            estimatedBranchingFactor,
            projectedStateCount,
            searchSpaceRisk,
            searchSpaceWarning,
            notes
        );
    }

    public SearchJob withActivePhase(String phase) {
        return new SearchJob(
            id,
            expression,
            inputType,
            profile,
            state,
            phase,
            createdAt,
            Instant.now(),
            discoveredSuccesses,
            exploredStates,
            bestExpression,
            bestImprovement,
            lastProcessedExpression,
            resumable,
            knownStateCount,
            estimatedBranchingFactor,
            projectedStateCount,
            searchSpaceRisk,
            searchSpaceWarning,
            notes
        );
    }

    public SearchJob withProgress(int explored, int successes, String best, int improvement, String lastProcessed) {
        return new SearchJob(
            id,
            expression,
            inputType,
            profile,
            state,
            activePhase,
            createdAt,
            Instant.now(),
            successes,
            explored,
            best,
            improvement,
            lastProcessed,
            resumable,
            knownStateCount,
            estimatedBranchingFactor,
            projectedStateCount,
            searchSpaceRisk,
            searchSpaceWarning,
            notes
        );
    }

    public SearchJob withSearchSpaceEstimate(
        long knownStates,
        double branchingFactor,
        long projectedStates,
        String risk,
        String warning
    ) {
        return new SearchJob(
            id,
            expression,
            inputType,
            profile,
            state,
            activePhase,
            createdAt,
            Instant.now(),
            discoveredSuccesses,
            exploredStates,
            bestExpression,
            bestImprovement,
            lastProcessedExpression,
            resumable,
            knownStates,
            branchingFactor,
            projectedStates,
            risk,
            warning,
            notes
        );
    }

    private static String defaultPhase(State state) {
        return switch (state) {
            case QUEUED -> "queued";
            case RUNNING -> "searching";
            case PAUSED -> "paused";
            case DONE -> "completed";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
        };
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
