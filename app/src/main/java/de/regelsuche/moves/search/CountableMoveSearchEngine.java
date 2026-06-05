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
        return search(inputExpression, targetExpression, SearchConfiguration.fromLegacyBounds(maxDepth, maxStates));
    }

    public CountableMoveSearchResult search(
        String inputExpression,
        String targetExpression,
        SearchConfiguration searchConfiguration
    ) {
        String input = normalize(inputExpression);
        String target = normalize(targetExpression);
        SearchConfiguration configuration = searchConfiguration == null
            ? SearchConfiguration.defaults()
            : searchConfiguration;
        MoveSearchOptions options = configuration.moveSearchOptions();
        MetricsAccumulator metrics = new MetricsAccumulator();
        if (input.isBlank() || target.isBlank()) {
            return failure(
                input,
                target,
                List.of(input),
                List.of(),
                List.of(),
                FailureReason.INVALID_INPUT,
                metrics
            );
        }

        int depthLimit = Math.max(MIN_DEPTH, options.effectiveDepthLimit());
        int stateBudget = options.maxStates();
        int moveBudgetPerNode = options.maxGeneratedMovesPerNode();
        String canonicalTarget = canonical(target);

        SearchNode root = new SearchNode(input, canonical(input), List.of(input), List.of(), List.of(), 0);
        LinkedHashSet<String> uniqueCanonicals = new LinkedHashSet<>();
        uniqueCanonicals.add(root.canonicalExpression());
        metrics.recordUniqueState();
        if (root.canonicalExpression().equals(canonicalTarget)) {
            metrics.recordExploredState();
            return success(
                input,
                target,
                root.pathExpressions(),
                root.appliedMoves(),
                root.appliedRuleIds(),
                metrics
            );
        }

        ArrayDeque<SearchNode> frontier = new ArrayDeque<>();
        frontier.add(root);

        while (!frontier.isEmpty()) {
            if (metrics.exploredStateCount() >= stateBudget) {
                metrics.recordStateBudgetPruned(frontier.size());
                return failure(
                    input,
                    target,
                    List.of(input),
                    List.of(),
                    List.of(),
                    FailureReason.MAX_STATES_REACHED,
                    metrics
                );
            }
            SearchNode current = frontier.removeFirst();
            metrics.recordExploredState();
            if (current.depth() >= depthLimit) {
                metrics.recordDepthPruned();
                continue;
            }

            List<Transformation> transformations = new ArrayList<>(transformationEngine.transform(current.expression()));
            transformations.sort(Comparator.comparing(Transformation::rule)
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(Transformation::applicationKey));
            if (transformations.size() > moveBudgetPerNode) {
                transformations = new ArrayList<>(transformations.subList(0, moveBudgetPerNode));
            }

            metrics.beginNodeExpansion(current.depth());
            for (Transformation transformation : transformations) {
                String nextExpression = normalize(transformation.transformedExpression());
                if (nextExpression.equals(current.expression())) {
                    continue;
                }
                String nextCanonical = canonical(nextExpression);
                RewriteMove move = rewriteMoveFor(current.expression(), transformation);
                metrics.recordGeneratedMove(current.depth(), move);
                if (!uniqueCanonicals.add(nextCanonical)) {
                    metrics.recordDuplicateState();
                    continue;
                }
                metrics.recordUniqueState();

                SearchNode next = current.advance(nextExpression, nextCanonical, move, transformation.rule());
                if (nextCanonical.equals(canonicalTarget)) {
                    return success(
                        input,
                        target,
                        next.pathExpressions(),
                        next.appliedMoves(),
                        next.appliedRuleIds(),
                        metrics
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
            FailureReason.TARGET_NOT_REACHED,
            metrics
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
        MetricsAccumulator metrics
    ) {
        SearchSpaceMetrics searchSpaceMetrics = metrics.build(appliedMoves);
        return new CountableMoveSearchResult(
            true,
            input,
            target,
            pathExpressions,
            appliedMoves,
            appliedRuleIds,
            searchSpaceMetrics.exploredStateCount(),
            searchSpaceMetrics.uniqueCanonicalStateCount(),
            FailureReason.NONE,
            searchSpaceMetrics
        );
    }

    private CountableMoveSearchResult failure(
        String input,
        String target,
        List<String> pathExpressions,
        List<RewriteMove> appliedMoves,
        List<String> appliedRuleIds,
        FailureReason reason,
        MetricsAccumulator metrics
    ) {
        SearchSpaceMetrics searchSpaceMetrics = metrics.build(appliedMoves);
        return new CountableMoveSearchResult(
            false,
            input,
            target,
            pathExpressions,
            appliedMoves,
            appliedRuleIds,
            searchSpaceMetrics.exploredStateCount(),
            searchSpaceMetrics.uniqueCanonicalStateCount(),
            reason,
            searchSpaceMetrics
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
        FailureReason failureReason,
        SearchSpaceMetrics searchSpaceMetrics
    ) {
        public CountableMoveSearchResult {
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            pathExpressions = pathExpressions == null ? List.of() : List.copyOf(pathExpressions);
            appliedMoves = appliedMoves == null ? List.of() : List.copyOf(appliedMoves);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
            failureReason = failureReason == null ? FailureReason.NONE : failureReason;
            searchSpaceMetrics = searchSpaceMetrics == null ? SearchSpaceMetrics.empty() : searchSpaceMetrics;
        }

        public int pathLength() {
            return Math.max(0, pathExpressions.size() - 1);
        }
    }

    /** Per-depth branching factor: how many moves a node at this depth produced on average. */
    public record DepthBranchingFactor(int depth, int expandedNodeCount, int generatedMoveCount, double branchingFactor) {
    }

    /**
     * Search Space Intelligence metrics for a single bounded search run. Captures how the search
     * space grew and which move kinds/enumerators were useful (Issue #103).
     */
    public record SearchSpaceMetrics(
        int exploredStateCount,
        int uniqueCanonicalStateCount,
        int generatedMoveCount,
        int duplicateStateCount,
        int prunedByDepthCount,
        int prunedByStateBudgetCount,
        List<DepthBranchingFactor> branchingFactorByDepth,
        Map<String, Integer> moveKindHistogram,
        Map<String, Integer> enumeratorHistogram,
        List<String> successfulPathMoveKinds,
        int classicFallbackMoveCount,
        int unknownMoveCount,
        int unresolvedParameterMoveCount
    ) {
        public SearchSpaceMetrics {
            branchingFactorByDepth = branchingFactorByDepth == null ? List.of() : List.copyOf(branchingFactorByDepth);
            moveKindHistogram = moveKindHistogram == null ? Map.of() : Map.copyOf(moveKindHistogram);
            enumeratorHistogram = enumeratorHistogram == null ? Map.of() : Map.copyOf(enumeratorHistogram);
            successfulPathMoveKinds = successfulPathMoveKinds == null ? List.of() : List.copyOf(successfulPathMoveKinds);
        }

        public static SearchSpaceMetrics empty() {
            return new SearchSpaceMetrics(0, 0, 0, 0, 0, 0, List.of(), Map.of(), Map.of(), List.of(), 0, 0, 0);
        }
    }

    /** Tag identifying moves produced by the classic fallback derivation. */
    static final String CLASSIC_FALLBACK_TAG = "classic-fallback";
    /** Tag identifying moves produced from move-enumerator metadata. */
    static final String MOVE_METADATA_TAG = "move-metadata";

    /** Mutable, deterministic accumulator for {@link SearchSpaceMetrics}. */
    private static final class MetricsAccumulator {
        private int exploredStateCount;
        private int uniqueCanonicalStateCount;
        private int generatedMoveCount;
        private int duplicateStateCount;
        private int prunedByDepthCount;
        private int prunedByStateBudgetCount;
        private int classicFallbackMoveCount;
        private int unknownMoveCount;
        private int unresolvedParameterMoveCount;
        private final java.util.TreeMap<Integer, int[]> branchingByDepth = new java.util.TreeMap<>();
        private final java.util.TreeMap<String, Integer> moveKindHistogram = new java.util.TreeMap<>();
        private final java.util.TreeMap<String, Integer> enumeratorHistogram = new java.util.TreeMap<>();

        int exploredStateCount() {
            return exploredStateCount;
        }

        void recordExploredState() {
            exploredStateCount++;
        }

        void recordUniqueState() {
            uniqueCanonicalStateCount++;
        }

        void recordDuplicateState() {
            duplicateStateCount++;
        }

        void recordDepthPruned() {
            prunedByDepthCount++;
        }

        void recordStateBudgetPruned(int remainingFrontier) {
            prunedByStateBudgetCount += Math.max(0, remainingFrontier);
        }

        void beginNodeExpansion(int depth) {
            branchingByDepth.computeIfAbsent(depth, key -> new int[2])[0]++;
        }

        void recordGeneratedMove(int depth, RewriteMove move) {
            generatedMoveCount++;
            branchingByDepth.computeIfAbsent(depth, key -> new int[2])[1]++;
            String kind = move.kind() == null ? RewriteMoveKind.UNKNOWN.name() : move.kind().name();
            moveKindHistogram.merge(kind, 1, Integer::sum);
            enumeratorHistogram.merge(enumeratorOf(move), 1, Integer::sum);
            if (move.kind() == RewriteMoveKind.UNKNOWN) {
                unknownMoveCount++;
            }
            if (move.hasUnresolvedParameters()) {
                unresolvedParameterMoveCount++;
            }
            if (move.tags().contains(CLASSIC_FALLBACK_TAG)) {
                classicFallbackMoveCount++;
            }
        }

        private String enumeratorOf(RewriteMove move) {
            if (move.tags().contains(CLASSIC_FALLBACK_TAG)) {
                return CLASSIC_FALLBACK_TAG;
            }
            if (move.tags().contains(MOVE_METADATA_TAG)) {
                return MOVE_METADATA_TAG;
            }
            return "unclassified";
        }

        SearchSpaceMetrics build(List<RewriteMove> successfulPathMoves) {
            List<DepthBranchingFactor> branching = new ArrayList<>();
            for (Map.Entry<Integer, int[]> entry : branchingByDepth.entrySet()) {
                int expandedNodes = entry.getValue()[0];
                int generated = entry.getValue()[1];
                double factor = expandedNodes == 0 ? 0.0 : (double) generated / expandedNodes;
                branching.add(new DepthBranchingFactor(entry.getKey(), expandedNodes, generated, factor));
            }
            List<String> successfulKinds = successfulPathMoves == null ? List.of() : successfulPathMoves.stream()
                .map(move -> move.kind() == null ? RewriteMoveKind.UNKNOWN.name() : move.kind().name())
                .toList();
            return new SearchSpaceMetrics(
                exploredStateCount,
                uniqueCanonicalStateCount,
                generatedMoveCount,
                duplicateStateCount,
                prunedByDepthCount,
                prunedByStateBudgetCount,
                List.copyOf(branching),
                new java.util.LinkedHashMap<>(moveKindHistogram),
                new java.util.LinkedHashMap<>(enumeratorHistogram),
                successfulKinds,
                classicFallbackMoveCount,
                unknownMoveCount,
                unresolvedParameterMoveCount
            );
        }
    }
}
