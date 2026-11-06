package de.regelsuche.evolution;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MovePriorityPolicy;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.SearchContinuationContract;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.StateValue;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepared, experimental task solving using the existing frontier and independently checked schemata.
 * A declared quality goal ends work; an applicable schema alone does not. Unsolved remainders retain
 * the primitive fallback in the same frontier and budget. This is not exhaustive simplification.
 */
public final class CheckedSchemaTaskSolver {
    public static final String REVISION = "regelsuche.checked-schema-task-solver/v1";
    private static final int CODEC_ENTRIES = 256;
    private static final long CODEC_CHARACTERS = 2_000_000;

    /** No search starts when its input is outside the checked polynomial fragment. */
    public static final class UnsupportedTaskException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final long inspectionWork;
        private final long routingWork;
        private UnsupportedTaskException(long inspectionWork, long routingWork, Throwable cause) {
            super("task source is outside the checked polynomial domain", cause);
            this.inspectionWork = inspectionWork;
            this.routingWork = routingWork;
        }
        public long inspectionWork() { return inspectionWork; }
        public long routingWork() { return routingWork; }
        public boolean accountingComplete() { return false; }
        UnsupportedTaskException withRoutingWork(long extra) {
            return extra == 0 ? this : new UnsupportedTaskException(inspectionWork, Math.addExact(routingWork, extra), getCause());
        }
    }

    /** A failed provider reports its measured local work; the unfinished search prefix remains unknown. */
    public static final class ProviderFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final long observedProviderWork;
        ProviderFailure(long work, RuntimeException cause) {
            super("task provider failed; unfinished search work is unknown", cause);
            this.observedProviderWork = work;
        }
        public long observedProviderWork() { return observedProviderWork; }
        public boolean accountingComplete() { return false; }
    }

    /** The caller fixes this measured criterion before solving, not from the observed answer. */
    public record Goal(TypedSourceOnlySearch.Objective objective, long maximumOutputScore) {
        public Goal { Objects.requireNonNull(objective, "objective"); }
        public static Goal atMostNodes(int maximumNodes) {
            if (maximumNodes < 1) throw new IllegalArgumentException("node goal must be positive");
            return new Goal(NativeNodeCountObjective.INSTANCE, maximumNodes);
        }
    }

    public record Task(Expr source, List<String> assumptions, Goal goal, MoveSearch.Budget budget) {
        public Task {
            Objects.requireNonNull(source, "source");
            assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /** Issued only after the existing independent final replay has returned successfully. */
    public static final class Solution {
        private final Task task;
        private final TypedSourceOnlySearch.Result evidence;
        private final boolean exactPolynomialSteps;
        private Solution(Task task, TypedSourceOnlySearch.Result evidence, boolean exactPolynomialSteps) {
            this.task = task;
            this.evidence = evidence;
            this.exactPolynomialSteps = exactPolynomialSteps;
        }
        public Task task() { return task; }
        public TypedSourceOnlySearch.Result evidence() { return evidence; }
        public boolean solved() {
            return (!exactPolynomialSteps || !(task.goal().objective() instanceof StructuralTaskObjective)
                    || evidence.inputScore() != StructuralTaskObjective.UNSUPPORTED_SCORE)
                && evidence.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED
                && evidence.outputScore() <= task.goal().maximumOutputScore()
                && evidence.withinBudget();
        }
        public long learnedApplications() {
            return evidence.witness().stream()
                .filter(step -> step.move().sourceKind() == SearchMove.SourceKind.LEARNED).count();
        }
        public long primitiveSteps() {
            return evidence.witness().stream().mapToLong(step -> step.move().primitiveStepCount()).sum();
        }
    }

    private final TypedLearnedMoveInventory.SearchSession session;
    private final CheckedSchemaMatcherPlan.Compilation preparation;
    private final boolean breadthFirstPriority;
    private final boolean exactPolynomialSteps;
    private final boolean expansionRulesAttached;
    private final boolean expansionBridgesForOtherGoals;

    CheckedSchemaTaskSolver(TypedLearnedMoveInventory.SearchSession session,
            CheckedSchemaMatcherPlan.Compilation preparation) {
        this(session, preparation, false, false, false, false);
    }

    private CheckedSchemaTaskSolver(TypedLearnedMoveInventory.SearchSession session,
            CheckedSchemaMatcherPlan.Compilation preparation, boolean breadthFirstPriority, boolean exactPolynomialSteps,
            boolean expansionRulesAttached,
            boolean expansionBridgesForOtherGoals) {
        this.session = Objects.requireNonNull(session, "session");
        this.preparation = preparation;
        this.breadthFirstPriority = breadthFirstPriority;
        this.exactPolynomialSteps = exactPolynomialSteps;
        this.expansionRulesAttached = expansionRulesAttached;
        this.expansionBridgesForOtherGoals = expansionBridgesForOtherGoals;
    }

    CheckedSchemaTaskSolver withConstructions(List<StructuralConstructionPrograms.Operation> operations,
            boolean prioritizeBreadth) {
        var providers = new java.util.ArrayList<>(session.providers());
        var verifiers = new java.util.HashMap<String, TypedMoveSearch.Verifier>();
        for (var operation : operations) {
            var provider = new StructuralConstructionProvider(operation);
            providers.add(preparation == null ? provider : provider.incremental());
            verifiers.put(provider.descriptor().id(), provider.verifier());
        }
        var augmented = new TypedLearnedMoveInventory.SearchSession(providers, session.primitiveCaches(),
            (source, move, context) -> verifiers.getOrDefault(move.ruleId(), session.verifier()).verify(source, move, context));
        return new CheckedSchemaTaskSolver(augmented, preparation, prioritizeBreadth, exactPolynomialSteps,
            expansionRulesAttached, expansionBridgesForOtherGoals);
    }

    CheckedSchemaTaskSolver withExactPolynomialSteps() {
        return new CheckedSchemaTaskSolver(session, preparation, breadthFirstPriority, true,
            expansionRulesAttached, expansionBridgesForOtherGoals);
    }

    CheckedSchemaTaskSolver withExpansionRules(boolean bridgesForOtherGoals) {
        return new CheckedSchemaTaskSolver(StructuralExpansionRules.attach(session, preparation != null), preparation,
            breadthFirstPriority, exactPolynomialSteps, true, bridgesForOtherGoals);
    }

    /** Paid once when this immutable solver is prepared; never silently multiplied or discarded. */
    public long preparationWork() { return preparation == null ? 0 : preparation.workUnits(); }
    public Optional<CheckedSchemaMatcherPlan.Compilation> preparationReceipt() {
        return Optional.ofNullable(preparation);
    }

    /** Fresh cursors, scoped codec reuse, one budget, and final replay for every invocation. */
    public Solution solve(Task task) {
        Objects.requireNonNull(task, "task");
        var original = task.goal().objective();
        boolean checkedSteps = exactPolynomialSteps || StructuralTaskObjective.requiresExactPolynomialSteps(original);
        if (checkedSteps) StructuralCheckedDomain.requireUnobserved();
        var sourceInspection = checkedSteps ? inspectSource(task) : new SourceInspection(0, 0);
        long inspection = sourceInspection.work();
        TypedSourceOnlySearch.Objective objective = (breadthFirstPriority || StructuralTaskObjective.requiresExactPolynomialSteps(original))
            && original instanceof StructuralTaskObjective structural
            ? structural::evaluateWithProductWork : original;
        if (inspection > 0) {
            var delegate = objective;
            objective = state -> {
                if (state.searchDepth() == 0 && Math.addExact(inspection, sourceInspection.nodes()) >= task.budget().totalWork()) {
                    // Source and the existing root node-count assessment precede the objective.
                    return new TypedSourceOnlySearch.Score(Long.MAX_VALUE, Math.addExact(inspection, 1));
                }
                var score = delegate.evaluate(state);
                return state.searchDepth() == 0
                    ? new TypedSourceOnlySearch.Score(score.value(), Math.addExact(score.work(), inspection)) : score;
            };
        }
        var selectedObjective = objective;
        var result = CompiledAstReplayCodec.withExpressionCache(CODEC_ENTRIES, CODEC_CHARACTERS,
            () -> new TypedSourceOnlySearch().searchUntil(problem(task), selectedObjective,
                task.goal().maximumOutputScore(), SearchContinuationContract.PATH_SENSITIVE));
        return new Solution(task, result, checkedSteps);
    }

    private record SourceInspection(long work, int nodes) { }

    private static SourceInspection inspectSource(Task task) {
        var work = new CheckedSchemaSupport.Work();
        try {
            int nodes = StructuralCheckedDomain.inspect(task.source(), work);
            return new SourceInspection(work.units, nodes);
        } catch (CheckedSchemaSupport.DomainRejected unsupported) {
            throw new UnsupportedTaskException(work.units, 0, unsupported);
        }
    }

    // The primitive control uses its cheaper batch scheduler; it is not handicapped by unused cursor machinery.
    TypedMoveSearch.Problem problem(Task task) {
        return new TypedMoveSearch.Problem(task.source(),
            TypedMoveSearch.Context.sourceOnly(task.assumptions(), MoveContext.Phase.FROZEN_EVALUATION),
            providersFor(task), policyFor(task),
            exactPolynomialSteps || StructuralTaskObjective.requiresExactPolynomialSteps(task.goal().objective())
                ? StructuralStepVerifier.checked(session.verifier()) : session.verifier(), state -> 0,
            MoveSearch.Mode.FAST, preparation == null ? MoveSearch.Scheduling.STAGED
                : MoveSearch.Scheduling.STAGED_INCREMENTAL, task.budget(),
            (state, context) -> {
                var size = NativeNodeCountObjective.INSTANCE.evaluate(state);
                double priority = breadthFirstPriority ? -state.searchDepth() : -size.value();
                return new StateValue.Assessment(Math.toIntExact(size.value()), priority, size.work(), 0, Map.of());
            });
    }

    private List<MoveProvider> providersFor(Task task) {
        if (!expansionRulesAttached || expansionBridgesForOtherGoals
                || task.goal().objective() == StructuralTaskObjective.EXPANDED_POLYNOMIAL) return session.providers();
        return session.providers().stream().filter(provider -> !StructuralExpansionRules.isExpansionProvider(provider.descriptor())).toList();
    }

    private KnowledgeFirst policyFor(Task task) {
        if (!breadthFirstPriority) return KnowledgeFirst.INSTANCE;
        return expansionRulesAttached && task.goal().objective() != StructuralTaskObjective.EXPANDED_POLYNOMIAL
            ? KnowledgeFirst.STRUCTURAL_DEFER_EXPANSION : KnowledgeFirst.STRUCTURAL_EXPLORATION;
    }

    /** Ordering alone grants no mathematical authority; the normal verifier still admits each edge. */
    private enum KnowledgeFirst implements TypedMoveSearch.TypedPolicy {
        INSTANCE, STRUCTURAL_EXPLORATION, STRUCTURAL_DEFER_EXPANSION;
        @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
        @Override public double providerScore(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
            if (this == INSTANCE) return 0;
            String id = provider.id();
            if (id.endsWith(":AUXILIARY_FACTOR")) return 4;
            if (id.endsWith(":FACTOR_DIFFERENCE")) return 3;
            if (id.endsWith(":RECOGNIZE_SQUARE")) return 2;
            if (id.endsWith(":RECOGNIZE_METERED_SQUARE")) return 2;
            if (id.endsWith(":RECOGNIZE_RATIONAL_SQUARE")) return 2;
            if (id.endsWith(":EXPOSE_SQUARE")) return 1;
            if (id.endsWith(":RATIONAL_EXPOSE_SQUARE")) return 1;
            return 0;
        }
        @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
            if (StructuralExpansionRules.isExpansionProvider(provider))
                return this == STRUCTURAL_DEFER_EXPANSION ? Stage.EXPENSIVE : Stage.NORMAL_PRIMITIVE;
            return provider.sourceKind() == SearchMove.SourceKind.LEARNED ? Stage.VALUABLE_LEARNED
                : MovePriorityPolicy.INVENTORY_ORDER.stage(provider, state, context);
        }
    }
}
