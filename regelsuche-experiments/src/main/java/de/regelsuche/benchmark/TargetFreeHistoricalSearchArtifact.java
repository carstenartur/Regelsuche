package de.regelsuche.benchmark;

import static de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus.UNTARGETED;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Freezes a target-free historical-search comparison before any post-hoc
 * correspondence to known mathematics is evaluated.
 *
 * <p>The artifact retains every explored state, the complete retained path and
 * rule lineage of each state, all search counters, both operator inventories
 * and the common admitted-work budget in one canonical content-addressed
 * payload. It is diagnostic run evidence, not mathematical proof, novelty or
 * a general search-superiority claim.</p>
 */
public final class TargetFreeHistoricalSearchArtifact {
    public static final String SCHEMA =
        "regelsuche.target-free-historical-search-comparison/v1";
    public static final String FILE_NAME =
        "target-free-historical-search-comparison.json";
    public static final String CLAIM_BOUNDARY =
        "Target-free search formation, complete retained explored-state "
            + "evidence and information-parity comparison for one frozen "
            + "study; not proof, external novelty, global reachability or "
            + "unbounded search superiority.";

    private static final long MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L;
    private static final Pattern STUDY_ID = Pattern.compile(
        "[a-z0-9][a-z0-9._-]{2,127}");
    private static final Pattern SHA256 = Pattern.compile(
        "sha256:[0-9a-f]{64}");

    private TargetFreeHistoricalSearchArtifact() {
    }

    public static Comparison freeze(
        String studyId,
        String sourceExpression,
        TransformationGoal objective,
        SearchHeuristic heuristic,
        String frozenLearnedRuleId,
        RunInput baseline,
        RunInput accumulated
    ) {
        String normalizedStudyId = requireStudyId(studyId);
        String source = requireText(sourceExpression, "sourceExpression");
        String learnedRuleId = requireText(
            frozenLearnedRuleId,
            "frozenLearnedRuleId");
        Budget budget = Budget.from(Objects.requireNonNull(
            heuristic,
            "heuristic"));
        FrozenRun frozenBaseline = FrozenRun.from(
            "baseline",
            Objects.requireNonNull(baseline, "baseline"));
        FrozenRun frozenAccumulated = FrozenRun.from(
            "accumulated",
            Objects.requireNonNull(accumulated, "accumulated"));
        String objectiveId = Objects.requireNonNull(
            objective,
            "objective").name();
        String payload = payloadJson(
            normalizedStudyId,
            source,
            objectiveId,
            budget,
            learnedRuleId,
            frozenBaseline,
            frozenAccumulated,
            CLAIM_BOUNDARY);
        return new Comparison(
            SCHEMA,
            normalizedStudyId,
            source,
            objectiveId,
            "BEST_FIRST",
            budget,
            learnedRuleId,
            frozenBaseline,
            frozenAccumulated,
            CLAIM_BOUNDARY,
            sha256(payload));
    }

    public static VerifiedComparison write(
        Path outputDirectory,
        Comparison comparison
    ) throws IOException {
        Path directory = Objects.requireNonNull(
            outputDirectory,
            "outputDirectory").toAbsolutePath().normalize();
        rejectSymbolicAncestry(directory);
        Files.createDirectories(directory);
        rejectSymbolicAncestry(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException(
                "target-free search output must be a regular directory");
        }
        Path path = directory.resolve(FILE_NAME);
        String expected = Objects.requireNonNull(
            comparison,
            "comparison").toCanonicalJson() + "\n";
        if (expected.getBytes(StandardCharsets.UTF_8).length
                > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                "target-free search artifact exceeds "
                    + MAX_ARTIFACT_BYTES + " bytes");
        }
        AtomicJsonFile.writeUtf8(path, expected);
        return verify(path, comparison);
    }

    public static VerifiedComparison verify(
        Path artifactPath,
        Comparison expectedComparison
    ) throws IOException {
        Path path = Objects.requireNonNull(
            artifactPath,
            "artifactPath").toAbsolutePath().normalize();
        rejectSymbolicAncestry(path);
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "target-free search artifact must be a regular file");
        }
        long declaredLength = Files.size(path);
        if (declaredLength < 1L || declaredLength > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                "target-free search artifact length is outside the "
                    + "bounded range: " + declaredLength);
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != declaredLength) {
            throw new IllegalArgumentException(
                "target-free search artifact changed while being read");
        }
        String expected = Objects.requireNonNull(
            expectedComparison,
            "expectedComparison").toCanonicalJson() + "\n";
        String actual = new String(bytes, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "target-free search artifact differs from frozen comparison");
        }
        return new VerifiedComparison(
            path,
            declaredLength,
            sha256(actual),
            expectedComparison);
    }

    public record RunInput(
        List<String> operatorInventory,
        GoalSearchResult result
    ) {
        public RunInput {
            operatorInventory = requireInventory(operatorInventory);
            result = Objects.requireNonNull(result, "result");
            if (result.status() != UNTARGETED
                    || result.reachedState() != null
                    || result.reached()) {
                throw new IllegalArgumentException(
                    "target-free artifact accepts only untargeted search "
                        + "results without a reached state");
            }
        }
    }

    public record Comparison(
        String schema,
        String studyId,
        String sourceExpression,
        String objective,
        String strategy,
        Budget budget,
        String frozenLearnedRuleId,
        FrozenRun baseline,
        FrozenRun accumulated,
        String claimBoundary,
        String contentHash
    ) {
        public Comparison {
            schema = requireText(schema, "schema");
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported target-free search artifact schema: "
                        + schema);
            }
            studyId = requireStudyId(studyId);
            sourceExpression = requireText(
                sourceExpression,
                "sourceExpression");
            objective = requireText(objective, "objective");
            strategy = requireText(strategy, "strategy");
            if (!"BEST_FIRST".equals(strategy)) {
                throw new IllegalArgumentException(
                    "unsupported target-free search strategy: " + strategy);
            }
            budget = Objects.requireNonNull(budget, "budget");
            frozenLearnedRuleId = requireText(
                frozenLearnedRuleId,
                "frozenLearnedRuleId");
            baseline = Objects.requireNonNull(baseline, "baseline");
            accumulated = Objects.requireNonNull(
                accumulated,
                "accumulated");
            if (!"baseline".equals(baseline.label())
                    || !"accumulated".equals(accumulated.label())) {
                throw new IllegalArgumentException(
                    "comparison run labels must be baseline and accumulated");
            }
            claimBoundary = requireText(claimBoundary, "claimBoundary");
            if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException(
                    "unsupported target-free search claim boundary");
            }
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = sha256(payloadJson(
                studyId,
                sourceExpression,
                objective,
                budget,
                frozenLearnedRuleId,
                baseline,
                accumulated,
                claimBoundary));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "target-free search content hash differs");
            }
        }

        public String toCanonicalJson() {
            return canonicalJson(
                studyId,
                sourceExpression,
                objective,
                budget,
                frozenLearnedRuleId,
                baseline,
                accumulated,
                claimBoundary,
                contentHash);
        }
    }

    public record FrozenRun(
        String label,
        List<String> operatorInventory,
        String terminalStatus,
        int bestDistance,
        String bestStateFingerprint,
        Metrics metrics,
        List<FrozenState> states
    ) {
        public FrozenRun {
            label = requireText(label, "label");
            operatorInventory = requireInventory(operatorInventory);
            terminalStatus = requireText(
                terminalStatus,
                "terminalStatus");
            if (!UNTARGETED.name().equals(terminalStatus)) {
                throw new IllegalArgumentException(
                    "frozen run must be untargeted");
            }
            bestStateFingerprint = requireSha256(
                bestStateFingerprint,
                "bestStateFingerprint");
            metrics = Objects.requireNonNull(metrics, "metrics");
            states = List.copyOf(Objects.requireNonNull(states, "states"));
            if (states.isEmpty()) {
                throw new IllegalArgumentException(
                    "frozen run must retain at least the root state");
            }
            if (metrics.exploredStates() != states.size()) {
                throw new IllegalArgumentException(
                    "frozen state count differs from search metrics");
            }
            if (states.stream().noneMatch(state ->
                    state.fingerprint().equals(bestStateFingerprint))) {
                throw new IllegalArgumentException(
                    "best state is absent from retained states");
            }
            for (int index = 0; index < states.size(); index++) {
                if (states.get(index).ordinal() != index) {
                    throw new IllegalArgumentException(
                        "frozen state ordinals must be contiguous");
                }
            }
        }

        private static FrozenRun from(String label, RunInput input) {
            GoalSearchResult result = input.result();
            List<FrozenState> states = new ArrayList<>(
                result.states().size());
            for (int index = 0; index < result.states().size(); index++) {
                states.add(FrozenState.from(
                    index,
                    result.states().get(index)));
            }
            return new FrozenRun(
                label,
                input.operatorInventory(),
                result.status().name(),
                result.bestDistance(),
                stateFingerprint(result.bestState()),
                Metrics.from(result.metrics()),
                states);
        }

        private void writeJsonValue(JsonWriter writer) {
            writer.objectValue(object -> object
                .property("label", label)
                .stringArray("operatorInventory", operatorInventory)
                .property("terminalStatus", terminalStatus)
                .property("bestDistance", bestDistance)
                .property("bestStateFingerprint", bestStateFingerprint)
                .object("metrics", metrics::writeJson)
                .array("states", statesWriter ->
                    states.forEach(state ->
                        state.writeJsonValue(statesWriter))));
        }
    }

    public record FrozenState(
        int ordinal,
        String expression,
        int depth,
        Score score,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> appliedRuleApplications,
        int expandedStepCount,
        String canonicalHash,
        String parentExpression,
        String appliedRuleId,
        String appliedRuleKind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        int improvement,
        List<String> appliedRuleKinds,
        List<Boolean> equivalencePreservingFlags,
        List<String> assumptions,
        String fingerprint
    ) {
        public FrozenState {
            if (ordinal < 0 || depth < 0 || expandedStepCount < 0) {
                throw new IllegalArgumentException(
                    "frozen state counters must be non-negative");
            }
            expression = requireText(expression, "expression");
            score = Objects.requireNonNull(score, "score");
            path = requireTextList(path, "path", false);
            appliedRuleIds = requireTextList(
                appliedRuleIds,
                "appliedRuleIds",
                true);
            appliedRuleApplications = requireTextList(
                appliedRuleApplications,
                "appliedRuleApplications",
                true);
            canonicalHash = requireText(canonicalHash, "canonicalHash");
            parentExpression = parentExpression == null
                ? ""
                : parentExpression;
            appliedRuleId = appliedRuleId == null ? "" : appliedRuleId;
            appliedRuleKind = requireText(
                appliedRuleKind,
                "appliedRuleKind");
            appliedRuleKinds = requireTextList(
                appliedRuleKinds,
                "appliedRuleKinds",
                true);
            equivalencePreservingFlags = List.copyOf(
                Objects.requireNonNull(
                    equivalencePreservingFlags,
                    "equivalencePreservingFlags"));
            assumptions = requireTextList(
                assumptions,
                "assumptions",
                true);
            fingerprint = requireSha256(fingerprint, "fingerprint");
            if (path.size() != depth + 1
                    || appliedRuleIds.size() != depth
                    || appliedRuleKinds.size() != depth
                    || equivalencePreservingFlags.size() != depth) {
                throw new IllegalArgumentException(
                    "frozen state path and lineage lengths differ from depth");
            }
            String expected = sha256(statePayloadJson(
                expression,
                depth,
                score,
                path,
                appliedRuleIds,
                appliedRuleApplications,
                expandedStepCount,
                canonicalHash,
                parentExpression,
                appliedRuleId,
                appliedRuleKind,
                mayIncreaseComplexity,
                estimatedCostDelta,
                equivalencePreservingByConstruction,
                improvement,
                appliedRuleKinds,
                equivalencePreservingFlags,
                assumptions));
            if (!expected.equals(fingerprint)) {
                throw new IllegalArgumentException(
                    "frozen state fingerprint differs");
            }
        }

        private static FrozenState from(int ordinal, SearchState state) {
            Objects.requireNonNull(state, "state");
            Score score = Score.from(state.score());
            List<String> applications = state.appliedRuleApplications()
                .stream().sorted().toList();
            List<String> kinds = state.appliedRuleKinds().stream()
                .map(Enum::name).toList();
            String parent = state.parentExpression() == null
                ? ""
                : state.parentExpression();
            String ruleId = state.appliedRuleId() == null
                ? ""
                : state.appliedRuleId();
            String kind = state.appliedRuleKind().name();
            String fingerprint = sha256(statePayloadJson(
                state.expression(),
                state.depth(),
                score,
                state.path(),
                state.appliedRuleIds(),
                applications,
                state.expandedStepCount(),
                state.canonicalHash(),
                parent,
                ruleId,
                kind,
                state.mayIncreaseComplexity(),
                state.estimatedCostDelta(),
                state.equivalencePreservingByConstruction(),
                state.improvement(),
                kinds,
                state.equivalencePreservingFlags(),
                state.assumptions()));
            return new FrozenState(
                ordinal,
                state.expression(),
                state.depth(),
                score,
                state.path(),
                state.appliedRuleIds(),
                applications,
                state.expandedStepCount(),
                state.canonicalHash(),
                parent,
                ruleId,
                kind,
                state.mayIncreaseComplexity(),
                state.estimatedCostDelta(),
                state.equivalencePreservingByConstruction(),
                state.improvement(),
                kinds,
                state.equivalencePreservingFlags(),
                state.assumptions(),
                fingerprint);
        }

        private void writeJsonValue(JsonWriter writer) {
            writer.objectValue(object -> writeStateFields(
                object,
                ordinal,
                expression,
                depth,
                score,
                path,
                appliedRuleIds,
                appliedRuleApplications,
                expandedStepCount,
                canonicalHash,
                parentExpression,
                appliedRuleId,
                appliedRuleKind,
                mayIncreaseComplexity,
                estimatedCostDelta,
                equivalencePreservingByConstruction,
                improvement,
                appliedRuleKinds,
                equivalencePreservingFlags,
                assumptions).property("fingerprint", fingerprint));
        }
    }

    public record Budget(
        int maxDepth,
        int maxVisitedExpressions,
        int significantImprovementThreshold,
        int maxExpandingSteps,
        int maxCandidatesPerState,
        int beamWidth
    ) {
        private static Budget from(SearchHeuristic heuristic) {
            return new Budget(
                heuristic.maxDepth(),
                heuristic.maxVisitedExpressions(),
                heuristic.significantImprovementThreshold(),
                heuristic.maxExpandingSteps(),
                heuristic.maxCandidatesPerState(),
                heuristic.beamWidth());
        }

        private void writeJson(JsonWriter writer) {
            writer.property("maxDepth", maxDepth)
                .property("maxVisitedExpressions", maxVisitedExpressions)
                .property(
                    "significantImprovementThreshold",
                    significantImprovementThreshold)
                .property("maxExpandingSteps", maxExpandingSteps)
                .property("maxCandidatesPerState", maxCandidatesPerState)
                .property("beamWidth", beamWidth);
        }
    }

    public record Score(
        int stringLength,
        int astNodeCount,
        int operatorCount,
        int nestingDepth,
        int recognizedPatternBonus,
        int weightedTotal
    ) {
        private static Score from(ExpressionScore score) {
            Objects.requireNonNull(score, "score");
            return new Score(
                score.stringLength(),
                score.astNodeCount(),
                score.operatorCount(),
                score.nestingDepth(),
                score.recognizedPatternBonus(),
                score.weightedTotal());
        }

        private void writeJson(JsonWriter writer) {
            writer.property("stringLength", stringLength)
                .property("astNodeCount", astNodeCount)
                .property("operatorCount", operatorCount)
                .property("nestingDepth", nestingDepth)
                .property("recognizedPatternBonus", recognizedPatternBonus)
                .property("weightedTotal", weightedTotal);
        }
    }

    public record Metrics(
        int exploredStates,
        int expandedStates,
        int generatedTransformations,
        int enqueuedStates,
        int skippedTransformations,
        int duplicatePrunes,
        int transpositionPrunes,
        int depthPrunes,
        int candidateBudgetPrunes,
        int statesWithoutTransformations,
        int identityCacheHits,
        int identityCacheMisses,
        int cachedExpressions,
        int internedValues
    ) {
        private static Metrics from(GoalMetrics metrics) {
            Objects.requireNonNull(metrics, "metrics");
            return new Metrics(
                metrics.exploredStates(),
                metrics.expandedStates(),
                metrics.generatedTransformations(),
                metrics.enqueuedStates(),
                metrics.skippedTransformations(),
                metrics.duplicatePrunes(),
                metrics.transpositionPrunes(),
                metrics.depthPrunes(),
                metrics.candidateBudgetPrunes(),
                metrics.statesWithoutTransformations(),
                metrics.identityCacheHits(),
                metrics.identityCacheMisses(),
                metrics.cachedExpressions(),
                metrics.internedValues());
        }

        private void writeJson(JsonWriter writer) {
            writer.property("exploredStates", exploredStates)
                .property("expandedStates", expandedStates)
                .property(
                    "generatedTransformations",
                    generatedTransformations)
                .property("enqueuedStates", enqueuedStates)
                .property("skippedTransformations", skippedTransformations)
                .property("duplicatePrunes", duplicatePrunes)
                .property("transpositionPrunes", transpositionPrunes)
                .property("depthPrunes", depthPrunes)
                .property("candidateBudgetPrunes", candidateBudgetPrunes)
                .property(
                    "statesWithoutTransformations",
                    statesWithoutTransformations)
                .property("identityCacheHits", identityCacheHits)
                .property("identityCacheMisses", identityCacheMisses)
                .property("cachedExpressions", cachedExpressions)
                .property("internedValues", internedValues);
        }
    }

    public record VerifiedComparison(
        Path artifactPath,
        long byteLength,
        String byteHash,
        Comparison comparison
    ) {
        public VerifiedComparison {
            artifactPath = Objects.requireNonNull(
                artifactPath,
                "artifactPath").toAbsolutePath().normalize();
            if (byteLength < 1L || byteLength > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException(
                    "verified artifact byte length is outside bounds");
            }
            byteHash = requireSha256(byteHash, "byteHash");
            comparison = Objects.requireNonNull(comparison, "comparison");
        }
    }

    private static String canonicalJson(
        String studyId,
        String sourceExpression,
        String objective,
        Budget budget,
        String frozenLearnedRuleId,
        FrozenRun baseline,
        FrozenRun accumulated,
        String claimBoundary,
        String contentHash
    ) {
        JsonWriter writer = rootWriter(
            studyId,
            sourceExpression,
            objective,
            budget,
            frozenLearnedRuleId,
            baseline,
            accumulated,
            claimBoundary);
        writer.property("contentHash", contentHash).endObject();
        return writer.toString();
    }

    private static String payloadJson(
        String studyId,
        String sourceExpression,
        String objective,
        Budget budget,
        String frozenLearnedRuleId,
        FrozenRun baseline,
        FrozenRun accumulated,
        String claimBoundary
    ) {
        return rootWriter(
            studyId,
            sourceExpression,
            objective,
            budget,
            frozenLearnedRuleId,
            baseline,
            accumulated,
            claimBoundary).endObject().toString();
    }

    private static JsonWriter rootWriter(
        String studyId,
        String sourceExpression,
        String objective,
        Budget budget,
        String frozenLearnedRuleId,
        FrozenRun baseline,
        FrozenRun accumulated,
        String claimBoundary
    ) {
        return new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("sourceExpression", sourceExpression)
            .property("objective", objective)
            .property("strategy", "BEST_FIRST")
            .object("budget", budget::writeJson)
            .property("frozenLearnedRuleId", frozenLearnedRuleId)
            .array("runs", runs -> {
                baseline.writeJsonValue(runs);
                accumulated.writeJsonValue(runs);
            })
            .property("claimBoundary", claimBoundary);
    }

    private static String statePayloadJson(
        String expression,
        int depth,
        Score score,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> appliedRuleApplications,
        int expandedStepCount,
        String canonicalHash,
        String parentExpression,
        String appliedRuleId,
        String appliedRuleKind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        int improvement,
        List<String> appliedRuleKinds,
        List<Boolean> equivalencePreservingFlags,
        List<String> assumptions
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeStateFields(
            writer,
            -1,
            expression,
            depth,
            score,
            path,
            appliedRuleIds,
            appliedRuleApplications,
            expandedStepCount,
            canonicalHash,
            parentExpression,
            appliedRuleId,
            appliedRuleKind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            improvement,
            appliedRuleKinds,
            equivalencePreservingFlags,
            assumptions);
        return writer.endObject().toString();
    }

    private static JsonWriter writeStateFields(
        JsonWriter writer,
        int ordinal,
        String expression,
        int depth,
        Score score,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> appliedRuleApplications,
        int expandedStepCount,
        String canonicalHash,
        String parentExpression,
        String appliedRuleId,
        String appliedRuleKind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        int improvement,
        List<String> appliedRuleKinds,
        List<Boolean> equivalencePreservingFlags,
        List<String> assumptions
    ) {
        if (ordinal >= 0) {
            writer.property("ordinal", ordinal);
        }
        return writer.property("expression", expression)
            .property("depth", depth)
            .object("score", score::writeJson)
            .stringArray("path", path)
            .stringArray("appliedRuleIds", appliedRuleIds)
            .stringArray(
                "appliedRuleApplications",
                appliedRuleApplications)
            .property("expandedStepCount", expandedStepCount)
            .property("canonicalHash", canonicalHash)
            .property("parentExpression", parentExpression)
            .property("appliedRuleId", appliedRuleId)
            .property("appliedRuleKind", appliedRuleKind)
            .property("mayIncreaseComplexity", mayIncreaseComplexity)
            .property("estimatedCostDelta", estimatedCostDelta)
            .property(
                "equivalencePreservingByConstruction",
                equivalencePreservingByConstruction)
            .property("improvement", improvement)
            .stringArray("appliedRuleKinds", appliedRuleKinds)
            .booleanArray(
                "equivalencePreservingFlags",
                equivalencePreservingFlags)
            .stringArray("assumptions", assumptions);
    }

    private static String stateFingerprint(SearchState state) {
        if (state == null) {
            throw new IllegalArgumentException(
                "target-free result must expose a best state");
        }
        Score score = Score.from(state.score());
        return sha256(statePayloadJson(
            state.expression(),
            state.depth(),
            score,
            state.path(),
            state.appliedRuleIds(),
            state.appliedRuleApplications().stream().sorted().toList(),
            state.expandedStepCount(),
            state.canonicalHash(),
            state.parentExpression() == null ? "" : state.parentExpression(),
            state.appliedRuleId() == null ? "" : state.appliedRuleId(),
            state.appliedRuleKind().name(),
            state.mayIncreaseComplexity(),
            state.estimatedCostDelta(),
            state.equivalencePreservingByConstruction(),
            state.improvement(),
            state.appliedRuleKinds().stream().map(Enum::name).toList(),
            state.equivalencePreservingFlags(),
            state.assumptions()));
    }

    private static List<String> requireInventory(List<String> values) {
        List<String> inventory = requireTextList(
            values,
            "operatorInventory",
            false);
        Set<String> unique = new HashSet<>(inventory);
        if (unique.size() != inventory.size()) {
            throw new IllegalArgumentException(
                "operator inventory must not contain duplicates");
        }
        return inventory;
    }

    private static List<String> requireTextList(
        List<String> values,
        String label,
        boolean allowEmpty
    ) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, label));
        if (!allowEmpty && copy.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        for (String value : copy) {
            requireText(value, label + " entry");
        }
        return copy;
    }

    private static String requireStudyId(String value) {
        String studyId = requireText(value, "studyId");
        if (!STUDY_ID.matcher(studyId).matches()) {
            throw new IllegalArgumentException(
                "studyId must be a bounded lowercase identifier");
        }
        return studyId;
    }

    private static String requireSha256(String value, String label) {
        String hash = requireText(value, label);
        if (!SHA256.matcher(hash).matches()) {
            throw new IllegalArgumentException(
                label + " must use sha256:<64 lowercase hex digits>");
        }
        return hash;
    }

    private static String requireText(String value, String label) {
        String text = Objects.requireNonNull(value, label).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return text;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void rejectSymbolicAncestry(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                    "symbolic path ancestry is not accepted: " + current);
            }
        }
    }
}
