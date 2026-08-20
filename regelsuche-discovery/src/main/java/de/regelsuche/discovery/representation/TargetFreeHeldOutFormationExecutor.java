package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.CANONICALIZER;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.EXACT_CHECKPOINT;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonicalCandidateLineages;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.enumStateKey;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.union;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.uniqueProposals;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.visibleSelection;

import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class TargetFreeHeldOutFormationExecutor {
    private TargetFreeHeldOutFormationExecutor() {
    }

    static FreezeRow executeRow(
        PlanRow row,
        CaseSpec benchmarkCase,
        PolicySpec policy
    ) {
        if (!"UNION_WITH_RETAINED_STATE_ASSUMPTIONS".equals(
                policy.initialAssumptionPolicy())) {
            throw new IllegalArgumentException(
                "unsupported initial assumption policy");
        }
        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                benchmarkCase.informationTrack(),
                visibleSelection(benchmarkCase));
        ExecutionResult result = switch (policy.adapterInterface()) {
            case "TARGET_FREE_REPRESENTATION_SEARCH" ->
                executeEnumeration(
                    benchmarkCase, policy, boundary, row.checkpoint());
            case "SEARCH_STRATEGY" -> executeSearchStrategy(
                benchmarkCase, policy, boundary, row.checkpoint());
            default -> throw new IllegalArgumentException(
                "unsupported policy adapter interface: "
                    + policy.adapterInterface());
        };

        List<RepresentationCandidateProposal> proposals = uniqueProposals(
            benchmarkCase.sourceExpression(), result.candidates());
        RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt
            receipt = boundary.freezeCandidates(proposals);
        if (receipt.candidateCount() != proposals.size()) {
            throw new IllegalStateException(
                "candidate-set receipt count differs for "
                    + row.configurationId());
        }
        return FreezeRow.create(
            row,
            benchmarkCase,
            policy,
            boundary,
            result,
            receipt,
            proposals.size());
    }

    private static ExecutionResult executeEnumeration(
        CaseSpec benchmarkCase,
        PolicySpec policy,
        RepresentationDiscoveryInformationBoundary boundary,
        int checkpoint
    ) {
        requireEnumerationPolicy(policy);
        CheckpointEngine engine = checkpointEngine(
            benchmarkCase, boundary, checkpoint);
        int sourceSize = CANONICALIZER.astNodeCount(
            benchmarkCase.sourceExpression());
        EnumState root = EnumState.root(
            benchmarkCase.sourceExpression(),
            benchmarkCase.assumptions());
        List<EnumState> states = new ArrayList<>();
        states.add(root);
        ArrayDeque<EnumState> frontier = new ArrayDeque<>();
        frontier.add(root);
        Set<String> seen = new HashSet<>();
        seen.add(enumStateKey(root));
        TreeSet<String> limits = new TreeSet<>();
        int explored = 0;

        while (!frontier.isEmpty()
                && explored < benchmarkCase.budget().maxExploredStates()
                && !engine.checkpointReached()) {
            EnumState current = frontier.removeFirst();
            explored++;
            if (current.depth() >= benchmarkCase.budget().maxDepth()) {
                limits.add("MAX_DEPTH");
                continue;
            }
            List<Transformation> transformations = engine.transform(
                current.expression());
            for (Transformation transformation : transformations) {
                int depth = current.depth()
                    + transformation.primitiveStepCount();
                if (depth > benchmarkCase.budget().maxDepth()) {
                    limits.add("MAX_DEPTH");
                    continue;
                }
                String expression =
                    transformation.transformedExpression();
                List<String> assumptions = union(
                    current.assumptions(), transformation.assumptions());
                boolean complexityIncrease =
                    current.temporaryComplexityIncrease()
                        || CANONICALIZER.astNodeCount(expression) > sourceSize;
                EnumState discovered = current.successor(
                    expression,
                    assumptions,
                    transformation,
                    depth,
                    complexityIncrease);
                String key = enumStateKey(discovered);
                if (!seen.add(key)) {
                    continue;
                }
                if (states.size()
                        >= benchmarkCase.budget().maxRetainedStates()) {
                    limits.add("MAX_RETAINED_STATES");
                    continue;
                }
                states.add(discovered);
                frontier.addLast(discovered);
            }
        }
        if (!frontier.isEmpty()
                && explored >= benchmarkCase.budget().maxExploredStates()) {
            limits.add("MAX_EXPLORED_STATES");
        }
        limits.addAll(engine.limitReasons());
        List<CandidateEvidence> candidates = canonicalCandidateLineages(
            states.stream().filter(value -> value.depth() > 0)
                .map(EnumState::toCandidate).toList());
        WorkLedger work = WorkLedger.create(
            checkpoint,
            engine.engineCalls(),
            engine.materializedTransitions(),
            engine.admittedTransitions(),
            engine.admittedPrimitiveSteps(),
            explored,
            states.size());
        return new ExecutionResult(
            candidates,
            work,
            terminalReason(work, frontier.isEmpty(), limits),
            List.copyOf(limits));
    }

    private static ExecutionResult executeSearchStrategy(
        CaseSpec benchmarkCase,
        PolicySpec policy,
        RepresentationDiscoveryInformationBoundary boundary,
        int checkpoint
    ) {
        CheckpointEngine engine = checkpointEngine(
            benchmarkCase, boundary, checkpoint);
        int retainedLimit = Math.min(
            benchmarkCase.budget().maxExploredStates(),
            benchmarkCase.budget().maxRetainedStates());
        SearchProblem problem = new SearchProblem(
            benchmarkCase.sourceExpression(),
            engine,
            new ExpressionScorer(),
            CANONICALIZER,
            new SearchHeuristic(
                benchmarkCase.budget().maxDepth(),
                retainedLimit,
                benchmarkCase.budget()
                    .significantImprovementThreshold(),
                benchmarkCase.budget().maxExpandingSteps(),
                benchmarkCase.budget().maxCandidatesPerState(),
                benchmarkCase.budget().beamWidth()));
        if (problem.target() != null) {
            throw new IllegalStateException(
                "held-out formation attached a target");
        }
        SearchStrategy strategy = instantiateSearchStrategy(policy);
        List<SearchState> states = List.copyOf(strategy.search(problem));
        TreeSet<String> limits = new TreeSet<>(engine.limitReasons());
        if (states.size() >= retainedLimit) {
            limits.add("MAX_RETAINED_OR_EXPLORED_STATES");
        }
        if (states.stream().anyMatch(value ->
                value.depth() >= benchmarkCase.budget().maxDepth())) {
            limits.add("MAX_DEPTH");
        }
        int sourceSize = CANONICALIZER.astNodeCount(
            benchmarkCase.sourceExpression());
        List<CandidateEvidence> candidates = canonicalCandidateLineages(
            states.stream().filter(value -> value.depth() > 0)
                .map(state -> CandidateEvidence.create(
                    state.expression(),
                    union(
                        benchmarkCase.assumptions(),
                        state.assumptions()),
                    state.depth(),
                    state.path(),
                    state.appliedRuleIds(),
                    state.appliedRuleIds(),
                    state.equivalencePreservingFlags().stream()
                        .allMatch(Boolean.TRUE::equals),
                    state.path().stream().anyMatch(expression ->
                        CANONICALIZER.astNodeCount(expression)
                            > sourceSize)))
                .toList());
        WorkLedger work = WorkLedger.create(
            checkpoint,
            engine.engineCalls(),
            engine.materializedTransitions(),
            engine.admittedTransitions(),
            engine.admittedPrimitiveSteps(),
            states.size(),
            Math.max(1, states.size()));
        return new ExecutionResult(
            candidates,
            work,
            terminalReason(work, limits.isEmpty(), limits),
            List.copyOf(limits));
    }

    private static CheckpointEngine checkpointEngine(
        CaseSpec benchmarkCase,
        RepresentationDiscoveryInformationBoundary boundary,
        int checkpoint
    ) {
        return new CheckpointEngine(
            new AstRewriteTransformationEngine(
                boundary.candidateFormationRules(),
                benchmarkCase.budget().maxAstSizeIncreasePerStep(),
                benchmarkCase.budget().maxCandidatesPerState()),
            checkpoint,
            benchmarkCase.budget().maxGeneratedTransitions(),
            benchmarkCase.budget().maxCandidatesPerState());
    }

    private static String terminalReason(
        WorkLedger work,
        boolean frontierExhausted,
        Set<String> limits
    ) {
        if (work.exactCheckpointReached()) {
            return EXACT_CHECKPOINT;
        }
        if (!limits.isEmpty()) {
            return "SEARCH_LIMIT_BEFORE_CHECKPOINT";
        }
        return frontierExhausted
            ? "FRONTIER_EXHAUSTED_BEFORE_CHECKPOINT"
            : "STOPPED_BEFORE_CHECKPOINT";
    }

    private static void requireEnumerationPolicy(PolicySpec policy) {
        if (!TargetFreeRepresentationSearch.class.getName().equals(
                policy.adapter())
                || !"TARGET_FREE_REPRESENTATION_SEARCH".equals(
                    policy.adapterInterface())
                || !"NO_ARGUMENT".equals(policy.adapterConstructor())
                || policy.deterministicSeed() != 0L) {
            throw new IllegalArgumentException(
                "native enumeration policy differs from frozen formation");
        }
    }

    private static SearchStrategy instantiateSearchStrategy(
        PolicySpec policy
    ) {
        try {
            Class<?> type = Class.forName(policy.adapter());
            Object instance = switch (policy.adapterConstructor()) {
                case "NO_ARGUMENT" -> type.getConstructor().newInstance();
                case "LONG_SEED" -> type.getConstructor(long.class)
                    .newInstance(policy.deterministicSeed());
                default -> throw new IllegalArgumentException(
                    "unknown adapter constructor: "
                        + policy.adapterConstructor());
            };
            if (!(instance instanceof SearchStrategy strategy)) {
                throw new IllegalArgumentException(
                    "configured adapter is not a SearchStrategy: "
                        + policy.adapter());
            }
            return strategy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "cannot instantiate target-blind policy "
                    + policy.adapter(), exception);
        }
    }
}
