package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One bounded physical preparation traversal shared by several principals.
 *
 * <p>The traversal deliberately stops before guard authorization and concrete
 * principal replay. Those remain separate authority decisions. What is shared
 * here is principal-independent work: state identity, preparation expansion,
 * AST metadata, deduplication and the bounded traversal budget. Pattern analysis
 * stays keyed by the complete pattern/recognition contract through
 * {@link SharedPreparationTraversal}, so two principal IDs never imply shared
 * semantics merely because their names are related.</p>
 */
final class SharedMultiPrincipalPreparationTraversal {
    static final String REVISION =
        "regelsuche.shared-multi-principal-preparation-traversal/v1";

    private final List<RewriteApplicabilitySchema> principals;
    private final PatternTargetedLocalBridgeSearch.Budget budget;
    private final SharedPreparationTraversal shared;

    SharedMultiPrincipalPreparationTraversal(
        List<RewriteApplicabilitySchema> principalSchemas,
        List<? extends RewriteRule> preparationRules,
        PatternTargetedLocalBridgeSearch.Budget budget
    ) {
        this.principals = validatePrincipals(principalSchemas);
        validateNoPrincipalInPreparation(this.principals, preparationRules);
        this.budget = Objects.requireNonNull(budget, "budget");
        this.shared = new SharedPreparationTraversal(preparationRules, budget);
    }

    Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        AssumptionSignature sourceAssumptions = normalized(initialAssumptions);
        SharedPreparationTraversal.ParsedExpression source =
            shared.parse(sourceExpression);
        Map<String, PrincipalState> principalStates = new LinkedHashMap<>();
        for (RewriteApplicabilitySchema principal : principals) {
            PatternMatchAnalyzer.Analysis initial = analyze(principal, source);
            principalStates.put(
                principal.ruleId(),
                new PrincipalState(principal, initial));
        }

        ArrayDeque<State> frontier = new ArrayDeque<>();
        Map<String, State> states = new LinkedHashMap<>();
        State root = new State(
            source,
            sourceAssumptions,
            0,
            0,
            null,
            null);
        states.put(root.key(), root);
        frontier.add(root);

        WorkCounter work = new WorkCounter();
        work.maxFrontierSize = 1;
        resolveMatches(root, principalStates, work);

        while (!frontier.isEmpty() && !allResolved(principalStates)) {
            int depth = frontier.getFirst().depth();
            List<State> layer = removeLayer(frontier, depth);
            List<Candidate> candidates = new ArrayList<>();
            for (State current : layer) {
                expand(current, principalStates, candidates, work);
                if (work.reachedLimits.contains("GENERATED_TRANSITIONS")) {
                    break;
                }
            }
            candidates.sort(candidateOrder(principalStates));
            for (Candidate candidate : candidates) {
                State retained = retain(candidate, states, frontier, work);
                if (retained == null) {
                    continue;
                }
                resolveMatches(retained, principalStates, work);
                if (allResolved(principalStates)) {
                    break;
                }
            }
            work.maxFrontierSize = Math.max(
                work.maxFrontierSize,
                frontier.size());
            if (work.reachedLimits.contains("GENERATED_TRANSITIONS")
                    || work.reachedLimits.contains("VISITED_STATES")) {
                break;
            }
        }

        List<PrincipalOutcome> outcomes = principals.stream()
            .map(principal -> outcome(
                principalStates.get(principal.ruleId()), work.reachedLimits))
            .toList();
        return new Evaluation(
            REVISION,
            source.expression(),
            sourceAssumptions,
            outcomes,
            work.finish(states.size(), shared.work()));
    }

    private void expand(
        State current,
        Map<String, PrincipalState> principalStates,
        List<Candidate> candidates,
        WorkCounter work
    ) {
        SharedPreparationTraversal.Expansion expansion =
            shared.expand(current.expression().expression());
        if (current.depth() >= budget.maxDepth()) {
            if (!expansion.transitions().isEmpty()) {
                work.reachedLimits.add("DEPTH");
            }
            return;
        }
        work.expandedStates++;
        List<Candidate> local = new ArrayList<>();
        for (SharedPreparationTraversal.Transition transition
                : expansion.transitions()) {
            if (work.generatedTransitions >= budget.maxGeneratedTransitions()) {
                work.reachedLimits.add("GENERATED_TRANSITIONS");
                break;
            }
            work.generatedTransitions++;
            Candidate candidate = candidate(
                current,
                transition,
                principalStates,
                work);
            if (candidate != null) {
                local.add(candidate);
            }
        }
        local.sort(candidateOrder(principalStates));
        if (local.size() > budget.maxSuccessorsPerState()) {
            work.reachedLimits.add("SUCCESSORS_PER_STATE");
            local = local.subList(0, budget.maxSuccessorsPerState());
        }
        candidates.addAll(local);
    }

    private Candidate candidate(
        State parent,
        SharedPreparationTraversal.Transition transition,
        Map<String, PrincipalState> principalStates,
        WorkCounter work
    ) {
        Transformation transformation = transition.transformation();
        if (!transformation.equivalencePreservingByConstruction()) {
            work.reachedLimits.add("UNSAFE_PREPARATION_OUTPUT");
            return null;
        }
        if (principals.stream().anyMatch(value ->
                value.ruleId().equals(transformation.rule()))) {
            work.reachedLimits.add("PRINCIPAL_PRESENT_IN_PREPARATION_OUTPUT");
            return null;
        }
        int primitiveWork = parent.primitivePathWork()
            + transformation.primitiveStepCount();
        if (primitiveWork > budget.maxPrimitiveSteps()) {
            work.reachedLimits.add("PRIMITIVE_STEPS");
            return null;
        }
        SharedPreparationTraversal.ParsedExpression target = transition.target();
        if (target.expressionNodes() > budget.maxExpressionNodes()) {
            work.reachedLimits.add("EXPRESSION_NODES");
            return null;
        }
        AssumptionSignature assumptions = AssumptionSignature.merge(
            parent.assumptions(),
            AssumptionSignature.ofExpressions(transformation.assumptions()));
        Map<String, PatternMatchAnalyzer.Analysis> analyses =
            analysesForUnresolved(target, principalStates, work);
        return new Candidate(
            parent,
            transformation,
            target,
            assumptions,
            parent.depth() + 1,
            primitiveWork,
            target.expressionNodes() - parent.expression().expressionNodes(),
            analyses);
    }

    private Map<String, PatternMatchAnalyzer.Analysis> analysesForUnresolved(
        SharedPreparationTraversal.ParsedExpression expression,
        Map<String, PrincipalState> principalStates,
        WorkCounter work
    ) {
        Map<String, PatternMatchAnalyzer.Analysis> result = new LinkedHashMap<>();
        for (PrincipalState state : principalStates.values()) {
            if (state.terminal != null) {
                continue;
            }
            PatternMatchAnalyzer.Analysis analysis = analyze(
                state.principal,
                expression);
            work.principalAnalysisRequests++;
            recordAnalysisLimit(analysis, work);
            result.put(state.principal.ruleId(), analysis);
        }
        return Map.copyOf(result);
    }

    private PatternMatchAnalyzer.Analysis analyze(
        RewriteApplicabilitySchema principal,
        SharedPreparationTraversal.ParsedExpression expression
    ) {
        return shared.analyze(
            principal.pattern(),
            principal.recognitionProfile(),
            expression);
    }

    private void resolveMatches(
        State state,
        Map<String, PrincipalState> principalStates,
        WorkCounter work
    ) {
        for (PrincipalState principal : principalStates.values()) {
            if (principal.terminal != null) {
                continue;
            }
            PatternMatchAnalyzer.Analysis analysis = state == null
                ? null
                : analyze(principal.principal, state.expression());
            work.principalAnalysisRequests++;
            recordAnalysisLimit(analysis, work);
            if (analysis.matched()) {
                principal.terminal = new Terminal(
                    state,
                    analysis,
                    path(state));
            }
        }
    }

    private static void recordAnalysisLimit(
        PatternMatchAnalyzer.Analysis analysis,
        WorkCounter work
    ) {
        if (analysis.inconclusive()) {
            work.reachedLimits.add("MATCH_ANALYSIS");
        }
    }

    private State retain(
        Candidate candidate,
        Map<String, State> states,
        ArrayDeque<State> frontier,
        WorkCounter work
    ) {
        String key = stateKey(candidate.expression(), candidate.assumptions());
        if (states.containsKey(key)) {
            work.duplicateTransitions++;
            return null;
        }
        if (states.size() >= budget.maxVisitedStates()) {
            work.reachedLimits.add("VISITED_STATES");
            return null;
        }
        PatternTargetedLocalBridgeSearch.Step step =
            new PatternTargetedLocalBridgeSearch.Step(
                candidate.parent().expression().expression(),
                candidate.expression().expression(),
                candidate.transformation().rule(),
                candidate.transformation().assumptions(),
                candidate.transformation().applicationKey(),
                candidate.transformation().primitiveRuleIds());
        State retained = new State(
            candidate.expression(),
            candidate.assumptions(),
            candidate.depth(),
            candidate.primitivePathWork(),
            candidate.parent(),
            step);
        states.put(key, retained);
        frontier.addLast(retained);
        work.retainedTransitions++;
        return retained;
    }

    private Comparator<Candidate> candidateOrder(
        Map<String, PrincipalState> principalStates
    ) {
        return Comparator
            .comparingInt((Candidate value) ->
                -matchedPrincipalCount(value, principalStates))
            .thenComparingInt(value ->
                -bestMatchedPatternNodes(value, principalStates))
            .thenComparingInt(value ->
                -bestBindingCount(value, principalStates))
            .thenComparingInt(value ->
                minimumResidualCount(value, principalStates))
            .thenComparingInt(value ->
                minimumResidualLowerBound(value, principalStates))
            .thenComparingInt(Candidate::astGrowth)
            .thenComparingInt(Candidate::primitivePathWork)
            .thenComparing(value -> value.expression().structuralFingerprint())
            .thenComparing(value -> value.transformation().rule())
            .thenComparing(value -> value.transformation().applicationKey());
    }

    private static int matchedPrincipalCount(
        Candidate candidate,
        Map<String, PrincipalState> principals
    ) {
        return activeAnalyses(candidate, principals).stream()
            .map(Map.Entry::getValue)
            .mapToInt(value -> value.matched() ? 1 : 0)
            .sum();
    }

    private static int bestMatchedPatternNodes(
        Candidate candidate,
        Map<String, PrincipalState> principals
    ) {
        return activeAnalyses(candidate, principals).stream()
            .map(Map.Entry::getValue)
            .mapToInt(PatternMatchAnalyzer.Analysis::matchedPatternNodes)
            .max()
            .orElse(0);
    }

    private static int bestBindingCount(
        Candidate candidate,
        Map<String, PrincipalState> principals
    ) {
        return activeAnalyses(candidate, principals).stream()
            .map(Map.Entry::getValue)
            .mapToInt(value -> value.bindings().size())
            .max()
            .orElse(0);
    }

    private static int minimumResidualCount(
        Candidate candidate,
        Map<String, PrincipalState> principals
    ) {
        return activeAnalyses(candidate, principals).stream()
            .map(Map.Entry::getValue)
            .mapToInt(value -> value.residualObligations().size())
            .min()
            .orElse(Integer.MAX_VALUE);
    }

    private static int minimumResidualLowerBound(
        Candidate candidate,
        Map<String, PrincipalState> principals
    ) {
        return activeAnalyses(candidate, principals).stream()
            .map(Map.Entry::getValue)
            .mapToInt(SharedMultiPrincipalPreparationTraversal::residualLowerBound)
            .min()
            .orElse(Integer.MAX_VALUE);
    }

    private static List<Map.Entry<String, PatternMatchAnalyzer.Analysis>>
            activeAnalyses(
                Candidate candidate,
                Map<String, PrincipalState> principals
            ) {
        return candidate.analyses().entrySet().stream()
            .filter(entry -> {
                PrincipalState state = principals.get(entry.getKey());
                return state != null && state.terminal == null;
            })
            .toList();
    }

    private static int residualLowerBound(
        PatternMatchAnalyzer.Analysis analysis
    ) {
        return analysis.residualObligations().stream()
            .mapToInt(value -> switch (value.kind()) {
                case LITERAL_MISMATCH -> 1;
                case BINDING_CONFLICT -> 2;
                case SHAPE_MISMATCH -> 3;
                case FUNCTION_SHAPE_MISMATCH -> 4;
            })
            .sum();
    }

    private static List<PatternTargetedLocalBridgeSearch.Step> path(State terminal) {
        ArrayDeque<PatternTargetedLocalBridgeSearch.Step> reversed =
            new ArrayDeque<>();
        State current = terminal;
        while (current.parent() != null) {
            reversed.addFirst(current.incomingStep());
            current = current.parent();
        }
        return List.copyOf(reversed);
    }

    private static List<State> removeLayer(
        ArrayDeque<State> frontier,
        int depth
    ) {
        List<State> result = new ArrayList<>();
        while (!frontier.isEmpty() && frontier.getFirst().depth() == depth) {
            result.add(frontier.removeFirst());
        }
        return result;
    }

    private static boolean allResolved(Map<String, PrincipalState> states) {
        return states.values().stream().allMatch(value -> value.terminal != null);
    }

    private static PrincipalOutcome outcome(
        PrincipalState state,
        Set<String> reachedLimits
    ) {
        if (state.terminal != null) {
            Terminal terminal = state.terminal;
            return new PrincipalOutcome(
                state.principal.ruleId(),
                state.principal.contentHash(),
                terminal.path().isEmpty()
                    ? Status.MATCHED_AT_SOURCE
                    : Status.PREPARED_MATCH,
                PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(
                    state.initial),
                PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(
                    terminal.analysis()),
                terminal.state().expression().expression(),
                terminal.state().assumptions(),
                terminal.path());
        }
        Status status = reachedLimits.isEmpty()
            ? Status.NO_MATCH_IN_COMPLETE_FROZEN_CLOSURE
            : Status.BUDGET_INCONCLUSIVE;
        return new PrincipalOutcome(
            state.principal.ruleId(),
            state.principal.contentHash(),
            status,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(state.initial),
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(state.initial),
            "",
            AssumptionSignature.ofExpressions(List.of()),
            List.of());
    }

    private static List<RewriteApplicabilitySchema> validatePrincipals(
        List<RewriteApplicabilitySchema> supplied
    ) {
        Objects.requireNonNull(supplied, "principalSchemas");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one principal schema is required");
        }
        Map<String, RewriteApplicabilitySchema> byId = new LinkedHashMap<>();
        for (RewriteApplicabilitySchema schema : supplied) {
            RewriteApplicabilitySchema checked = Objects.requireNonNull(
                schema, "principal schema");
            if (!checked.executor().isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "principal must preserve equivalence: " + checked.ruleId());
            }
            if (byId.put(checked.ruleId(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate principal rule ID: " + checked.ruleId());
            }
        }
        return byId.values().stream()
            .sorted(Comparator
                .comparing(RewriteApplicabilitySchema::ruleId)
                .thenComparing(RewriteApplicabilitySchema::schemaId))
            .toList();
    }

    private static void validateNoPrincipalInPreparation(
        List<RewriteApplicabilitySchema> principals,
        List<? extends RewriteRule> preparationRules
    ) {
        Objects.requireNonNull(preparationRules, "preparationRules");
        Set<String> principalIds = principals.stream()
            .map(RewriteApplicabilitySchema::ruleId)
            .collect(java.util.stream.Collectors.toSet());
        for (RewriteRule rule : preparationRules) {
            RewriteRule checked = Objects.requireNonNull(
                rule, "preparation rule");
            if (principalIds.contains(checked.id())) {
                throw new IllegalArgumentException(
                    "principal rule must not be in preparation inventory: "
                        + checked.id());
            }
        }
    }

    private static AssumptionSignature normalized(AssumptionSignature value) {
        AssumptionSignature checked = Objects.requireNonNull(
            value, "initialAssumptions");
        return AssumptionSignature.ofExpressions(
            checked.normalizedAssumptions());
    }

    private static String stateKey(
        SharedPreparationTraversal.ParsedExpression expression,
        AssumptionSignature assumptions
    ) {
        return expression.structuralFingerprint() + "\u0000"
            + assumptions.fingerprint();
    }

    enum Status {
        MATCHED_AT_SOURCE,
        PREPARED_MATCH,
        NO_MATCH_IN_COMPLETE_FROZEN_CLOSURE,
        BUDGET_INCONCLUSIVE
    }

    record PrincipalOutcome(
        String ruleId,
        String applicabilitySchemaHash,
        Status status,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot initialAnalysis,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot terminalAnalysis,
        String terminalExpression,
        AssumptionSignature terminalAssumptions,
        List<PatternTargetedLocalBridgeSearch.Step> preparationSteps
    ) {
        PrincipalOutcome {
            if (ruleId == null || ruleId.isBlank()
                    || applicabilitySchemaHash == null
                    || !applicabilitySchemaHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "principal outcome identity is invalid");
            }
            status = Objects.requireNonNull(status, "status");
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis, "initialAnalysis");
            terminalAnalysis = Objects.requireNonNull(
                terminalAnalysis, "terminalAnalysis");
            terminalExpression = terminalExpression == null
                ? ""
                : terminalExpression;
            terminalAssumptions = Objects.requireNonNull(
                terminalAssumptions, "terminalAssumptions");
            preparationSteps = List.copyOf(Objects.requireNonNull(
                preparationSteps, "preparationSteps"));
            boolean positive = status == Status.MATCHED_AT_SOURCE
                || status == Status.PREPARED_MATCH;
            if (positive != !terminalExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "only matched principals retain a terminal expression");
            }
            if ((status == Status.PREPARED_MATCH)
                    != !preparationSteps.isEmpty()) {
                throw new IllegalArgumentException(
                    "only prepared matches retain preparation steps");
            }
        }

        boolean matched() {
            return status == Status.MATCHED_AT_SOURCE
                || status == Status.PREPARED_MATCH;
        }
    }

    record Evaluation(
        String revision,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        List<PrincipalOutcome> outcomes,
        Work work
    ) {
        Evaluation {
            if (!REVISION.equals(revision)
                    || sourceExpression == null
                    || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "shared traversal evaluation identity is invalid");
            }
            sourceAssumptions = normalized(sourceAssumptions);
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException(
                    "shared traversal requires principal outcomes");
            }
            work = Objects.requireNonNull(work, "work");
        }

        Optional<PrincipalOutcome> outcome(String ruleId) {
            return outcomes.stream()
                .filter(value -> value.ruleId().equals(ruleId))
                .findFirst();
        }
    }

    record Work(
        int expandedStates,
        int generatedTransitions,
        int discoveredStates,
        int retainedTransitions,
        int duplicateTransitions,
        long principalAnalysisRequests,
        int maxFrontierSize,
        Set<String> reachedLimits,
        SharedPreparationTraversal.Work physicalWork
    ) {
        Work {
            if (expandedStates < 0
                    || generatedTransitions < 0
                    || discoveredStates < 1
                    || retainedTransitions < 0
                    || duplicateTransitions < 0
                    || principalAnalysisRequests < 0
                    || maxFrontierSize < 1
                    || discoveredStates != retainedTransitions + 1) {
                throw new IllegalArgumentException(
                    "shared multi-principal work ledger is invalid");
            }
            reachedLimits = Set.copyOf(Objects.requireNonNull(
                reachedLimits, "reachedLimits"));
            physicalWork = Objects.requireNonNull(physicalWork, "physicalWork");
        }
    }

    private static final class PrincipalState {
        private final RewriteApplicabilitySchema principal;
        private final PatternMatchAnalyzer.Analysis initial;
        private Terminal terminal;

        private PrincipalState(
            RewriteApplicabilitySchema principal,
            PatternMatchAnalyzer.Analysis initial
        ) {
            this.principal = principal;
            this.initial = initial;
        }
    }

    private record Terminal(
        State state,
        PatternMatchAnalyzer.Analysis analysis,
        List<PatternTargetedLocalBridgeSearch.Step> path
    ) {
    }

    private record State(
        SharedPreparationTraversal.ParsedExpression expression,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        State parent,
        PatternTargetedLocalBridgeSearch.Step incomingStep
    ) {
        private State {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(assumptions, "assumptions");
            if (depth < 0 || primitivePathWork < 0) {
                throw new IllegalArgumentException("state counters are invalid");
            }
            if ((parent == null) != (incomingStep == null)) {
                throw new IllegalArgumentException(
                    "state parent and incoming step must appear together");
            }
        }

        String key() {
            return stateKey(expression, assumptions);
        }
    }

    private record Candidate(
        State parent,
        Transformation transformation,
        SharedPreparationTraversal.ParsedExpression expression,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        int astGrowth,
        Map<String, PatternMatchAnalyzer.Analysis> analyses
    ) {
        private Candidate {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(transformation, "transformation");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(assumptions, "assumptions");
            analyses = Map.copyOf(Objects.requireNonNull(analyses, "analyses"));
        }
    }

    private static final class WorkCounter {
        private int expandedStates;
        private int generatedTransitions;
        private int retainedTransitions;
        private int duplicateTransitions;
        private long principalAnalysisRequests;
        private int maxFrontierSize;
        private final Set<String> reachedLimits = new LinkedHashSet<>();

        private Work finish(
            int discoveredStates,
            SharedPreparationTraversal.Work physicalWork
        ) {
            return new Work(
                expandedStates,
                generatedTransitions,
                discoveredStates,
                retainedTransitions,
                duplicateTransitions,
                principalAnalysisRequests,
                maxFrontierSize,
                reachedLimits,
                physicalWork);
        }
    }
}
