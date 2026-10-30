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
        private Solution(Task task, TypedSourceOnlySearch.Result evidence) {
            this.task = task;
            this.evidence = evidence;
        }
        public Task task() { return task; }
        public TypedSourceOnlySearch.Result evidence() { return evidence; }
        public boolean solved() {
            return evidence.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED
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

    CheckedSchemaTaskSolver(TypedLearnedMoveInventory.SearchSession session,
            CheckedSchemaMatcherPlan.Compilation preparation) {
        this.session = Objects.requireNonNull(session, "session");
        this.preparation = preparation;
    }

    CheckedSchemaTaskSolver withConstructions() {
        var providers = new java.util.ArrayList<>(session.providers());
        var verifiers = new java.util.HashMap<String, TypedMoveSearch.Verifier>();
        for (var operation : StructuralConstructionPrograms.Operation.values()) {
            var provider = new StructuralConstructionProvider(operation);
            providers.add(preparation == null ? provider : provider.incremental());
            verifiers.put(provider.descriptor().id(), provider.verifier());
        }
        var augmented = new TypedLearnedMoveInventory.SearchSession(providers, session.primitiveCaches(),
            (source, move, context) -> verifiers.getOrDefault(move.ruleId(), session.verifier()).verify(source, move, context));
        return new CheckedSchemaTaskSolver(augmented, preparation);
    }

    /** Paid once when this immutable solver is prepared; never silently multiplied or discarded. */
    public long preparationWork() { return preparation == null ? 0 : preparation.workUnits(); }
    public Optional<CheckedSchemaMatcherPlan.Compilation> preparationReceipt() {
        return Optional.ofNullable(preparation);
    }

    /** Fresh cursors, scoped codec reuse, one budget, and final replay for every invocation. */
    public Solution solve(Task task) {
        Objects.requireNonNull(task, "task");
        var result = CompiledAstReplayCodec.withExpressionCache(CODEC_ENTRIES, CODEC_CHARACTERS,
            () -> new TypedSourceOnlySearch().searchUntil(problem(task), task.goal().objective(),
                task.goal().maximumOutputScore(), SearchContinuationContract.PATH_SENSITIVE));
        return new Solution(task, result);
    }

    // The primitive control uses its cheaper batch scheduler; it is not handicapped by unused cursor machinery.
    TypedMoveSearch.Problem problem(Task task) {
        return new TypedMoveSearch.Problem(task.source(),
            TypedMoveSearch.Context.sourceOnly(task.assumptions(), MoveContext.Phase.FROZEN_EVALUATION),
            session.providers(), KnowledgeFirst.INSTANCE, session.verifier(), state -> 0,
            MoveSearch.Mode.FAST, preparation == null ? MoveSearch.Scheduling.STAGED
                : MoveSearch.Scheduling.STAGED_INCREMENTAL, task.budget(),
            (state, context) -> {
                var size = NativeNodeCountObjective.INSTANCE.evaluate(state);
                return new StateValue.Assessment(Math.toIntExact(size.value()), -size.value(), size.work(), 0, Map.of());
            });
    }

    /** Ordering alone grants no mathematical authority; the normal verifier still admits each edge. */
    private enum KnowledgeFirst implements TypedMoveSearch.TypedPolicy {
        INSTANCE;
        @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
        @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
            return provider.sourceKind() == SearchMove.SourceKind.LEARNED ? Stage.VALUABLE_LEARNED
                : MovePriorityPolicy.INVENTORY_ORDER.stage(provider, state, context);
        }
    }
}
