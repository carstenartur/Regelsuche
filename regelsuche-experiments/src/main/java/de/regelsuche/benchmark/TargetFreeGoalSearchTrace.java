package de.regelsuche.benchmark;

import static de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus.UNTARGETED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed evidence for one target-free best-first search.
 *
 * <p>The trace is an artifact payload, not a second run identity. Callers bind
 * its {@link Trace#contentHash()} into the existing
 * {@code RepresentationDiscoveryRunWorkspace} artifact matrix and compare
 * baseline and accumulated workspaces through
 * {@code RepresentationDiscoveryRunComparison}. Historical correspondence is
 * intentionally absent from this type and may only be evaluated after the
 * trace and its owning workspace have been retained.</p>
 */
public final class TargetFreeGoalSearchTrace {
    public static final String SCHEMA =
        "regelsuche.target-free-goal-search-trace/v1";
    public static final String FILE_SUFFIX = ".json";
    public static final String CLAIM_BOUNDARY =
        "Complete retained state and work evidence for one untargeted "
            + "bounded goal-search execution; not a run identity, "
            + "mathematical proof, historical correspondence, novelty, "
            + "global reachability or search superiority.";

    private static final long MAX_TRACE_BYTES = 256L * 1024L * 1024L;
    private static final Pattern SHA256 = Pattern.compile(
        "sha256:[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private TargetFreeGoalSearchTrace() {
    }

    /** Freezes one complete untargeted result without historical labels. */
    public static Trace freeze(
        String sourceExpression,
        TransformationGoal objective,
        SearchHeuristic heuristic,
        List<String> operatorInventory,
        List<String> frozenLearnedRuleIds,
        GoalSearchResult result
    ) {
        GoalSearchResult requiredResult = Objects.requireNonNull(
            result,
            "result");
        if (requiredResult.status() != UNTARGETED
                || requiredResult.reached()
                || requiredResult.reachedState() != null
                || requiredResult.bestDistance() != -1) {
            throw new IllegalArgumentException(
                "target-free trace accepts only an untargeted result "
                    + "without target distance or reached state");
        }
        String source = normalizeExpression(sourceExpression);
        List<State> states = freezeStates(requiredResult.states());
        Content content = new Content(
            source,
            Objects.requireNonNull(objective, "objective").name(),
            "BEST_FIRST",
            Budget.from(Objects.requireNonNull(heuristic, "heuristic")),
            requireInventory(operatorInventory),
            requireSortedUniqueTextList(
                frozenLearnedRuleIds,
                "frozenLearnedRuleIds",
                true),
            requiredResult.status().name(),
            requiredResult.bestDistance(),
            stateFingerprint(requiredResult.bestState()),
            Metrics.from(requiredResult.metrics()),
            states,
            CLAIM_BOUNDARY
        );
        return Trace.create(content);
    }

    /**
     * Retains canonical bytes under the trace content hash and verifies the
     * exact persisted payload. Existing identical content is reused.
     */
    public static VerifiedTrace retain(
        Path directory,
        Trace trace
    ) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        rejectSymbolicAncestry(root);
        Files.createDirectories(root);
        rejectSymbolicAncestry(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException(
                "target-free trace directory must be a regular directory");
        }

        Trace required = Objects.requireNonNull(trace, "trace");
        String canonical = required.toCanonicalJson();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_TRACE_BYTES) {
            throw new IllegalArgumentException(
                "target-free trace length is outside the bounded range: "
                    + bytes.length);
        }
        Path path = root.resolve(fileName(required.contentHash()));
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return verify(path, required);
        }
        AtomicJsonFile.writeUtf8(path, canonical);
        return verify(path, required);
    }

    /** Verifies exact canonical bytes and the decoded trace identity. */
    public static VerifiedTrace verify(
        Path artifact,
        Trace expected
    ) throws IOException {
        Path path = Objects.requireNonNull(artifact, "artifact")
            .toAbsolutePath().normalize();
        rejectSymbolicAncestry(path);
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "target-free trace must be a regular file");
        }
        long declaredLength = Files.size(path);
        if (declaredLength < 1 || declaredLength > MAX_TRACE_BYTES) {
            throw new IllegalArgumentException(
                "target-free trace length is outside the bounded range: "
                    + declaredLength);
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != declaredLength) {
            throw new IllegalArgumentException(
                "target-free trace changed while being read");
        }
        Trace decoded = Trace.fromCanonicalBytes(bytes);
        Trace required = Objects.requireNonNull(expected, "expected");
        if (!required.equals(decoded)
                || !required.toCanonicalJson().equals(
                    new String(bytes, StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(
                "target-free trace differs from frozen evidence");
        }
        String expectedFileName = fileName(required.contentHash());
        if (!path.getFileName().toString().equals(expectedFileName)) {
            throw new IllegalArgumentException(
                "target-free trace filename does not match content hash");
        }
        return new VerifiedTrace(
            path,
            declaredLength,
            sha256(bytes),
            decoded
        );
    }

    private static List<State> freezeStates(List<SearchState> source) {
        List<SearchState> states = List.copyOf(Objects.requireNonNull(
            source,
            "states"));
        if (states.isEmpty()) {
            throw new IllegalArgumentException(
                "target-free trace must retain at least the root state");
        }
        return java.util.stream.IntStream.range(0, states.size())
            .mapToObj(index -> State.from(index, states.get(index)))
            .toList();
    }

    private static String stateFingerprint(SearchState state) {
        if (state == null) {
            throw new IllegalArgumentException(
                "target-free result must expose a best state");
        }
        return State.from(-1, state).fingerprint();
    }

    private static String fileName(String contentHash) {
        return requireSha256(contentHash, "contentHash")
            .substring("sha256:".length()) + FILE_SUFFIX;
    }

    public record Trace(
        String schema,
        Content content,
        String contentHash
    ) {
        public Trace {
            schema = requireText(schema, "schema");
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported target-free goal-search trace schema: "
                        + schema);
            }
            content = Objects.requireNonNull(content, "content");
            contentHash = requireSha256(contentHash, "contentHash");
            if (!sha256(json(content)).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "target-free goal-search trace content hash differs");
            }
        }

        private static Trace create(Content content) {
            Content required = Objects.requireNonNull(content, "content");
            return new Trace(
                SCHEMA,
                required,
                sha256(json(required))
            );
        }

        public String toCanonicalJson() {
            return json(this);
        }

        public static Trace fromCanonicalJson(String source) {
            String required = Objects.requireNonNull(source, "source");
            try {
                Trace trace = JSON.readValue(required, Trace.class);
                if (!trace.toCanonicalJson().equals(required)) {
                    throw new IllegalArgumentException(
                        "target-free trace JSON is not canonical");
                }
                return trace;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid target-free trace JSON",
                    exception);
            }
        }

        public static Trace fromCanonicalBytes(byte[] source) {
            byte[] required = Objects.requireNonNull(source, "source");
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(required))
                    .toString();
                return fromCanonicalJson(text);
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException(
                    "target-free trace is not valid UTF-8",
                    exception);
            }
        }
    }

    public record Content(
        String sourceExpression,
        String objective,
        String strategy,
        Budget budget,
        List<String> operatorInventory,
        List<String> frozenLearnedRuleIds,
        String terminalStatus,
        int bestDistance,
        String bestStateFingerprint,
        Metrics metrics,
        List<State> states,
        String claimBoundary
    ) {
        public Content {
            sourceExpression = normalizeExpression(sourceExpression);
            objective = requireText(objective, "objective");
            strategy = requireText(strategy, "strategy");
            if (!"BEST_FIRST".equals(strategy)) {
                throw new IllegalArgumentException(
                    "unsupported target-free search strategy: " + strategy);
            }
            budget = Objects.requireNonNull(budget, "budget");
            operatorInventory = requireInventory(operatorInventory);
            frozenLearnedRuleIds = requireSortedUniqueTextList(
                frozenLearnedRuleIds,
                "frozenLearnedRuleIds",
                true);
            terminalStatus = requireText(
                terminalStatus,
                "terminalStatus");
            if (!UNTARGETED.name().equals(terminalStatus)
                    || bestDistance != -1) {
                throw new IllegalArgumentException(
                    "target-free trace must report UNTARGETED and "
                        + "bestDistance=-1");
            }
            bestStateFingerprint = requireSha256(
                bestStateFingerprint,
                "bestStateFingerprint");
            metrics = Objects.requireNonNull(metrics, "metrics");
            states = List.copyOf(Objects.requireNonNull(states, "states"));
            claimBoundary = requireText(claimBoundary, "claimBoundary");
            if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException(
                    "unsupported target-free trace claim boundary");
            }
            validateStates(
                sourceExpression,
                bestStateFingerprint,
                metrics,
                states
            );
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
        public Budget {
            if (maxDepth < 0
                    || maxVisitedExpressions < 1
                    || significantImprovementThreshold < 0
                    || maxExpandingSteps < 0
                    || maxCandidatesPerState < 1
                    || beamWidth < 1) {
                throw new IllegalArgumentException(
                    "invalid target-free goal-search budget");
            }
        }

        private static Budget from(SearchHeuristic heuristic) {
            return new Budget(
                heuristic.maxDepth(),
                heuristic.maxVisitedExpressions(),
                heuristic.significantImprovementThreshold(),
                heuristic.maxExpandingSteps(),
                heuristic.maxCandidatesPerState(),
                heuristic.beamWidth()
            );
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
        public Metrics {
            for (int value : List.of(
                exploredStates,
                expandedStates,
                generatedTransformations,
                enqueuedStates,
                skippedTransformations,
                duplicatePrunes,
                transpositionPrunes,
                depthPrunes,
                candidateBudgetPrunes,
                statesWithoutTransformations,
                identityCacheHits,
                identityCacheMisses,
                cachedExpressions,
                internedValues)) {
                if (value < 0) {
                    throw new IllegalArgumentException(
                        "target-free trace metrics must be non-negative");
                }
            }
        }

        private static Metrics from(GoalMetrics metrics) {
            GoalMetrics required = Objects.requireNonNull(metrics, "metrics");
            return new Metrics(
                required.exploredStates(),
                required.expandedStates(),
                required.generatedTransformations(),
                required.enqueuedStates(),
                required.skippedTransformations(),
                required.duplicatePrunes(),
                required.transpositionPrunes(),
                required.depthPrunes(),
                required.candidateBudgetPrunes(),
                required.statesWithoutTransformations(),
                required.identityCacheHits(),
                required.identityCacheMisses(),
                required.cachedExpressions(),
                required.internedValues()
            );
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
            ExpressionScore required = Objects.requireNonNull(score, "score");
            return new Score(
                required.stringLength(),
                required.astNodeCount(),
                required.operatorCount(),
                required.nestingDepth(),
                required.recognizedPatternBonus(),
                required.weightedTotal()
            );
        }
    }

    public record State(
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
        public State {
            if (ordinal < -1 || depth < 0 || expandedStepCount < 0
                    || expandedStepCount > depth) {
                throw new IllegalArgumentException(
                    "invalid target-free trace state counters");
            }
            expression = normalizeExpression(expression);
            score = Objects.requireNonNull(score, "score");
            path = requireTextList(path, "path", false);
            appliedRuleIds = requireTextList(
                appliedRuleIds,
                "appliedRuleIds",
                true);
            appliedRuleApplications = requireSortedUniqueTextList(
                appliedRuleApplications,
                "appliedRuleApplications",
                true);
            canonicalHash = requireText(canonicalHash, "canonicalHash");
            parentExpression = optionalExpression(parentExpression);
            appliedRuleId = optionalText(appliedRuleId);
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
            assumptions = requireSortedUniqueTextList(
                assumptions,
                "assumptions",
                true);
            fingerprint = requireSha256(fingerprint, "fingerprint");
            validateLineage(
                expression,
                depth,
                path,
                appliedRuleIds,
                appliedRuleApplications,
                parentExpression,
                appliedRuleId,
                appliedRuleKind,
                equivalencePreservingByConstruction,
                appliedRuleKinds,
                equivalencePreservingFlags
            );
            StateFingerprint content = new StateFingerprint(
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
                assumptions
            );
            if (!sha256(json(content)).equals(fingerprint)) {
                throw new IllegalArgumentException(
                    "target-free trace state fingerprint differs");
            }
        }

        private static State from(int ordinal, SearchState state) {
            SearchState required = Objects.requireNonNull(state, "state");
            List<String> applications = required.appliedRuleApplications()
                .stream().sorted().toList();
            List<String> kinds = required.appliedRuleKinds().stream()
                .map(Enum::name).toList();
            String parent = required.parentExpression() == null
                ? ""
                : required.parentExpression();
            String ruleId = required.appliedRuleId() == null
                ? ""
                : required.appliedRuleId();
            StateFingerprint content = new StateFingerprint(
                normalizeExpression(required.expression()),
                required.depth(),
                Score.from(required.score()),
                required.path(),
                required.appliedRuleIds(),
                applications,
                required.expandedStepCount(),
                required.canonicalHash(),
                optionalExpression(parent),
                optionalText(ruleId),
                required.appliedRuleKind().name(),
                required.mayIncreaseComplexity(),
                required.estimatedCostDelta(),
                required.equivalencePreservingByConstruction(),
                required.improvement(),
                kinds,
                required.equivalencePreservingFlags(),
                required.assumptions()
            );
            return new State(
                ordinal,
                content.expression(),
                content.depth(),
                content.score(),
                content.path(),
                content.appliedRuleIds(),
                content.appliedRuleApplications(),
                content.expandedStepCount(),
                content.canonicalHash(),
                content.parentExpression(),
                content.appliedRuleId(),
                content.appliedRuleKind(),
                content.mayIncreaseComplexity(),
                content.estimatedCostDelta(),
                content.equivalencePreservingByConstruction(),
                content.improvement(),
                content.appliedRuleKinds(),
                content.equivalencePreservingFlags(),
                content.assumptions(),
                sha256(json(content))
            );
        }
    }

    private record StateFingerprint(
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
        private StateFingerprint {
            path = List.copyOf(path);
            appliedRuleIds = List.copyOf(appliedRuleIds);
            appliedRuleApplications = List.copyOf(appliedRuleApplications);
            appliedRuleKinds = List.copyOf(appliedRuleKinds);
            equivalencePreservingFlags = List.copyOf(
                equivalencePreservingFlags);
            assumptions = List.copyOf(assumptions);
        }
    }

    public record VerifiedTrace(
        Path path,
        long byteLength,
        String byteHash,
        Trace trace
    ) {
        public VerifiedTrace {
            path = Objects.requireNonNull(path, "path")
                .toAbsolutePath().normalize();
            if (byteLength < 1 || byteLength > MAX_TRACE_BYTES) {
                throw new IllegalArgumentException(
                    "verified trace length is outside bounds");
            }
            byteHash = requireSha256(byteHash, "byteHash");
            trace = Objects.requireNonNull(trace, "trace");
        }
    }

    private static void validateStates(
        String sourceExpression,
        String bestStateFingerprint,
        Metrics metrics,
        List<State> states
    ) {
        if (states.isEmpty()) {
            throw new IllegalArgumentException(
                "target-free trace must retain at least the root state");
        }
        if (metrics.exploredStates() != states.size()) {
            throw new IllegalArgumentException(
                "trace state count differs from explored-state metrics");
        }
        Set<String> fingerprints = new HashSet<>();
        boolean bestPresent = false;
        for (int index = 0; index < states.size(); index++) {
            State state = Objects.requireNonNull(
                states.get(index),
                "state");
            if (state.ordinal() != index) {
                throw new IllegalArgumentException(
                    "trace state ordinals must be contiguous");
            }
            if (!state.path().getFirst().equals(sourceExpression)
                    || !state.path().getLast().equals(state.expression())) {
                throw new IllegalArgumentException(
                    "trace state path is not rooted in the declared source");
            }
            if (!fingerprints.add(state.fingerprint())) {
                throw new IllegalArgumentException(
                    "trace state fingerprints must be unique");
            }
            bestPresent |= state.fingerprint().equals(bestStateFingerprint);
        }
        State root = states.getFirst();
        if (root.depth() != 0
                || !root.expression().equals(sourceExpression)
                || root.path().size() != 1
                || !root.appliedRuleIds().isEmpty()
                || !root.appliedRuleApplications().isEmpty()
                || !root.parentExpression().isEmpty()
                || !root.appliedRuleId().isEmpty()) {
            throw new IllegalArgumentException(
                "trace root state is inconsistent with the declared source");
        }
        if (!bestPresent) {
            throw new IllegalArgumentException(
                "trace best state is absent from retained states");
        }
    }

    private static void validateLineage(
        String expression,
        int depth,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> appliedRuleApplications,
        String parentExpression,
        String appliedRuleId,
        String appliedRuleKind,
        boolean equivalencePreservingByConstruction,
        List<String> appliedRuleKinds,
        List<Boolean> equivalencePreservingFlags
    ) {
        if (path.size() != depth + 1
                || appliedRuleIds.size() != depth
                || appliedRuleApplications.size() != depth
                || appliedRuleKinds.size() != depth
                || equivalencePreservingFlags.size() != depth
                || !path.getLast().equals(expression)) {
            throw new IllegalArgumentException(
                "trace state path and lineage lengths differ from depth");
        }
        if (depth == 0) {
            if (!parentExpression.isEmpty()
                    || !appliedRuleId.isEmpty()
                    || !appliedRuleKinds.isEmpty()
                    || !equivalencePreservingFlags.isEmpty()) {
                throw new IllegalArgumentException(
                    "trace root must not expose incoming lineage");
            }
            return;
        }
        if (!path.get(depth - 1).equals(parentExpression)
                || !appliedRuleIds.getLast().equals(appliedRuleId)
                || !appliedRuleKinds.getLast().equals(appliedRuleKind)
                || equivalencePreservingFlags.getLast()
                    != equivalencePreservingByConstruction) {
            throw new IllegalArgumentException(
                "trace incoming state metadata differs from its lineage");
        }
    }

    private static List<String> requireInventory(List<String> values) {
        List<String> inventory = requireTextList(
            values,
            "operatorInventory",
            false);
        if (new HashSet<>(inventory).size() != inventory.size()) {
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

    private static List<String> requireSortedUniqueTextList(
        List<String> values,
        String label,
        boolean allowEmpty
    ) {
        List<String> copy = requireTextList(values, label, allowEmpty);
        List<String> sorted = copy.stream().distinct().sorted().toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException(
                label + " must be sorted and duplicate-free");
        }
        return copy;
    }

    private static String normalizeExpression(String value) {
        return requireText(value, "expression")
            .replaceAll("\\s+", " ");
    }

    private static String optionalExpression(String value) {
        String text = optionalText(value);
        return text.isEmpty() ? "" : text.replaceAll("\\s+", " ");
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
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

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "unable to render target-free goal-search trace",
                exception);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void rejectSymbolicAncestry(Path path) {
        for (Path current = path; current != null;
                current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                    "symbolic path ancestry is not accepted: " + current);
            }
        }
    }
}
