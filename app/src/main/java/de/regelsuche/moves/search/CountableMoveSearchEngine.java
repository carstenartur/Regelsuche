package de.regelsuche.moves.search;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.moves.MoveCandidateTransformationEngine;
import de.regelsuche.moves.MoveOrdinal;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.RewriteMoveDeriver;
import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic and bounded multi-step search over explicit countable rewrite moves.
 */
public final class CountableMoveSearchEngine implements MoveSearchEngine {
    private static final int MIN_DEPTH = 1;
    private static final int MAX_DEPTH = 4;

    private final MoveCandidateTransformationEngine transformationEngine;
    private final ExpressionCanonicalizer canonicalizer;
    private final ExpressionScorer scorer;
    private final RewriteMoveDeriver rewriteMoveDeriver;

    public CountableMoveSearchEngine() {
        this(
            new MoveCandidateTransformationEngine(),
            new ExpressionCanonicalizer(),
            new ExpressionScorer(),
            new RewriteMoveDeriver()
        );
    }

    public CountableMoveSearchEngine(
        MoveCandidateTransformationEngine transformationEngine,
        ExpressionCanonicalizer canonicalizer,
        ExpressionScorer scorer,
        RewriteMoveDeriver rewriteMoveDeriver
    ) {
        this.transformationEngine = transformationEngine == null
            ? new MoveCandidateTransformationEngine()
            : transformationEngine;
        this.canonicalizer = canonicalizer == null ? new ExpressionCanonicalizer() : canonicalizer;
        this.scorer = scorer == null ? new ExpressionScorer() : scorer;
        this.rewriteMoveDeriver = rewriteMoveDeriver == null ? new RewriteMoveDeriver() : rewriteMoveDeriver;
    }

    @Override
    public CountableMoveSearchResult search(
        String inputExpression,
        String targetExpression,
        int maxDepth,
        int maxStates
    ) {
        String input = normalize(inputExpression);
        String target = normalize(targetExpression);
        if (input.isBlank() || target.isBlank()) {
            return failure(
                input,
                target,
                List.of(input),
                List.of(),
                List.of(),
                0,
                0,
                FailureReason.INVALID_INPUT
            );
        }

        int depthLimit = Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, maxDepth));
        int stateBudget = Math.max(1, maxStates);
        String canonicalTarget = canonical(target);

        SearchNode root = new SearchNode(input, canonical(input), List.of(input), List.of(), List.of(), 0);
        LinkedHashSet<String> uniqueCanonicals = new LinkedHashSet<>();
        uniqueCanonicals.add(root.canonicalExpression());
        if (root.canonicalExpression().equals(canonicalTarget)) {
            return success(input, target, root.pathExpressions(), root.appliedMoves(), root.appliedRuleIds(), 1, 1);
        }

        ArrayDeque<SearchNode> frontier = new ArrayDeque<>();
        frontier.add(root);
        int exploredStateCount = 0;

        while (!frontier.isEmpty()) {
            if (exploredStateCount >= stateBudget) {
                return failure(
                    input,
                    target,
                    List.of(input),
                    List.of(),
                    List.of(),
                    exploredStateCount,
                    uniqueCanonicals.size(),
                    FailureReason.MAX_STATES_REACHED
                );
            }
            SearchNode current = frontier.removeFirst();
            exploredStateCount++;
            if (current.depth() >= depthLimit) {
                continue;
            }

            List<Transformation> transformations = new ArrayList<>(transformationEngine.transform(current.expression()));
            transformations.sort(Comparator.comparing(Transformation::rule)
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(Transformation::applicationKey));

            for (Transformation transformation : transformations) {
                String nextExpression = normalize(transformation.transformedExpression());
                if (nextExpression.equals(current.expression())) {
                    continue;
                }
                String nextCanonical = canonical(nextExpression);
                if (!uniqueCanonicals.add(nextCanonical)) {
                    continue;
                }

                RewriteMove move = rewriteMoveFor(current.expression(), transformation);
                SearchNode next = current.advance(nextExpression, nextCanonical, move, transformation.rule());
                if (nextCanonical.equals(canonicalTarget)) {
                    return success(
                        input,
                        target,
                        next.pathExpressions(),
                        next.appliedMoves(),
                        next.appliedRuleIds(),
                        exploredStateCount,
                        uniqueCanonicals.size()
                    );
                }
                frontier.addLast(next);
            }
        }

        return failure(
            input,
            target,
            List.of(input),
            List.of(),
            List.of(),
            exploredStateCount,
            uniqueCanonicals.size(),
            FailureReason.TARGET_NOT_REACHED
        );
    }

    private RewriteMove rewriteMoveFor(String beforeExpression, Transformation transformation) {
        Optional<MoveCandidateTransformationEngine.MoveMetadata> metadata =
            MoveCandidateTransformationEngine.metadataOf(transformation);
        if (metadata.isPresent()) {
            return fromMetadata(beforeExpression, transformation, metadata.get());
        }
        return rewriteMoveDeriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
            beforeExpression,
            transformation.transformedExpression(),
            transformation.rule(),
            transformation.rule(),
            transformation.assumptions(),
            "countable-move-search",
            List.of("classic-fallback")
        ));
    }

    private RewriteMove fromMetadata(
        String beforeExpression,
        Transformation transformation,
        MoveCandidateTransformationEngine.MoveMetadata metadata
    ) {
        RewriteMoveKind kind = moveKindFromMoveId(metadata.moveId());
        List<MoveParameter> parameters = metadata.parameters().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new MoveParameter(
                entry.getKey(),
                MoveParameterKind.GENERATED,
                entry.getValue(),
                entry.getValue(),
                -1,
                "move-metadata"
            ))
            .toList();
        int occurrence = parseOccurrence(metadata.ordinal());
        MoveOrdinal ordinal = MoveOrdinal.of(kind, occurrence, parameters);
        return RewriteMove.builder(kind)
            .moveId(metadata.moveId().isBlank() ? kind.name() + "|" + canonical(beforeExpression) + "=>" + canonical(
                transformation.transformedExpression()) : metadata.moveId())
            .ruleId(firstNonBlank(metadata.ruleId(), transformation.rule()))
            .operatorId(firstNonBlank(metadata.operatorId(), transformation.rule()))
            .sourceExpression(beforeExpression)
            .targetExpression(transformation.transformedExpression())
            .canonicalBefore(canonical(beforeExpression))
            .canonicalAfter(canonical(transformation.transformedExpression()))
            .ordinal(ordinal)
            .parameters(parameters)
            .assumptions(transformation.assumptions())
            .tags(List.of("countable-move-search", "move-metadata"))
            .build();
    }

    private RewriteMoveKind moveKindFromMoveId(String moveId) {
        if (moveId == null || moveId.isBlank()) {
            return RewriteMoveKind.UNKNOWN;
        }
        int separator = moveId.indexOf('|');
        String prefix = separator < 0 ? moveId : moveId.substring(0, separator);
        try {
            return RewriteMoveKind.valueOf(prefix.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return RewriteMoveKind.UNKNOWN;
        }
    }

    private int parseOccurrence(String ordinalText) {
        if (ordinalText == null || ordinalText.isBlank()) {
            return 0;
        }
        String[] parts = ordinalText.split(":");
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private CountableMoveSearchResult success(
        String input,
        String target,
        List<String> pathExpressions,
        List<RewriteMove> appliedMoves,
        List<String> appliedRuleIds,
        int explored,
        int uniqueCanonicalStateCount
    ) {
        return new CountableMoveSearchResult(
            true,
            input,
            target,
            pathExpressions,
            appliedMoves,
            appliedRuleIds,
            explored,
            uniqueCanonicalStateCount,
            FailureReason.NONE
        );
    }

    private CountableMoveSearchResult failure(
        String input,
        String target,
        List<String> pathExpressions,
        List<RewriteMove> appliedMoves,
        List<String> appliedRuleIds,
        int explored,
        int uniqueCanonicalStateCount,
        FailureReason reason
    ) {
        return new CountableMoveSearchResult(
            false,
            input,
            target,
            pathExpressions,
            appliedMoves,
            appliedRuleIds,
            explored,
            uniqueCanonicalStateCount,
            reason
        );
    }

    private String normalize(String expression) {
        if (expression == null) {
            return "";
        }
        return expression.trim().replaceAll("\\s+", " ");
    }

    private String canonical(String expression) {
        String normalized = normalize(expression);
        if (normalized.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(normalized);
        } catch (RuntimeException exception) {
            scorer.score(normalized);
            return normalized;
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    public enum FailureReason {
        NONE,
        INVALID_INPUT,
        TARGET_NOT_REACHED,
        MAX_STATES_REACHED
    }

    private record SearchNode(
        String expression,
        String canonicalExpression,
        List<String> pathExpressions,
        List<RewriteMove> appliedMoves,
        List<String> appliedRuleIds,
        int depth
    ) {
        SearchNode advance(String nextExpression, String nextCanonical, RewriteMove move, String ruleId) {
            List<String> nextPath = new ArrayList<>(pathExpressions);
            nextPath.add(nextExpression);
            List<RewriteMove> moves = new ArrayList<>(appliedMoves);
            moves.add(move);
            List<String> rules = new ArrayList<>(appliedRuleIds);
            rules.add(ruleId == null ? "" : ruleId);
            return new SearchNode(
                nextExpression,
                nextCanonical,
                List.copyOf(nextPath),
                List.copyOf(moves),
                List.copyOf(rules),
                depth + 1
            );
        }
    }

    public record CountableMoveSearchResult(
        boolean success,
        String inputExpression,
        String targetExpression,
        List<String> pathExpressions,
        List<RewriteMove> appliedMoves,
        List<String> appliedRuleIds,
        int exploredStateCount,
        int uniqueCanonicalStateCount,
        FailureReason failureReason
    ) {
        public CountableMoveSearchResult {
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            pathExpressions = pathExpressions == null ? List.of() : List.copyOf(pathExpressions);
            appliedMoves = appliedMoves == null ? List.of() : List.copyOf(appliedMoves);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
            failureReason = failureReason == null ? FailureReason.NONE : failureReason;
        }

        public int pathLength() {
            return Math.max(0, pathExpressions.size() - 1);
        }
    }
}
