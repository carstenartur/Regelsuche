package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Complete target-aware reachability diagnostic for one explicitly bounded
 * production-rewrite closure.
 *
 * <p>The oracle never generates a candidate or changes the rewrite inventory.
 * It receives the target and is therefore diagnostic evidence only. Search
 * states are distinguished by formatted syntax and normalized assumptions.
 * Multiple non-dominated depth/primitive-work labels are retained for one
 * mathematical state so a short macro cannot hide a longer but cheaper
 * primitive witness, or vice versa.</p>
 */
public final class BoundedReachabilityOracle {
    public static final String ORACLE_ID =
        "regelsuche.bounded-reachability-oracle/v1";

    /**
     * Enumerates the declared closure and retains a shortest primitive witness.
     *
     * @param sourceExpression source expression accepted by the ordinary parser
     * @param targetExpression visible diagnostic target
     * @param initialAssumptions assumptions available before the first rewrite
     * @param engine exact transformation engine whose directed edges are tested
     * @param budget closure and execution bounds
     * @return complete retained diagnostic evidence
     */
    public Result analyze(
        String sourceExpression,
        String targetExpression,
        AssumptionSignature initialAssumptions,
        TransformationEngine engine,
        Budget budget
    ) {
        String source = normalizeInput(sourceExpression, "sourceExpression");
        String target = normalizeInput(targetExpression, "targetExpression");
        return new SearchRun(
            source,
            target,
            Objects.requireNonNull(
                initialAssumptions,
                "initialAssumptions"),
            Objects.requireNonNull(engine, "engine"),
            Objects.requireNonNull(budget, "budget"))
            .execute();
    }

    private static String normalizeInput(String expression, String field) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            return ExpressionFormatter.format(
                new ExpressionParser().parseTerm(expression));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                field + " is not a supported term", exception);
        }
    }

    private static String normalizeGenerated(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalStateException(
                "transformation produced a blank expression");
        }
        try {
            return ExpressionFormatter.format(
                new ExpressionParser().parseTerm(expression));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "transformation produced an unparsable expression",
                exception);
        }
    }

    private static String sha256(String payload) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        REACHABLE,
        REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS,
        UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    public enum EdgeDisposition {
        ENQUEUED,
        DOMINATED_BY_EXISTING_LABEL,
        OUTSIDE_PRIMITIVE_WORK_BOUND,
        VISITED_STATE_LIMIT
    }

    /**
     * Closure bounds and separate mechanical execution ceilings.
     *
     * <p>{@code maxDepth} and {@code maxPrimitivePathWork} define the finite
     * closure being claimed. Reaching either is therefore a completed boundary,
     * not an inconclusive failure. State and transition ceilings are execution
     * resources; hitting either makes the result inconclusive.</p>
     */
    public record Budget(
        int maxDepth,
        int maxPrimitivePathWork,
        int maxVisitedStates,
        int maxGeneratedTransitions
    ) {
        public Budget {
            if (maxDepth < 0
                    || maxPrimitivePathWork < 0
                    || maxVisitedStates < 1
                    || maxGeneratedTransitions < 0) {
                throw new IllegalArgumentException(
                    "depth/work/transition limits must be non-negative "
                        + "and maxVisitedStates must be positive");
            }
        }
    }

    public record RetainedState(
        String id,
        String expression,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        boolean nonDominatedAtTermination
    ) {
        public RetainedState {
            requireHash(id, "state id");
            requireText(expression, "state expression");
            assumptions = Objects.requireNonNull(assumptions, "assumptions");
            if (depth < 0 || primitivePathWork < 0) {
                throw new IllegalArgumentException(
                    "state depth and primitive work must not be negative");
            }
        }
    }

    public record RetainedEdge(
        String id,
        String fromStateId,
        String proposedStateId,
        String ruleId,
        String transformedExpression,
        AssumptionSignature resultingAssumptions,
        List<String> primitiveRuleIds,
        int primitiveStepCount,
        EdgeDisposition disposition
    ) {
        public RetainedEdge {
            requireHash(id, "edge id");
            requireHash(fromStateId, "fromStateId");
            requireHash(proposedStateId, "proposedStateId");
            requireText(ruleId, "ruleId");
            requireText(transformedExpression, "transformedExpression");
            resultingAssumptions = Objects.requireNonNull(
                resultingAssumptions,
                "resultingAssumptions");
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds,
                "primitiveRuleIds"));
            if (primitiveRuleIds.isEmpty()
                    || primitiveRuleIds.stream().anyMatch(
                        value -> value == null || value.isBlank())
                    || primitiveStepCount != primitiveRuleIds.size()) {
                throw new IllegalArgumentException(
                    "primitive lineage must be complete and balanced");
            }
            disposition = Objects.requireNonNull(
                disposition,
                "disposition");
        }
    }

    public record Witness(
        List<RetainedState> states,
        List<RetainedEdge> edges,
        List<String> primitiveRuleIds,
        List<String> additionalAssumptions,
        int depth,
        int primitiveSteps
    ) {
        public Witness {
            states = List.copyOf(Objects.requireNonNull(states, "states"));
            edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds,
                "primitiveRuleIds"));
            additionalAssumptions = List.copyOf(Objects.requireNonNull(
                additionalAssumptions,
                "additionalAssumptions"));
            if (states.isEmpty()
                    || edges.size() + 1 != states.size()
                    || depth != edges.size()
                    || primitiveSteps != primitiveRuleIds.size()
                    || primitiveSteps != edges.stream()
                        .mapToInt(RetainedEdge::primitiveStepCount)
                        .sum()) {
                throw new IllegalArgumentException(
                    "witness path and primitive lineage must be balanced");
            }
        }
    }

    public record WorkLedger(
        int expandedStates,
        int generatedTransitions,
        int discoveredStates,
        int enqueuedTransitions,
        int dominatedTransitions,
        int outsidePrimitiveWorkTransitions,
        int visitedStateLimitTransitions,
        int supersededStates,
        int depthBoundaryStates,
        int primitiveWorkBoundaryStates,
        int maxFrontierSize,
        boolean generatedTransitionLimitReached,
        boolean visitedStateLimitReached
    ) {
        public WorkLedger {
            if (expandedStates < 0
                    || generatedTransitions < 0
                    || discoveredStates < 1
                    || enqueuedTransitions < 0
                    || dominatedTransitions < 0
                    || outsidePrimitiveWorkTransitions < 0
                    || visitedStateLimitTransitions < 0
                    || supersededStates < 0
                    || depthBoundaryStates < 0
                    || primitiveWorkBoundaryStates < 0
                    || maxFrontierSize < 1
                    || discoveredStates != enqueuedTransitions + 1
                    || generatedTransitions
                        != enqueuedTransitions
                            + dominatedTransitions
                            + outsidePrimitiveWorkTransitions
                            + visitedStateLimitTransitions) {
                throw new IllegalArgumentException(
                    "reachability work ledger must be non-negative "
                        + "and exactly balanced");
            }
        }
    }

    public record Result(
        String oracleId,
        Status status,
        String sourceExpression,
        String targetExpression,
        AssumptionSignature initialAssumptions,
        Budget budget,
        List<RetainedState> states,
        List<RetainedEdge> edges,
        Optional<Witness> witness,
        WorkLedger work,
        String detailCode,
        String technicalDetail
    ) {
        public Result {
            if (!ORACLE_ID.equals(oracleId)) {
                throw new IllegalArgumentException(
                    "unexpected reachability-oracle identity");
            }
            status = Objects.requireNonNull(status, "status");
            requireText(sourceExpression, "sourceExpression");
            requireText(targetExpression, "targetExpression");
            initialAssumptions = Objects.requireNonNull(
                initialAssumptions,
                "initialAssumptions");
            budget = Objects.requireNonNull(budget, "budget");
            states = List.copyOf(Objects.requireNonNull(states, "states"));
            edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
            witness = Objects.requireNonNull(witness, "witness");
            work = Objects.requireNonNull(work, "work");
            requireText(detailCode, "detailCode");
            technicalDetail = technicalDetail == null
                ? "" : technicalDetail.trim();
            boolean reached = status == Status.REACHABLE
                || status
                    == Status.REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS;
            if (reached && witness.isEmpty()) {
                throw new IllegalArgumentException(
                    "conclusive reachability status requires a witness");
            }
            if (status
                    == Status.UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE
                    && witness.isPresent()) {
                throw new IllegalArgumentException(
                    "unreachable closure must not retain a witness");
            }
            if (status == Status.REACHABLE
                    && !witness.orElseThrow()
                        .additionalAssumptions().isEmpty()) {
                throw new IllegalArgumentException(
                    "REACHABLE witness must use declared assumptions only");
            }
            if (status
                    == Status.REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS
                    && witness.orElseThrow()
                        .additionalAssumptions().isEmpty()) {
                throw new IllegalArgumentException(
                    "conditional reachability requires added assumptions");
            }
            if (status == Status.TECHNICAL_FAILURE
                    && technicalDetail.isBlank()) {
                throw new IllegalArgumentException(
                    "technical failure requires technical detail");
            }
            if (states.size() != work.discoveredStates()
                    || edges.size() != work.generatedTransitions()) {
                throw new IllegalArgumentException(
                    "retained graph and work ledger must agree");
            }
        }

        public boolean closureComplete() {
            return status
                == Status.UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE
                || status
                    == Status.REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS;
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a SHA-256 identity");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private final class SearchRun {
        private static final Comparator<Node> NODE_ORDER =
            Comparator.comparingInt(Node::primitivePathWork)
                .thenComparingInt(Node::depth)
                .thenComparingInt(node ->
                    node.assumptions().normalizedAssumptions().size())
                .thenComparing(Node::expression)
                .thenComparing(node -> node.assumptions().fingerprint())
                .thenComparing(Node::id);

        private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparing(Candidate::expression)
                .thenComparing(candidate ->
                    candidate.assumptions().fingerprint())
                .thenComparingInt(candidate ->
                    candidate.transformation().primitiveStepCount())
                .thenComparing(candidate ->
                    candidate.transformation().rule())
                .thenComparing(candidate ->
                    candidate.transformation().applicationKey())
                .thenComparing(candidate ->
                    String.join(
                        "\u0001",
                        candidate.transformation().primitiveRuleIds()));

        private final String source;
        private final String target;
        private final AssumptionSignature initialAssumptions;
        private final TransformationEngine engine;
        private final Budget budget;
        private final PriorityQueue<Node> frontier =
            new PriorityQueue<>(NODE_ORDER);
        private final Map<String, Node> statesById = new LinkedHashMap<>();
        private final Map<MathematicalKey, List<Node>> labelsByKey =
            new LinkedHashMap<>();
        private final Set<String> activeStateIds = new LinkedHashSet<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<String, Edge> parentEdgeByStateId =
            new LinkedHashMap<>();

        private int expandedStates;
        private int generatedTransitions;
        private int enqueuedTransitions;
        private int dominatedTransitions;
        private int outsidePrimitiveWorkTransitions;
        private int visitedStateLimitTransitions;
        private int supersededStates;
        private int depthBoundaryStates;
        private int primitiveWorkBoundaryStates;
        private int maxFrontierSize = 1;
        private boolean generatedTransitionLimitReached;
        private boolean visitedStateLimitReached;
        private Node bestConditionalTarget;

        private SearchRun(
            String source,
            String target,
            AssumptionSignature initialAssumptions,
            TransformationEngine engine,
            Budget budget
        ) {
            this.source = source;
            this.target = target;
            this.initialAssumptions = initialAssumptions;
            this.engine = engine;
            this.budget = budget;
        }

        private Result execute() {
            retainSource();
            while (!frontier.isEmpty()) {
                Node current = frontier.remove();
                if (!activeStateIds.contains(current.id())) {
                    continue;
                }
                Result reached = inspectTarget(current);
                if (reached != null) {
                    return reached;
                }
                if (current.expression().equals(target)) {
                    // Assumptions only accumulate. Expanding a conditional
                    // target cannot lead to an assumption-free target state.
                    continue;
                }
                if (atClosureBoundary(current)) {
                    continue;
                }
                expandedStates++;
                List<Candidate> candidates;
                try {
                    candidates = candidates(current);
                } catch (RuntimeException exception) {
                    return technicalFailure(exception);
                }
                Result interrupted = processCandidates(current, candidates);
                if (interrupted != null) {
                    return interrupted;
                }
            }
            if (bestConditionalTarget != null) {
                return result(
                    Status.REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS,
                    Optional.of(witness(bestConditionalTarget)),
                    "TARGET_REQUIRES_ADDITIONAL_ASSUMPTIONS",
                    "");
            }
            return result(
                Status.UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE,
                Optional.empty(),
                "COMPLETE_DECLARED_CLOSURE_EXHAUSTED",
                "");
        }

        private void retainSource() {
            Node sourceNode = node(
                source,
                initialAssumptions,
                0,
                0);
            statesById.put(sourceNode.id(), sourceNode);
            labelsByKey.put(
                sourceNode.mathematicalKey(),
                new ArrayList<>(List.of(sourceNode)));
            activeStateIds.add(sourceNode.id());
            frontier.add(sourceNode);
        }

        private Result inspectTarget(Node current) {
            if (!current.expression().equals(target)) {
                return null;
            }
            if (additionalAssumptions(current).isEmpty()) {
                return result(
                    Status.REACHABLE,
                    Optional.of(witness(current)),
                    "TARGET_REACHED_WITHIN_DECLARED_ASSUMPTIONS",
                    "");
            }
            if (bestConditionalTarget == null
                    || NODE_ORDER.compare(
                        current,
                        bestConditionalTarget) < 0) {
                bestConditionalTarget = current;
            }
            return null;
        }

        private boolean atClosureBoundary(Node current) {
            boolean depthBoundary = current.depth() >= budget.maxDepth();
            boolean primitiveBoundary =
                current.primitivePathWork()
                    >= budget.maxPrimitivePathWork();
            if (depthBoundary) {
                depthBoundaryStates++;
            }
            if (primitiveBoundary) {
                primitiveWorkBoundaryStates++;
            }
            return depthBoundary || primitiveBoundary;
        }

        private List<Candidate> candidates(Node current) {
            List<Transformation> transformations =
                engine.transform(current.expression());
            if (transformations == null) {
                throw new IllegalStateException(
                    "transformation engine returned null");
            }
            List<Candidate> result = new ArrayList<>();
            for (Transformation transformation : transformations) {
                Objects.requireNonNull(
                    transformation,
                    "transformation entry");
                AssumptionSignature assumptions = AssumptionSignature.merge(
                    current.assumptions(),
                    AssumptionSignature.ofExpressions(
                        transformation.assumptions()));
                result.add(new Candidate(
                    transformation,
                    normalizeGenerated(
                        transformation.transformedExpression()),
                    assumptions));
            }
            result.sort(CANDIDATE_ORDER);
            return List.copyOf(result);
        }

        private Result processCandidates(
            Node current,
            List<Candidate> candidates
        ) {
            for (Candidate candidate : candidates) {
                if (generatedTransitions
                        >= budget.maxGeneratedTransitions()) {
                    generatedTransitionLimitReached = true;
                    return budgetInconclusive(
                        "GENERATED_TRANSITION_LIMIT_REACHED");
                }
                Result interrupted = processCandidate(current, candidate);
                if (interrupted != null) {
                    return interrupted;
                }
            }
            return null;
        }

        private Result processCandidate(
            Node current,
            Candidate candidate
        ) {
            generatedTransitions++;
            int depth = current.depth() + 1;
            int primitiveWork = Math.addExact(
                current.primitivePathWork(),
                candidate.transformation().primitiveStepCount());
            Node proposed = node(
                candidate.expression(),
                candidate.assumptions(),
                depth,
                primitiveWork);
            if (primitiveWork > budget.maxPrimitivePathWork()) {
                outsidePrimitiveWorkTransitions++;
                retainEdge(
                    current,
                    proposed,
                    candidate.transformation(),
                    EdgeDisposition.OUTSIDE_PRIMITIVE_WORK_BOUND);
                return null;
            }
            List<Node> labels = labelsByKey.computeIfAbsent(
                proposed.mathematicalKey(),
                ignored -> new ArrayList<>());
            if (isDominated(proposed, labels)) {
                dominatedTransitions++;
                retainEdge(
                    current,
                    proposed,
                    candidate.transformation(),
                    EdgeDisposition.DOMINATED_BY_EXISTING_LABEL);
                return null;
            }
            if (statesById.size() >= budget.maxVisitedStates()) {
                visitedStateLimitTransitions++;
                visitedStateLimitReached = true;
                retainEdge(
                    current,
                    proposed,
                    candidate.transformation(),
                    EdgeDisposition.VISITED_STATE_LIMIT);
                return budgetInconclusive(
                    "VISITED_STATE_LIMIT_REACHED");
            }
            removeDominatedLabels(proposed, labels);
            labels.add(proposed);
            activeStateIds.add(proposed.id());
            statesById.put(proposed.id(), proposed);
            frontier.add(proposed);
            maxFrontierSize = Math.max(maxFrontierSize, frontier.size());
            Edge edge = retainEdge(
                current,
                proposed,
                candidate.transformation(),
                EdgeDisposition.ENQUEUED);
            parentEdgeByStateId.put(proposed.id(), edge);
            enqueuedTransitions++;
            return null;
        }

        private boolean isDominated(Node proposed, List<Node> labels) {
            return labels.stream().anyMatch(existing ->
                existing.depth() <= proposed.depth()
                    && existing.primitivePathWork()
                        <= proposed.primitivePathWork());
        }

        private void removeDominatedLabels(
            Node proposed,
            List<Node> labels
        ) {
            List<Node> dominated = labels.stream()
                .filter(existing ->
                    proposed.depth() <= existing.depth()
                        && proposed.primitivePathWork()
                            <= existing.primitivePathWork())
                .toList();
            labels.removeAll(dominated);
            dominated.forEach(existing ->
                activeStateIds.remove(existing.id()));
            supersededStates += dominated.size();
        }

        private Edge retainEdge(
            Node from,
            Node proposed,
            Transformation transformation,
            EdgeDisposition disposition
        ) {
            int ordinal = edges.size();
            String id = sha256(String.join(
                "\n",
                "schema=regelsuche.reachability-edge/v1",
                "ordinal=" + ordinal,
                "from=" + from.id(),
                "to=" + proposed.id(),
                "rule=" + transformation.rule(),
                "application=" + transformation.applicationKey(),
                "disposition=" + disposition.name()));
            Edge edge = new Edge(
                id,
                from.id(),
                proposed.id(),
                transformation.rule(),
                proposed.expression(),
                proposed.assumptions(),
                transformation.primitiveRuleIds(),
                transformation.primitiveStepCount(),
                disposition);
            edges.add(edge);
            return edge;
        }

        private Result budgetInconclusive(String detailCode) {
            Optional<Witness> provisional =
                bestConditionalTarget == null
                    ? Optional.empty()
                    : Optional.of(witness(bestConditionalTarget));
            return result(
                Status.BUDGET_INCONCLUSIVE,
                provisional,
                bestConditionalTarget == null
                    ? detailCode
                    : detailCode + "_AFTER_CONDITIONAL_WITNESS",
                "");
        }

        private Result technicalFailure(RuntimeException exception) {
            String message = exception.getMessage() == null
                ? "" : exception.getMessage().trim();
            String detail = exception.getClass().getName()
                + (message.isBlank() ? "" : ": " + message);
            Optional<Witness> provisional =
                bestConditionalTarget == null
                    ? Optional.empty()
                    : Optional.of(witness(bestConditionalTarget));
            return result(
                Status.TECHNICAL_FAILURE,
                provisional,
                "TRANSFORMATION_ENGINE_OR_OUTPUT_FAILURE",
                detail);
        }

        private Result result(
            Status status,
            Optional<Witness> witness,
            String detailCode,
            String technicalDetail
        ) {
            List<RetainedState> retainedStates = statesById.values().stream()
                .map(this::retainedState)
                .toList();
            List<RetainedEdge> retainedEdges = edges.stream()
                .map(Edge::retained)
                .toList();
            return new Result(
                ORACLE_ID,
                status,
                source,
                target,
                initialAssumptions,
                budget,
                retainedStates,
                retainedEdges,
                witness,
                workLedger(),
                detailCode,
                technicalDetail);
        }

        private WorkLedger workLedger() {
            return new WorkLedger(
                expandedStates,
                generatedTransitions,
                statesById.size(),
                enqueuedTransitions,
                dominatedTransitions,
                outsidePrimitiveWorkTransitions,
                visitedStateLimitTransitions,
                supersededStates,
                depthBoundaryStates,
                primitiveWorkBoundaryStates,
                maxFrontierSize,
                generatedTransitionLimitReached,
                visitedStateLimitReached);
        }

        private Witness witness(Node targetNode) {
            List<Node> reverseStates = new ArrayList<>();
            List<Edge> reverseEdges = new ArrayList<>();
            Node current = targetNode;
            reverseStates.add(current);
            while (current.depth() > 0) {
                Edge edge = parentEdgeByStateId.get(current.id());
                if (edge == null) {
                    throw new IllegalStateException(
                        "missing parent edge for witness state "
                            + current.id());
                }
                reverseEdges.add(edge);
                current = statesById.get(edge.fromStateId());
                if (current == null) {
                    throw new IllegalStateException(
                        "missing parent state for witness edge "
                            + edge.id());
                }
                reverseStates.add(current);
            }
            Collections.reverse(reverseStates);
            Collections.reverse(reverseEdges);
            List<String> primitiveRuleIds = reverseEdges.stream()
                .flatMap(edge -> edge.primitiveRuleIds().stream())
                .toList();
            return new Witness(
                reverseStates.stream().map(this::retainedState).toList(),
                reverseEdges.stream().map(Edge::retained).toList(),
                primitiveRuleIds,
                additionalAssumptions(targetNode),
                reverseEdges.size(),
                primitiveRuleIds.size());
        }

        private List<String> additionalAssumptions(Node state) {
            TreeSet<String> additional = new TreeSet<>(
                state.assumptions().normalizedAssumptions());
            additional.removeAll(
                initialAssumptions.normalizedAssumptions());
            return List.copyOf(additional);
        }

        private RetainedState retainedState(Node state) {
            return new RetainedState(
                state.id(),
                state.expression(),
                state.assumptions(),
                state.depth(),
                state.primitivePathWork(),
                activeStateIds.contains(state.id()));
        }

        private Node node(
            String expression,
            AssumptionSignature assumptions,
            int depth,
            int primitivePathWork
        ) {
            String id = sha256(String.join(
                "\n",
                "schema=regelsuche.reachability-state/v1",
                "expression=" + expression,
                "assumptions=" + assumptions.fingerprint(),
                "depth=" + depth,
                "primitivePathWork=" + primitivePathWork));
            return new Node(
                id,
                expression,
                assumptions,
                depth,
                primitivePathWork);
        }
    }

    private record MathematicalKey(
        String expression,
        String assumptionFingerprint
    ) {
        private MathematicalKey {
            requireText(expression, "mathematical expression");
            assumptionFingerprint = assumptionFingerprint == null
                ? "" : assumptionFingerprint;
        }
    }

    private record Node(
        String id,
        String expression,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork
    ) {
        private Node {
            requireHash(id, "node id");
            requireText(expression, "node expression");
            assumptions = Objects.requireNonNull(assumptions, "assumptions");
            if (depth < 0 || primitivePathWork < 0) {
                throw new IllegalArgumentException(
                    "node depth/work must not be negative");
            }
        }

        private MathematicalKey mathematicalKey() {
            return new MathematicalKey(
                expression,
                assumptions.fingerprint());
        }
    }

    private record Candidate(
        Transformation transformation,
        String expression,
        AssumptionSignature assumptions
    ) {
        private Candidate {
            transformation = Objects.requireNonNull(
                transformation,
                "transformation");
            requireText(expression, "candidate expression");
            assumptions = Objects.requireNonNull(assumptions, "assumptions");
        }
    }

    private record Edge(
        String id,
        String fromStateId,
        String proposedStateId,
        String ruleId,
        String transformedExpression,
        AssumptionSignature resultingAssumptions,
        List<String> primitiveRuleIds,
        int primitiveStepCount,
        EdgeDisposition disposition
    ) {
        private Edge {
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
        }

        private RetainedEdge retained() {
            return new RetainedEdge(
                id,
                fromStateId,
                proposedStateId,
                ruleId,
                transformedExpression,
                resultingAssumptions,
                primitiveRuleIds,
                primitiveStepCount,
                disposition);
        }
    }
}
