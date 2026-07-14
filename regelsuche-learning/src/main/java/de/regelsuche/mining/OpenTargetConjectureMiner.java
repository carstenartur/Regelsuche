package de.regelsuche.mining;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.learning.ExpressionFingerprint;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Mines first-stage conjectures from real untargeted search convergence.
 *
 * <p>The miner receives no target expression. It uses only explored search states,
 * requires genuinely different convergent paths, and refuses to count pure
 * alpha-renaming as independent support.</p>
 */
public final class OpenTargetConjectureMiner {
    public static final String SCHEMA = "regelsuche.open-target-conjecture-mining/v1";
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("[A-Z]");

    private final PatternGeneralizer generalizer;
    private final ExpressionCanonicalizer canonicalizer;
    private final ExpressionScorer scorer;

    public OpenTargetConjectureMiner() {
        this(new PatternGeneralizer(), new ExpressionCanonicalizer(), new ExpressionScorer());
    }

    OpenTargetConjectureMiner(
        PatternGeneralizer generalizer,
        ExpressionCanonicalizer canonicalizer,
        ExpressionScorer scorer
    ) {
        this.generalizer = Objects.requireNonNull(generalizer, "generalizer");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    public MiningReport mine(List<OpenTargetObservation> observations) {
        List<OpenTargetObservation> ordered = observations == null
            ? List.of()
            : observations.stream()
                .sorted(Comparator.comparing(OpenTargetObservation::observationId))
                .toList();
        List<ConvergenceEvidence> evidence = new ArrayList<>();
        List<RejectedCluster> rejected = new ArrayList<>();
        for (OpenTargetObservation observation : ordered) {
            Optional<ConvergenceEvidence> convergence = bestConvergence(observation);
            if (convergence.isPresent()) {
                evidence.add(convergence.orElseThrow());
            } else {
                rejected.add(new RejectedCluster(
                    "observation:" + observation.observationId(),
                    List.of(observation.observationId()),
                    0,
                    0,
                    "no-independent-equivalence-preserving-convergence"));
            }
        }

        Map<String, List<ConvergenceEvidence>> clusters = evidence.stream()
            .collect(Collectors.groupingBy(
                ConvergenceEvidence::pathCompetitionSignature,
                LinkedHashMap::new,
                Collectors.toList()));
        List<OpenTargetConjecture> conjectures = new ArrayList<>();
        clusters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> mineCluster(entry.getKey(), entry.getValue(), conjectures, rejected));
        return new MiningReport(
            SCHEMA,
            false,
            conjectures.stream()
                .sorted(Comparator.comparing(OpenTargetConjecture::conjectureId))
                .toList(),
            rejected.stream()
                .sorted(Comparator.comparing(RejectedCluster::clusterSignature))
                .toList());
    }

    private void mineCluster(
        String signature,
        List<ConvergenceEvidence> cluster,
        List<OpenTargetConjecture> conjectures,
        List<RejectedCluster> rejected
    ) {
        List<ConvergenceEvidence> ordered = cluster.stream()
            .sorted(Comparator.comparing(ConvergenceEvidence::observationId))
            .toList();
        List<String> observationIds = ordered.stream()
            .map(ConvergenceEvidence::observationId)
            .toList();
        int distinctAlphaSupport = (int) ordered.stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .distinct()
            .count();
        if (ordered.size() < 2) {
            rejected.add(new RejectedCluster(
                signature, observationIds, ordered.size(), distinctAlphaSupport, "support-count<2"));
            return;
        }
        if (distinctAlphaSupport < 2) {
            rejected.add(new RejectedCluster(
                signature,
                observationIds,
                ordered.size(),
                distinctAlphaSupport,
                "alpha-distinct-support<2"));
            return;
        }

        List<SuccessfulTransformationPath> paths = ordered.stream()
            .map(this::toLearningPath)
            .toList();
        Optional<GeneralizedPattern> generalized = generalizer.generalize(paths);
        if (generalized.isEmpty()) {
            rejected.add(new RejectedCluster(
                signature,
                observationIds,
                ordered.size(),
                distinctAlphaSupport,
                "generalizer-returned-no-compatible-pattern"));
            return;
        }
        GeneralizedPattern pattern = generalized.orElseThrow();
        if (!specificEnough(pattern)) {
            rejected.add(new RejectedCluster(
                signature,
                observationIds,
                ordered.size(),
                distinctAlphaSupport,
                "over-broad-or-structure-free-pattern"));
            return;
        }

        Set<String> families = ordered.stream()
            .map(ConvergenceEvidence::family)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(TreeSet::new));
        conjectures.add(new OpenTargetConjecture(
            conjectureId(signature, pattern),
            pattern.leftPattern(),
            pattern.rightPattern(),
            ordered.size(),
            distinctAlphaSupport,
            List.copyOf(families),
            observationIds,
            ordered,
            pattern.parameterRelations(),
            pattern.expressionPlaceholderValues(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS"));
    }

    private Optional<ConvergenceEvidence> bestConvergence(OpenTargetObservation observation) {
        Map<String, List<SearchState>> byCanonicalHash = new LinkedHashMap<>();
        observation.exploredStates().stream()
            .filter(state -> state.depth() > 0)
            .filter(this::eligiblePath)
            .sorted(Comparator
                .comparing(SearchState::canonicalHash)
                .thenComparing(SearchState::expression)
                .thenComparingInt(SearchState::depth)
                .thenComparing(this::rulePathSignature))
            .forEach(state -> byCanonicalHash
                .computeIfAbsent(state.canonicalHash(), ignored -> new ArrayList<>())
                .add(state));

        List<ConvergenceCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<SearchState>> canonicalEntry : byCanonicalHash.entrySet()) {
            Map<String, List<SearchState>> byExactOutput = canonicalEntry.getValue().stream()
                .collect(Collectors.groupingBy(
                    state -> normalize(state.expression()),
                    LinkedHashMap::new,
                    Collectors.toList()));
            for (List<SearchState> outputStates : byExactOutput.values()) {
                addCandidate(observation, canonicalEntry.getKey(), outputStates, candidates);
            }
        }
        return candidates.stream()
            .sorted(Comparator
                .comparingInt((ConvergenceCandidate candidate) -> -candidate.evidence().scoreImprovement())
                .thenComparingInt(ConvergenceCandidate::outputScore)
                .thenComparing(candidate -> candidate.evidence().canonicalOutputHash())
                .thenComparing(candidate -> candidate.evidence().outputExpression()))
            .map(ConvergenceCandidate::evidence)
            .findFirst();
    }

    private void addCandidate(
        OpenTargetObservation observation,
        String canonicalOutputHash,
        List<SearchState> outputStates,
        List<ConvergenceCandidate> candidates
    ) {
        Map<String, SearchState> distinctPaths = new LinkedHashMap<>();
        outputStates.stream()
            .sorted(Comparator.comparingInt(SearchState::depth).thenComparing(this::rulePathSignature))
            .forEach(state -> distinctPaths.putIfAbsent(rulePathSignature(state), state));
        if (distinctPaths.size() < 2) {
            return;
        }
        SearchState representative = distinctPaths.values().stream()
            .min(Comparator
                .comparingInt(SearchState::depth)
                .thenComparingInt(state -> state.score().weightedTotal())
                .thenComparing(this::rulePathSignature))
            .orElseThrow();
        int improvement = scorer.score(observation.rootExpression()).weightedTotal()
            - representative.score().weightedTotal();
        if (improvement <= 0) {
            return;
        }
        List<PathEvidence> paths = distinctPaths.values().stream()
            .map(this::pathEvidence)
            .sorted(Comparator.comparing(PathEvidence::pathId))
            .toList();
        String competition = paths.stream()
            .map(path -> String.join(">", path.ruleIds()))
            .sorted()
            .collect(Collectors.joining("||"));
        ExpressionFingerprint input = ExpressionFingerprint.of(
            observation.rootExpression(), canonicalizer);
        ExpressionFingerprint output = ExpressionFingerprint.of(
            representative.expression(), canonicalizer);
        candidates.add(new ConvergenceCandidate(
            new ConvergenceEvidence(
                observation.observationId(),
                observation.family(),
                observation.searchStatus(),
                observation.rootExpression(),
                representative.expression(),
                canonicalOutputHash,
                improvement,
                input.alphaShapeHash() + "->" + output.alphaShapeHash(),
                input.valueHash() + "->" + output.valueHash(),
                competition,
                paths),
            representative.score().weightedTotal()));
    }

    private boolean eligiblePath(SearchState state) {
        return !state.appliedRuleIds().isEmpty()
            && !state.path().isEmpty()
            && new LinkedHashSet<>(state.path()).size() == state.path().size()
            && !state.equivalencePreservingFlags().isEmpty()
            && state.equivalencePreservingFlags().stream().allMatch(Boolean::booleanValue);
    }

    private String rulePathSignature(SearchState state) {
        return String.join(">", state.appliedRuleIds());
    }

    private PathEvidence pathEvidence(SearchState state) {
        String material = String.join("\u0001", state.path())
            + "\u0002" + String.join("\u0001", state.appliedRuleIds());
        return new PathEvidence(
            "path-" + sha256(material).substring(0, 16),
            state.path(),
            state.appliedRuleIds(),
            state.assumptions(),
            state.depth(),
            state.score().weightedTotal());
    }

    private SuccessfulTransformationPath toLearningPath(ConvergenceEvidence evidence) {
        PathEvidence representative = evidence.paths().stream()
            .min(Comparator
                .comparingInt(PathEvidence::depth)
                .thenComparing(PathEvidence::pathId))
            .orElseThrow();
        if (!normalize(representative.expressions().getLast())
                .equals(normalize(evidence.outputExpression()))) {
            throw new IllegalStateException("convergence path does not end at its reported output");
        }
        return new SuccessfulTransformationPath(
            "open-target-" + evidence.observationId(),
            evidence.inputExpression(),
            evidence.outputExpression(),
            representative.expressions(),
            representative.ruleIds(),
            scorer.score(evidence.inputExpression()),
            scorer.score(evidence.outputExpression()),
            true,
            "EQUIVALENCE_PRESERVING_PATH_CONVERGENCE",
            Map.of("source", "UNTARGETED_SEARCH"),
            representative.assumptions());
    }

    private boolean specificEnough(GeneralizedPattern pattern) {
        String left = pattern.leftPattern().trim();
        String right = pattern.rightPattern().trim();
        if (left.isBlank() || right.isBlank() || left.equals(right)) {
            return false;
        }
        if (SINGLE_PLACEHOLDER.matcher(left).matches()
                && SINGLE_PLACEHOLDER.matcher(right).matches()) {
            return false;
        }
        return containsStructure(left) || containsStructure(right);
    }

    private boolean containsStructure(String pattern) {
        return pattern.indexOf('+') >= 0
            || pattern.indexOf('-') >= 0
            || pattern.indexOf('*') >= 0
            || pattern.indexOf('/') >= 0
            || pattern.indexOf('^') >= 0
            || pattern.indexOf('(') >= 0;
    }

    private String conjectureId(String signature, GeneralizedPattern pattern) {
        String material = signature + "\n" + pattern.leftPattern() + "->" + pattern.rightPattern();
        return "open-target-conjecture-" + sha256(material).substring(0, 20);
    }

    private static String normalize(String expression) {
        return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record OpenTargetObservation(
        String observationId,
        String family,
        String rootExpression,
        GoalStatus searchStatus,
        List<SearchState> exploredStates
    ) {
        public OpenTargetObservation {
            if (observationId == null || observationId.isBlank()) {
                throw new IllegalArgumentException("observationId must not be blank");
            }
            if (rootExpression == null || rootExpression.isBlank()) {
                throw new IllegalArgumentException("rootExpression must not be blank");
            }
            if (searchStatus != GoalStatus.UNTARGETED) {
                throw new IllegalArgumentException(
                    "open-target observations require GoalStatus.UNTARGETED");
            }
            family = family == null ? "" : family;
            exploredStates = exploredStates == null ? List.of() : List.copyOf(exploredStates);
        }

        public static OpenTargetObservation from(
            String observationId,
            String postHocFamily,
            String rootExpression,
            GoalSearchResult result
        ) {
            Objects.requireNonNull(result, "result");
            return new OpenTargetObservation(
                observationId,
                postHocFamily,
                rootExpression,
                result.status(),
                result.states());
        }
    }

    public record MiningReport(
        String schema,
        boolean targetProvided,
        List<OpenTargetConjecture> conjectures,
        List<RejectedCluster> rejectedClusters
    ) {
        public MiningReport {
            conjectures = conjectures == null ? List.of() : List.copyOf(conjectures);
            rejectedClusters = rejectedClusters == null ? List.of() : List.copyOf(rejectedClusters);
        }
    }

    public record OpenTargetConjecture(
        String conjectureId,
        String leftPattern,
        String rightPattern,
        int supportCount,
        int distinctAlphaSupport,
        List<String> postHocFamilies,
        List<String> supportingObservationIds,
        List<ConvergenceEvidence> evidence,
        List<String> parameterRelations,
        Map<String, List<String>> expressionPlaceholderValues,
        String candidateStatus,
        String evidenceStatus
    ) {
        public OpenTargetConjecture {
            postHocFamilies = postHocFamilies == null ? List.of() : List.copyOf(postHocFamilies);
            supportingObservationIds = supportingObservationIds == null
                ? List.of()
                : List.copyOf(supportingObservationIds);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            parameterRelations = parameterRelations == null ? List.of() : List.copyOf(parameterRelations);
            expressionPlaceholderValues = expressionPlaceholderValues == null
                ? Map.of()
                : Map.copyOf(expressionPlaceholderValues);
        }
    }

    public record ConvergenceEvidence(
        String observationId,
        String family,
        GoalStatus searchStatus,
        String inputExpression,
        String outputExpression,
        String canonicalOutputHash,
        int scoreImprovement,
        String alphaPairFingerprint,
        String valuePairFingerprint,
        String pathCompetitionSignature,
        List<PathEvidence> paths
    ) {
        public ConvergenceEvidence {
            family = family == null ? "" : family;
            if (searchStatus != GoalStatus.UNTARGETED) {
                throw new IllegalArgumentException("convergence evidence must come from untargeted search");
            }
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    public record PathEvidence(
        String pathId,
        List<String> expressions,
        List<String> ruleIds,
        List<String> assumptions,
        int depth,
        int finalScore
    ) {
        public PathEvidence {
            expressions = expressions == null ? List.of() : List.copyOf(expressions);
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }
    }

    public record RejectedCluster(
        String clusterSignature,
        List<String> observationIds,
        int supportCount,
        int distinctAlphaSupport,
        String reason
    ) {
        public RejectedCluster {
            observationIds = observationIds == null ? List.of() : List.copyOf(observationIds);
            reason = reason == null ? "" : reason;
        }
    }

    private record ConvergenceCandidate(ConvergenceEvidence evidence, int outputScore) {
    }
}