package de.regelsuche.discovery.representation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic bounded rewrite enumeration without a target expression or a
 * known-structure catalog.
 *
 * <p>Every exact normalized representation reached within the frozen budget is
 * retained once. Known forms may only be attached after the returned candidate
 * set has been frozen by an information boundary.</p>
 */
public final class TargetFreeRepresentationSearch {
    public static final String SCHEMA =
        "regelsuche.target-free-representation-search/v1";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
    private static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::transformedExpression)
            .thenComparing(Transformation::rule)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(value -> String.join(
                "\u0001", value.primitiveRuleIds()));

    private final ExpressionParser parser = new ExpressionParser();
    private final SemanticDescriptionMeasurer measurer =
        new SemanticDescriptionMeasurer();

    public SearchResult search(
        String sourceExpression,
        Collection<? extends RewriteRule> rules,
        Budget budget
    ) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(budget, "budget");
        String source = normalize(sourceExpression);
        List<RewriteRule> orderedRules = orderedRules(rules);
        AstRewriteTransformationEngine engine =
            new AstRewriteTransformationEngine(
                orderedRules,
                budget.maxAstSizeIncreasePerStep(),
                budget.maxCandidatesPerState()
            );

        List<State> states = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();
        Map<String, State> byStateKey = new LinkedHashMap<>();
        ArrayDeque<State> frontier = new ArrayDeque<>();
        State root = State.root(source, measurer.measure(source));
        states.add(root);
        byStateKey.put(stateDescriptor(source, List.of()), root);
        frontier.add(root);

        int explored = 0;
        boolean transitionLimit = false;
        boolean stateLimit = false;
        boolean depthLimit = false;
        while (!frontier.isEmpty()
                && explored < budget.maxExploredStates()) {
            State current = frontier.removeFirst();
            explored++;
            if (current.depth() >= budget.maxDepth()) {
                depthLimit = true;
                continue;
            }
            List<Transformation> generated = engine
                .transform(current.expression()).stream()
                .sorted(TRANSFORMATION_ORDER)
                .toList();
            for (Transformation transformation : generated) {
                if (transitions.size()
                        >= budget.maxGeneratedTransitions()) {
                    transitionLimit = true;
                    break;
                }
                String expression = normalize(
                    transformation.transformedExpression());
                List<String> targetAssumptions = union(
                    current.assumptions(), transformation.assumptions());
                String stateKey = stateDescriptor(
                    expression, targetAssumptions);
                String targetHash = KnownStructureCatalog.sha256(stateKey);
                TransitionDisposition disposition;
                if (byStateKey.containsKey(stateKey)) {
                    disposition =
                        TransitionDisposition.DUPLICATE_REPRESENTATION;
                } else if (states.size() >= budget.maxRetainedStates()) {
                    disposition =
                        TransitionDisposition.STATE_BUDGET_EXHAUSTED;
                    stateLimit = true;
                } else {
                    disposition = TransitionDisposition.ACCEPTED_NEW_STATE;
                    State discovered = State.successor(
                        states.size() + 1,
                        targetHash,
                        expression,
                        current,
                        transformation,
                        targetAssumptions,
                        measurer.measure(expression)
                    );
                    states.add(discovered);
                    byStateKey.put(stateKey, discovered);
                    frontier.addLast(discovered);
                }
                transitions.add(Transition.of(
                    transitions.size() + 1,
                    current.stateHash(),
                    targetHash,
                    expression,
                    transformation,
                    targetAssumptions,
                    disposition
                ));
            }
            if (transitionLimit) {
                break;
            }
        }

        SearchContent content = new SearchContent(
            SCHEMA,
            source,
            root.stateHash(),
            RuleInventoryFingerprint.contentHash(orderedRules),
            budget,
            explored,
            transitions.size(),
            transitionLimit || stateLimit || depthLimit
                || !frontier.isEmpty(),
            states,
            transitions,
            paretoStateHashes(states)
        );
        return SearchResult.create(content);
    }

    private String normalize(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(
            RepresentationCandidateAssessment.requireText(
                expression, "expression")));
    }

    private static List<RewriteRule> orderedRules(
        Collection<? extends RewriteRule> rules
    ) {
        return rules.stream()
            .map(rule -> new RuleOrder(
                Objects.requireNonNull(rule, "rule"),
                RuleInventoryFingerprint.ruleContentHash(rule)))
            .sorted(Comparator
                .comparing((RuleOrder value) -> value.rule().id())
                .thenComparing(value ->
                    value.rule().descriptor().packId())
                .thenComparing(RuleOrder::contentHash))
            .map(RuleOrder::rule)
            .toList();
    }

    private static List<String> paretoStateHashes(List<State> states) {
        List<State> candidates = states.stream()
            .filter(state -> state.depth() > 0)
            .toList();
        return candidates.stream()
            .filter(candidate -> candidates.stream().noneMatch(other ->
                !other.stateHash().equals(candidate.stateHash())
                    && dominates(other, candidate)))
            .map(State::stateHash)
            .toList();
    }

    private static boolean dominates(State left, State right) {
        SemanticDescriptionMetrics leftMetrics = left.metrics();
        SemanticDescriptionMetrics rightMetrics = right.metrics();
        boolean noWorse =
            leftMetrics.tokenCount() <= rightMetrics.tokenCount()
                && leftMetrics.astNodeCount()
                    <= rightMetrics.astNodeCount()
                && leftMetrics.operatorCount()
                    <= rightMetrics.operatorCount()
                && leftMetrics.numericBitLength()
                    <= rightMetrics.numericBitLength()
                && leftMetrics.distinctSemanticValues()
                    <= rightMetrics.distinctSemanticValues()
                && leftMetrics.repeatedSemanticValueSavings()
                    >= rightMetrics.repeatedSemanticValueSavings()
                && left.assumptions().size()
                    <= right.assumptions().size();
        boolean better =
            leftMetrics.tokenCount() < rightMetrics.tokenCount()
                || leftMetrics.astNodeCount()
                    < rightMetrics.astNodeCount()
                || leftMetrics.operatorCount()
                    < rightMetrics.operatorCount()
                || leftMetrics.numericBitLength()
                    < rightMetrics.numericBitLength()
                || leftMetrics.distinctSemanticValues()
                    < rightMetrics.distinctSemanticValues()
                || leftMetrics.repeatedSemanticValueSavings()
                    > rightMetrics.repeatedSemanticValueSavings()
                || left.assumptions().size()
                    < right.assumptions().size();
        return noWorse && better;
    }

    private static String stateDescriptor(
        String expression,
        List<String> assumptions
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, SCHEMA + "/state");
        KnownStructureCatalog.appendCanonicalField(
            descriptor, expression);
        KnownStructureCatalog.appendCanonicalList(
            descriptor, assumptions);
        return descriptor.toString();
    }

    private static String stateHash(
        String expression,
        List<String> assumptions
    ) {
        return KnownStructureCatalog.sha256(
            stateDescriptor(expression, assumptions));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to render target-free search evidence", exception);
        }
    }

    private static List<String> append(
        List<String> values,
        Collection<String> additions
    ) {
        List<String> result = new ArrayList<>(values);
        result.addAll(additions);
        return List.copyOf(result);
    }

    private static List<String> union(
        Collection<String> left,
        Collection<String> right
    ) {
        TreeSet<String> result = new TreeSet<>(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    private static List<String> normalized(
        Collection<String> values
    ) {
        TreeSet<String> result = new TreeSet<>();
        for (String value : Objects.requireNonNull(values, "values")) {
            result.add(RepresentationCandidateAssessment.requireText(
                value, "list entry"));
        }
        return List.copyOf(result);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String requireHash(String value, String field) {
        String normalized =
            RepresentationCandidateAssessment.requireText(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 identity");
        }
        return normalized;
    }

    public record Budget(
        int maxDepth,
        int maxExploredStates,
        int maxRetainedStates,
        int maxGeneratedTransitions,
        int maxCandidatesPerState,
        int maxAstSizeIncreasePerStep
    ) {
        public Budget {
            if (maxDepth < 0
                    || maxExploredStates < 1
                    || maxRetainedStates < 1
                    || maxGeneratedTransitions < 1
                    || maxCandidatesPerState < 1
                    || maxAstSizeIncreasePerStep < 0) {
                throw new IllegalArgumentException(
                    "invalid target-free search budget");
            }
        }

        public static Budget small() {
            return new Budget(2, 32, 32, 256, 48, 12);
        }
    }

    public enum TransitionDisposition {
        ACCEPTED_NEW_STATE,
        DUPLICATE_REPRESENTATION,
        STATE_BUDGET_EXHAUSTED
    }

    public record State(
        int sequence,
        String stateHash,
        String expression,
        int depth,
        String parentStateHash,
        String incomingRuleId,
        String incomingApplicationKey,
        List<String> pathRuleIds,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        List<String> packIds,
        boolean equivalencePreserving,
        SemanticDescriptionMetrics metrics
    ) {
        public State {
            if (sequence < 1 || depth < 0) {
                throw new IllegalArgumentException(
                    "invalid state sequence or depth");
            }
            stateHash = requireHash(stateHash, "stateHash");
            expression = RepresentationCandidateAssessment.requireText(
                expression, "expression");
            parentStateHash = optional(parentStateHash);
            incomingRuleId = optional(incomingRuleId);
            incomingApplicationKey = optional(incomingApplicationKey);
            pathRuleIds = List.copyOf(pathRuleIds);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            assumptions = normalized(assumptions);
            packIds = normalized(packIds);
            metrics = Objects.requireNonNull(metrics, "metrics");
            String expectedStateHash = TargetFreeRepresentationSearch
                .stateHash(expression, assumptions);
            if (!expectedStateHash.equals(stateHash)) {
                throw new IllegalArgumentException(
                    "state hash does not match expression and assumptions");
            }
            boolean hasLineage = !parentStateHash.isEmpty()
                && !incomingRuleId.isEmpty()
                && !incomingApplicationKey.isEmpty()
                && !pathRuleIds.isEmpty()
                && !primitiveRuleIds.isEmpty();
            if (depth == 0 && (hasLineage
                    || !parentStateHash.isEmpty()
                    || !incomingRuleId.isEmpty()
                    || !incomingApplicationKey.isEmpty()
                    || !pathRuleIds.isEmpty()
                    || !primitiveRuleIds.isEmpty()
                    || !assumptions.isEmpty()
                    || !packIds.isEmpty())) {
                throw new IllegalArgumentException(
                    "root state must not contain lineage");
            }
            if (depth > 0 && !hasLineage) {
                throw new IllegalArgumentException(
                    "non-root state requires lineage");
            }
        }

        static State root(
            String expression,
            SemanticDescriptionMetrics metrics
        ) {
            return new State(
                1,
                TargetFreeRepresentationSearch.stateHash(
                    expression, List.of()),
                expression, 0,
                "", "", "", List.of(), List.of(), List.of(),
                List.of(), true, metrics);
        }

        static State successor(
            int sequence,
            String stateHash,
            String expression,
            State parent,
            Transformation transformation,
            List<String> targetAssumptions,
            SemanticDescriptionMetrics metrics
        ) {
            return new State(
                sequence,
                stateHash,
                expression,
                parent.depth() + 1,
                parent.stateHash(),
                transformation.rule(),
                transformation.applicationKey(),
                append(parent.pathRuleIds(),
                    List.of(transformation.rule())),
                append(parent.primitiveRuleIds(),
                    transformation.primitiveRuleIds()),
                targetAssumptions,
                union(parent.packIds(),
                    List.of(transformation.packId())),
                parent.equivalencePreserving()
                    && transformation
                        .equivalencePreservingByConstruction(),
                metrics
            );
        }
    }

    public record Transition(
        int sequence,
        String fromStateHash,
        String toStateHash,
        String transformedExpression,
        String ruleId,
        String applicationKey,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        String packId,
        String license,
        boolean equivalencePreserving,
        TransitionDisposition disposition
    ) {
        public Transition {
            if (sequence < 1) {
                throw new IllegalArgumentException(
                    "transition sequence must be positive");
            }
            fromStateHash = requireHash(
                fromStateHash, "fromStateHash");
            toStateHash = requireHash(toStateHash, "toStateHash");
            transformedExpression =
                RepresentationCandidateAssessment.requireText(
                    transformedExpression, "transformedExpression");
            ruleId = RepresentationCandidateAssessment.requireText(
                ruleId, "ruleId");
            applicationKey =
                RepresentationCandidateAssessment.requireText(
                    applicationKey, "applicationKey");
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            assumptions = normalized(assumptions);
            String expectedTargetHash =
                TargetFreeRepresentationSearch.stateHash(
                    transformedExpression, assumptions);
            if (!expectedTargetHash.equals(toStateHash)) {
                throw new IllegalArgumentException(
                    "transition target hash does not match representation");
            }
            packId = RepresentationCandidateAssessment.requireText(
                packId, "packId");
            license = RepresentationCandidateAssessment.requireText(
                license, "license");
            disposition = Objects.requireNonNull(
                disposition, "disposition");
        }

        static Transition of(
            int sequence,
            String fromStateHash,
            String toStateHash,
            String expression,
            Transformation transformation,
            List<String> targetAssumptions,
            TransitionDisposition disposition
        ) {
            return new Transition(
                sequence,
                fromStateHash,
                toStateHash,
                expression,
                transformation.rule(),
                transformation.applicationKey(),
                transformation.primitiveRuleIds(),
                targetAssumptions,
                transformation.packId(),
                transformation.license(),
                transformation.equivalencePreservingByConstruction(),
                disposition
            );
        }
    }

    public record SearchContent(
        String schema,
        String sourceExpression,
        String sourceStateHash,
        String ruleInventoryHash,
        Budget budget,
        int exploredStateCount,
        int generatedTransitionCount,
        boolean truncated,
        List<State> states,
        List<Transition> transitions,
        List<String> paretoStateHashes
    ) {
        public SearchContent {
            schema = RepresentationCandidateAssessment.requireText(
                schema, "schema");
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported target-free search schema: " + schema);
            }
            sourceExpression =
                RepresentationCandidateAssessment.requireText(
                    sourceExpression, "sourceExpression");
            sourceStateHash = requireHash(
                sourceStateHash, "sourceStateHash");
            ruleInventoryHash = requireHash(
                ruleInventoryHash, "ruleInventoryHash");
            budget = Objects.requireNonNull(budget, "budget");
            states = List.copyOf(states);
            transitions = List.copyOf(transitions);
            paretoStateHashes = List.copyOf(paretoStateHashes);
            validateBalance();
        }

        private void validateBalance() {
            if (exploredStateCount < 1
                    || exploredStateCount > states.size()
                    || exploredStateCount > budget.maxExploredStates()
                    || generatedTransitionCount != transitions.size()
                    || transitions.size()
                        > budget.maxGeneratedTransitions()
                    || states.isEmpty()
                    || states.size() > budget.maxRetainedStates()) {
                throw new IllegalArgumentException(
                    "target-free search resources do not balance");
            }
            State root = states.getFirst();
            if (root.sequence() != 1
                    || root.depth() != 0
                    || !root.expression().equals(sourceExpression)
                    || !root.stateHash().equals(sourceStateHash)) {
                throw new IllegalArgumentException(
                    "target-free search root does not balance");
            }

            Map<String, State> statesByHash = new LinkedHashMap<>();
            Set<String> stateDescriptors = new HashSet<>();
            for (int index = 0; index < states.size(); index++) {
                State state = states.get(index);
                if (state.sequence() != index + 1
                        || state.depth() > budget.maxDepth()
                        || statesByHash.put(
                            state.stateHash(), state) != null
                        || !stateDescriptors.add(stateDescriptor(
                            state.expression(), state.assumptions()))) {
                    throw new IllegalArgumentException(
                        "target-free search states do not balance");
                }
            }

            Set<String> acceptedTargets = new HashSet<>();
            for (int index = 0; index < transitions.size(); index++) {
                Transition transition = transitions.get(index);
                if (transition.sequence() != index + 1
                        || !statesByHash.containsKey(
                            transition.fromStateHash())) {
                    throw new IllegalArgumentException(
                        "target-free search transitions do not balance");
                }
                boolean targetRetained = statesByHash.containsKey(
                    transition.toStateHash());
                if (transition.disposition()
                        == TransitionDisposition.STATE_BUDGET_EXHAUSTED) {
                    if (targetRetained || !truncated) {
                        throw new IllegalArgumentException(
                            "state-budget transition must be unretained");
                    }
                } else if (!targetRetained) {
                    throw new IllegalArgumentException(
                        "accepted or duplicate transition target is missing");
                }
                if (transition.disposition()
                        == TransitionDisposition.ACCEPTED_NEW_STATE
                        && !acceptedTargets.add(
                            transition.toStateHash())) {
                    throw new IllegalArgumentException(
                        "state has multiple accepting transitions");
                }
            }

            for (State state : states.stream()
                    .filter(value -> value.depth() > 0)
                    .toList()) {
                State parent = statesByHash.get(
                    state.parentStateHash());
                if (parent == null
                        || parent.sequence() >= state.sequence()
                        || parent.depth() + 1 != state.depth()
                        || !acceptedTargets.contains(
                            state.stateHash())) {
                    throw new IllegalArgumentException(
                        "target-free state lineage does not balance");
                }
                boolean lineageTransition = transitions.stream()
                    .anyMatch(transition ->
                        transition.disposition()
                            == TransitionDisposition.ACCEPTED_NEW_STATE
                            && transition.fromStateHash().equals(
                                state.parentStateHash())
                            && transition.toStateHash().equals(
                                state.stateHash())
                            && transition.ruleId().equals(
                                state.incomingRuleId())
                            && transition.applicationKey().equals(
                                state.incomingApplicationKey()));
                if (!lineageTransition) {
                    throw new IllegalArgumentException(
                        "target-free state lacks accepting lineage");
                }
            }

            List<String> expectedPareto =
                TargetFreeRepresentationSearch.paretoStateHashes(states);
            if (!expectedPareto.equals(paretoStateHashes)
                    || new HashSet<>(paretoStateHashes).size()
                        != paretoStateHashes.size()) {
                throw new IllegalArgumentException(
                    "target-free Pareto archive does not balance");
            }
        }

        public List<State> candidateStates() {
            return states.stream()
                .filter(state -> state.depth() > 0)
                .toList();
        }

        public State state(String stateHash) {
            return states.stream()
                .filter(state -> state.stateHash().equals(stateHash))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "unknown state hash: " + stateHash));
        }
    }

    public record SearchResult(
        SearchContent content,
        String contentHash
    ) {
        public SearchResult {
            content = Objects.requireNonNull(content, "content");
            contentHash = requireHash(contentHash, "contentHash");
            String expected = KnownStructureCatalog.sha256(json(content));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "target-free search hash does not match content");
            }
        }

        static SearchResult create(SearchContent content) {
            return new SearchResult(
                content,
                KnownStructureCatalog.sha256(json(content))
            );
        }

        public String toCanonicalJson() {
            return json(this);
        }
    }

    private record RuleOrder(
        RewriteRule rule,
        String contentHash
    ) {
    }
}
