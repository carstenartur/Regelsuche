package de.regelsuche.radar;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static de.regelsuche.radar.AstRuleRadar.ApplicableMove;
import static de.regelsuche.radar.AstRuleRadar.CandidateOutcome;
import static de.regelsuche.radar.AstRuleRadar.Context;
import static de.regelsuche.radar.AstRuleRadar.Diagnostic;
import static de.regelsuche.radar.AstRuleRadar.SearchEdge;
import static de.regelsuche.radar.AstRuleRadar.SearchEvent;
import static de.regelsuche.radar.AstRuleRadar.SearchResult;
import static de.regelsuche.radar.AstRuleRadar.SearchState;
import static de.regelsuche.radar.AstRuleRadar.Snapshot;

/**
 * Deterministic bounded search driven exclusively by candidates from
 * {@link AstRuleRadarService}. Manual application, preview and search therefore
 * share candidate identity and successor expressions.
 */
public final class RuleRadarSearchService {
    private static final String SCHEMA = "regelsuche.ast-rule-radar-search/v1";
    private final AstRuleRadarService radar;
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    public RuleRadarSearchService(AstRuleRadarService radar) {
        if (radar == null) {
            throw new IllegalArgumentException("radar is required");
        }
        this.radar = radar;
    }

    public SearchResult search(SearchRequest requested) {
        SearchRequest request = requested == null ? SearchRequest.defaults("") : requested;
        Snapshot initial = radar.inspect(request.expression(), request.context());
        if (!initial.valid()) {
            return new SearchResult(
                SCHEMA, request.expression(), request.targetExpression(), false, "", 0, 0,
                List.of(), List.of(), List.of(), Map.of(), initial.diagnostics());
        }

        String targetCanonical = canonical(request.targetExpression());
        String initialCanonical = initial.canonicalExpression();
        String initialStateId = stateId(initialCanonical);
        SearchState initialState = new SearchState(
            initialStateId, initial.expression(), initialCanonical, 0,
            !targetCanonical.isBlank() && initialCanonical.equals(targetCanonical));
        List<SearchState> states = new ArrayList<>();
        states.add(initialState);
        if (initialState.target()) {
            return new SearchResult(
                SCHEMA, initial.expression(), request.targetExpression(), true, initialStateId,
                0, 0, states, List.of(), List.of(), Map.of(), List.of());
        }

        ArrayDeque<FrontierState> frontier = new ArrayDeque<>();
        frontier.add(new FrontierState(initialState));
        Map<String, Integer> bestDepthByCanonical = new LinkedHashMap<>();
        bestDepthByCanonical.put(initialCanonical, 0);
        Map<String, SearchState> stateByCanonical = new LinkedHashMap<>();
        stateByCanonical.put(initialCanonical, initialState);
        List<SearchEdge> edges = new ArrayList<>();
        List<SearchEvent> events = new ArrayList<>();
        Map<String, CandidateOutcome> finalOutcomes = new LinkedHashMap<>();
        long sequence = 0;
        int explored = 0;
        int generated = 0;
        boolean reached = false;
        String terminalStateId = "";

        search:
        while (!frontier.isEmpty()) {
            FrontierState current = frontier.removeFirst();
            explored++;
            if (current.state().depth() >= request.maxDepth()) {
                continue;
            }
            Snapshot snapshot = radar.inspect(current.state().expression(), request.context());
            List<ApplicableMove> candidates = snapshot.candidates();
            generated += candidates.size();
            for (int index = 0; index < candidates.size(); index++) {
                ApplicableMove candidate = candidates.get(index);
                if (index >= request.maxMovesPerState()) {
                    sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                        CandidateOutcome.PRUNED_BUDGET, "per-state move budget reached");
                    continue;
                }
                if (!candidate.applicable()) {
                    CandidateOutcome rejected = candidate.outcome() == CandidateOutcome.REJECTED_VALIDATION
                        ? CandidateOutcome.REJECTED_VALIDATION
                        : candidate.outcome() == CandidateOutcome.REJECTED_ASSUMPTION
                            ? CandidateOutcome.REJECTED_ASSUMPTION
                            : CandidateOutcome.FAILED_APPLICATION;
                    sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                        rejected, "candidate is visible for audit but is not executable");
                    continue;
                }
                sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                    CandidateOutcome.SELECTED, "candidate selected in deterministic order");
                if (candidate.expressionAfter().isBlank()) {
                    sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                        CandidateOutcome.FAILED_APPLICATION, "candidate advertised no successor expression");
                    continue;
                }
                if (states.size() >= request.maxStates()) {
                    sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                        CandidateOutcome.PRUNED_BUDGET, "global state budget reached");
                    continue;
                }

                String nextCanonical = canonical(candidate.expressionAfter());
                int nextDepth = current.state().depth() + 1;
                Integer knownDepth = bestDepthByCanonical.get(nextCanonical);
                if (knownDepth != null) {
                    CandidateOutcome pruning = knownDepth < nextDepth
                        ? CandidateOutcome.PRUNED_KNOWN_BETTER
                        : CandidateOutcome.PRUNED_DUPLICATE;
                    sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                        pruning, "canonical successor already reached at depth " + knownDepth);
                    continue;
                }

                String nextStateId = stateId(nextCanonical);
                boolean isTarget = !targetCanonical.isBlank() && nextCanonical.equals(targetCanonical);
                SearchState next = new SearchState(
                    nextStateId,
                    candidate.expressionAfter(),
                    nextCanonical,
                    nextDepth,
                    isTarget
                );
                bestDepthByCanonical.put(nextCanonical, nextDepth);
                stateByCanonical.put(nextCanonical, next);
                states.add(next);
                SearchEdge edge = new SearchEdge(
                    edgeId(current.state().stateId(), nextStateId, candidate.candidateId()),
                    current.state().stateId(),
                    nextStateId,
                    current.state().expression(),
                    next.expression(),
                    candidate.candidateId(),
                    candidate.pathKey(),
                    candidate.ruleId(),
                    candidate.origin(),
                    CandidateOutcome.APPLIED
                );
                edges.add(edge);
                sequence = event(events, finalOutcomes, sequence, current.state(), candidate,
                    CandidateOutcome.APPLIED, "successor state " + nextStateId + " created");
                if (isTarget) {
                    reached = true;
                    terminalStateId = nextStateId;
                    break search;
                }
                frontier.addLast(new FrontierState(next));
            }
        }

        if (!reached && !states.isEmpty()) {
            terminalStateId = states.getLast().stateId();
        }
        return new SearchResult(
            SCHEMA,
            initial.expression(),
            request.targetExpression(),
            reached,
            terminalStateId,
            explored,
            generated,
            states,
            edges,
            events,
            finalOutcomes,
            List.of()
        );
    }

    private long event(
        List<SearchEvent> events,
        Map<String, CandidateOutcome> outcomes,
        long sequence,
        SearchState state,
        ApplicableMove candidate,
        CandidateOutcome outcome,
        String detail
    ) {
        long next = sequence + 1;
        events.add(new SearchEvent(
            next,
            state.stateId(),
            state.expression(),
            candidate.candidateId(),
            candidate.pathKey(),
            candidate.ruleId(),
            outcome,
            detail
        ));
        outcomes.put(candidate.candidateId(), outcome);
        return next;
    }

    private String canonical(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private String stateId(String canonicalExpression) {
        return "state:" + sha256(canonicalExpression).substring(0, 20);
    }

    private String edgeId(String from, String to, String candidateId) {
        return "edge:" + sha256(from + "\u001f" + to + "\u001f" + candidateId).substring(0, 20);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record SearchRequest(
        String expression,
        String targetExpression,
        Context context,
        int maxDepth,
        int maxStates,
        int maxMovesPerState
    ) {
        private static final int MAX_DEPTH_LIMIT = 12;
        private static final int MAX_STATE_LIMIT = 2_000;
        private static final int MAX_MOVE_LIMIT = 500;

        public SearchRequest {
            expression = expression == null ? "" : expression.trim();
            targetExpression = targetExpression == null ? "" : targetExpression.trim();
            context = context == null ? Context.defaults() : context;
            maxDepth = bounded(maxDepth, 4, MAX_DEPTH_LIMIT);
            maxStates = bounded(maxStates, 120, MAX_STATE_LIMIT);
            maxMovesPerState = bounded(maxMovesPerState, 60, MAX_MOVE_LIMIT);
        }

        public static SearchRequest defaults(String expression) {
            return new SearchRequest(expression, "", Context.defaults(), 4, 120, 60);
        }

        private static int bounded(int value, int fallback, int maximum) {
            int effective = value <= 0 ? fallback : value;
            return Math.min(effective, maximum);
        }
    }

    private record FrontierState(SearchState state) {
    }
}
