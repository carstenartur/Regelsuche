package de.regelsuche.search.program;

import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.WorkAwareTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic interpreter for the Java-internal rewrite program model.
 *
 * <p>Ordinary execution remains primitive-only. The explicit work-budget entry
 * composes the same Transformation/RewriteCandidate model with verifier-backed
 * exact theory. The frozen BudgetedSource protocol retains its separate entry.</p>
 */
public final class RewriteProgramInterpreter {
    // Shared with compiled Source/Sequence execution, including the final kind tie-breaker.
    static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(transformation -> transformation.kind().name());

    private final BudgetedTransformationSourceExecutor
        budgetedSourceExecutor = new BudgetedTransformationSourceExecutor();

    public RewriteExecution execute(RewriteProgram program, String expression) {
        return execute(
            program,
            expression,
            RewriteTraceLevel.OFF,
            RewriteTraceSink.noOp()
        );
    }

    public RewriteExecution execute(
        RewriteProgram program,
        String expression,
        RewriteTraceLevel traceLevel,
        RewriteTraceSink traceSink
    ) {
        return executeInternal(program, expression, null, traceLevel, traceSink);
    }

    public RewriteExecution executeWithWorkBudget(RewriteProgram program, String expression, PathBudget budget) {
        return executeWithWorkBudget(program, expression, budget, RewriteTraceLevel.OFF, RewriteTraceSink.noOp());
    }

    public RewriteExecution executeWithWorkBudget(RewriteProgram program, String expression, PathBudget budget,
                                                 RewriteTraceLevel traceLevel, RewriteTraceSink traceSink) {
        return executeInternal(program, expression, Objects.requireNonNull(budget, "budget"), traceLevel, traceSink);
    }

    private RewriteExecution executeInternal(RewriteProgram program, String expression, PathBudget budget,
                                             RewriteTraceLevel traceLevel, RewriteTraceSink traceSink) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(traceLevel, "traceLevel");
        Objects.requireNonNull(traceSink, "traceSink");
        rejectBudgetedSources(program, budget != null);
        String normalizedExpression = normalizeExpression(expression);
        Context context = new Context(traceLevel, traceSink);
        Evaluation evaluation = evaluate(program, normalizedExpression, context, budget);
        List<RewriteCandidate> candidates = distinct(
            evaluation.candidates(), context);
        return new RewriteExecution(
            candidates,
            evaluation.complete(),
            context.workMetrics(),
            context.observations,
            budget
        );
    }

    /**
     * Executes one explicitly budgeted exact-theory source as a top-level
     * program node.
     *
     * <p>Composition nodes are intentionally not accepted by this v1 entry.
     * Their path-budget propagation requires a separate contract.</p>
     */
    public BudgetedTransformationSourceProgramExecution executeBudgetedSource(
        RewriteProgram.BudgetedSource program,
        String expression,
        long availableMathematicalWorkUnits
    ) {
        Objects.requireNonNull(program, "program");
        if (availableMathematicalWorkUnits < 0) {
            throw new IllegalArgumentException(
                "availableMathematicalWorkUnits must not be negative");
        }
        String normalizedExpression = normalizeExpression(expression);
        BudgetedTransformationSourceExecutor.Execution execution =
            budgetedSourceExecutor.execute(
                program.source(),
                normalizedExpression,
                availableMathematicalWorkUnits);
        return BudgetedTransformationSourceProgramExecution.create(
            program,
            execution);
    }

    /**
     * Executes the frozen exact-theory-only composition lane under explicit
     * path authority and exploration ceilings. No ordinary rewrite is emitted.
     */
    public BudgetedRewriteProgramExecution executeBudgeted(
        RewriteProgram program,
        String expression,
        BudgetedRewriteProgramExecution.PathBudget budget,
        BudgetedRewriteProgramExecution.ExplorationLimits limits
    ) {
        return new BudgetedRewriteProgramEvaluator(budgetedSourceExecutor)
            .execute(program, expression, budget, limits);
    }

    private Evaluation evaluate(
        RewriteProgram program,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        context.programNodeVisited();
        context.summary(
            RewriteTraceEventType.NODE_ENTERED,
            program,
            inputExpression,
            "",
            List.of(),
            0,
            true,
            ""
        );

        Evaluation evaluation = switch (program) {
            case RewriteProgram.Source source ->
                evaluateSource(source, inputExpression, context, budget);
            case RewriteProgram.BudgetedSource ignored ->
                throw new IllegalStateException(
                    "budgeted source passed the unbudgeted preflight");
            case RewriteProgram.Choice choice ->
                evaluateChoice(choice, inputExpression, context, budget);
            case RewriteProgram.FirstApplicable firstApplicable ->
                evaluateFirstApplicable(firstApplicable, inputExpression, context, budget);
            case RewriteProgram.Sequence sequence ->
                evaluateSequence(sequence, inputExpression, context, budget);
            case RewriteProgram.Repeat repeat ->
                evaluateRepeat(repeat, inputExpression, context, budget);
            case RewriteProgram.Require require ->
                evaluateRequire(require, inputExpression, context, budget);
            case RewriteProgram.Prioritize prioritize ->
                evaluatePrioritize(prioritize, inputExpression, context, budget);
            case RewriteProgram.Prune prune ->
                evaluatePrune(prune, inputExpression, context, budget);
        };

        List<String> rules = evaluation.candidates().size() == 1
            ? evaluation.candidates().get(0).ruleIds()
            : List.of();
        String outputExpression = evaluation.candidates().size() == 1
            ? evaluation.candidates().get(0).outputExpression()
            : "";
        context.summary(
            RewriteTraceEventType.NODE_EXITED,
            program,
            inputExpression,
            outputExpression,
            rules,
            evaluation.candidates().size(),
            evaluation.complete(),
            ""
        );
        return evaluation;
    }

    private Evaluation evaluateSource(
        RewriteProgram.Source source,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        context.sourceInvoked();
        if (budget != null && source.engine() instanceof ProgrammedTransformationEngine nested) {
            // Recurse with the same context: a batch projection would lose
            // completeness, discarded evidence and the caller's remaining budget.
            return evaluate(nested.program(), inputExpression, context, budget);
        }
        List<Transformation> emitted;
        if (budget != null && source.engine() instanceof MeasuredTransformationEngine measured) {
            var batch = measured.transformMeasured(inputExpression);
            if (!batch.workMetrics().candidateWork().equals(ExecutionWork.ZERO)) {
                throw new IllegalArgumentException("nested mathematical batches require retained execution observations");
            }
            context.delegatedWork = context.delegatedWork.plus(batch.workMetrics());
            emitted = batch.transformations();
        } else {
            emitted = source.engine() instanceof WorkAwareTransformationEngine workAware
                ? workAware.verifiedTransformations(inputExpression) : source.engine().transform(inputExpression);
        }
        List<Transformation> transformations = new ArrayList<>(
            Objects.requireNonNull(
                emitted,
                "TransformationEngine.transform must not return null"
            )
        );
        transformations.forEach(transformation ->
            Objects.requireNonNull(transformation, "transformation"));
        if (budget == null) Transformation.requirePrimitiveOnly(transformations);
        transformations.sort(TRANSFORMATION_ORDER);
        context.sourceCandidates(transformations.size());

        List<RewriteCandidate> candidates = new ArrayList<>(transformations.size());
        boolean complete = true;
        for (Transformation transformation : transformations) {
            RewriteCandidate candidate = new RewriteCandidate(
                source.id(),
                inputExpression,
                transformation.transformedExpression(),
                List.of(transformation)
            );
            if (budget != null) {
                boolean admitted = budget.admits(candidate.executionWork());
                context.observations.add(new RewriteExecution.SourceObservation(candidate, budget, admitted));
                context.candidateWork = context.candidateWork.plus(candidate.executionWork());
                if (!admitted) {
                    complete = false;
                    context.summary(RewriteTraceEventType.WORK_BUDGET_REJECTED, source, inputExpression,
                        candidate.outputExpression(), candidate.primitiveRuleIds(), 1, false,
                        candidate.provenance().toCanonicalJson());
                    continue;
                }
            }
            candidates.add(candidate);
            context.full(
                transformation.exactTheoryStepCount() > 0 ? RewriteTraceEventType.EXACT_THEORY_CANDIDATE
                    : RewriteTraceEventType.SOURCE_CANDIDATE,
                source,
                inputExpression,
                candidate.outputExpression(),
                transformation.exactTheoryStepCount() > 0 ? candidate.primitiveRuleIds() : candidate.ruleIds(),
                1,
                true,
                transformation.exactTheoryStepCount() > 0 ? candidate.provenance().toCanonicalJson()
                    : transformation.applicationKey()
            );
        }
        return new Evaluation(candidates, complete);
    }

    private Evaluation evaluateChoice(
        RewriteProgram.Choice choice,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        List<RewriteCandidate> candidates = new ArrayList<>();
        boolean complete = true;
        for (RewriteProgram alternative : choice.alternatives()) {
            Evaluation evaluated = evaluate(alternative, inputExpression, context, budget);
            candidates.addAll(evaluated.candidates());
            complete &= evaluated.complete();
        }
        return new Evaluation(distinct(candidates, context), complete);
    }

    private Evaluation evaluateFirstApplicable(
        RewriteProgram.FirstApplicable firstApplicable,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        boolean complete = true;
        for (int index = 0; index < firstApplicable.alternatives().size(); index++) {
            RewriteProgram alternative = firstApplicable.alternatives().get(index);
            Evaluation evaluated = evaluate(alternative, inputExpression, context, budget);
            complete &= evaluated.complete();
            if (evaluated.candidates().isEmpty()) {
                if (budget != null && !evaluated.complete()) return evaluated;
                continue;
            }

            context.alternativeSelected();
            context.summary(
                RewriteTraceEventType.ALTERNATIVE_SELECTED,
                firstApplicable,
                inputExpression,
                "",
                List.of(),
                evaluated.candidates().size(),
                complete,
                alternative.id()
            );
            for (int skipped = index + 1;
                    skipped < firstApplicable.alternatives().size();
                    skipped++) {
                RewriteProgram skippedAlternative =
                    firstApplicable.alternatives().get(skipped);
                context.alternativeSkipped();
                context.full(
                    RewriteTraceEventType.ALTERNATIVE_SKIPPED,
                    skippedAlternative,
                    inputExpression,
                    "",
                    List.of(),
                    0,
                    complete,
                    "first applicable alternative was " + alternative.id()
                );
            }
            return new Evaluation(evaluated.candidates(), complete);
        }
        return new Evaluation(List.of(), complete);
    }

    private Evaluation evaluateSequence(
        RewriteProgram.Sequence sequence,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        Evaluation first = evaluate(sequence.steps().get(0), inputExpression, context, budget);
        List<RewriteCandidate> current = first.candidates();
        boolean complete = first.complete();

        for (int index = 1; index < sequence.steps().size() && !current.isEmpty(); index++) {
            RewriteProgram step = sequence.steps().get(index);
            List<RewriteCandidate> next = new ArrayList<>();
            for (RewriteCandidate prefix : current) {
                Evaluation suffixes = evaluate(step, prefix.outputExpression(), context, remaining(budget, prefix));
                complete &= suffixes.complete();
                for (RewriteCandidate suffix : suffixes.candidates()) {
                    context.composedCandidate();
                    next.add(prefix.append(suffix, sequence.id()));
                }
            }
            current = distinct(next, context);
        }

        return new Evaluation(
            current.stream()
                .map(candidate -> candidate.withOriginNodeId(sequence.id()))
                .toList(),
            complete
        );
    }

    private Evaluation evaluateRepeat(
        RewriteProgram.Repeat repeat,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        List<RewriteCandidate> frontier = List.of();
        List<RewriteCandidate> endpoints = new ArrayList<>();
        boolean complete = true;

        for (int iteration = 1; iteration <= repeat.maxIterations(); iteration++) {
            context.repeatIteration();
            List<RewriteCandidate> next = new ArrayList<>();
            if (iteration == 1) {
                Evaluation evaluated = evaluate(repeat.body(), inputExpression, context, budget);
                next.addAll(evaluated.candidates());
                complete &= evaluated.complete();
            } else {
                for (RewriteCandidate prefix : frontier) {
                    Evaluation suffixes = evaluate(
                        repeat.body(),
                        prefix.outputExpression(),
                        context,
                        remaining(budget, prefix)
                    );
                    complete &= suffixes.complete();
                    for (RewriteCandidate suffix : suffixes.candidates()) {
                        context.composedCandidate();
                        next.add(prefix.append(suffix, repeat.id()));
                    }
                }
            }

            frontier = distinct(next, context);
            if (iteration >= repeat.minIterations()) {
                context.repeatEndpoints(frontier.size());
                endpoints.addAll(frontier.stream()
                    .map(candidate -> candidate.withOriginNodeId(repeat.id()))
                    .toList());
            }
            context.full(
                RewriteTraceEventType.ITERATION_COMPLETED,
                repeat,
                inputExpression,
                "",
                List.of(),
                frontier.size(),
                complete,
                "iteration=" + iteration
            );
            if (frontier.isEmpty()) {
                break;
            }
        }
        return new Evaluation(distinct(endpoints, context), complete);
    }

    private Evaluation evaluateRequire(
        RewriteProgram.Require require,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        Evaluation evaluated = evaluate(require.body(), inputExpression, context, budget);
        List<RewriteCandidate> accepted = new ArrayList<>();
        for (RewriteCandidate candidate : evaluated.candidates()) {
            context.requirementEvaluated();
            if (require.condition().test(candidate)) {
                accepted.add(candidate);
                continue;
            }
            context.requirementRejected();
            context.full(
                RewriteTraceEventType.CANDIDATE_REJECTED,
                require,
                inputExpression,
                candidate.outputExpression(),
                candidate.ruleIds(),
                1,
                evaluated.complete(),
                require.conditionDescription()
            );
        }
        return new Evaluation(accepted, evaluated.complete());
    }

    private Evaluation evaluatePrioritize(
        RewriteProgram.Prioritize prioritize,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        Evaluation evaluated = evaluate(prioritize.body(), inputExpression, context, budget);
        List<RewriteCandidate> candidates = new ArrayList<>(evaluated.candidates());
        context.priorityCandidatesOrdered(candidates.size());
        candidates.sort(
            prioritize.comparator()
                .thenComparing(RewriteCandidate::orderingKey)
        );
        return new Evaluation(candidates, evaluated.complete());
    }

    private Evaluation evaluatePrune(
        RewriteProgram.Prune prune,
        String inputExpression,
        Context context,
        PathBudget budget
    ) {
        Evaluation evaluated = evaluate(prune.body(), inputExpression, context, budget);
        if (evaluated.candidates().size() <= prune.maxCandidates()) {
            return evaluated;
        }

        int removed = evaluated.candidates().size() - prune.maxCandidates();
        context.prunedCandidates(removed);
        List<RewriteCandidate> retained = List.copyOf(
            evaluated.candidates().subList(0, prune.maxCandidates())
        );
        context.summary(
            RewriteTraceEventType.CANDIDATES_PRUNED,
            prune,
            inputExpression,
            "",
            List.of(),
            removed,
            false,
            prune.reason()
        );
        return new Evaluation(retained, false);
    }

    private static PathBudget remaining(PathBudget budget, RewriteCandidate prefix) {
        return budget == null ? null : budget.after(prefix.executionWork());
    }

    private static void rejectBudgetedSources(RewriteProgram program, boolean allowWorkAware) {
        switch (program) {
            case RewriteProgram.Source source -> {
                if (!allowWorkAware && source.engine() instanceof WorkAwareTransformationEngine) {
                    throw new IllegalArgumentException("verified theory Source requires executeWithWorkBudget");
                }
                if (source.engine() instanceof ProgrammedTransformationEngine nested) {
                    rejectBudgetedSources(nested.program(), allowWorkAware);
                }
            }
            case RewriteProgram.BudgetedSource ignored ->
                throw new IllegalArgumentException(
                    "budgeted exact-theory sources require "
                        + "executeBudgetedSource or executeBudgeted with explicit budgets");
            case RewriteProgram.Choice choice ->
                choice.alternatives().forEach(
                    child -> rejectBudgetedSources(child, allowWorkAware));
            case RewriteProgram.FirstApplicable firstApplicable ->
                firstApplicable.alternatives().forEach(
                    child -> rejectBudgetedSources(child, allowWorkAware));
            case RewriteProgram.Sequence sequence ->
                sequence.steps().forEach(
                    child -> rejectBudgetedSources(child, allowWorkAware));
            case RewriteProgram.Repeat repeat ->
                rejectBudgetedSources(repeat.body(), allowWorkAware);
            case RewriteProgram.Require require ->
                rejectBudgetedSources(require.body(), allowWorkAware);
            case RewriteProgram.Prioritize prioritize ->
                rejectBudgetedSources(prioritize.body(), allowWorkAware);
            case RewriteProgram.Prune prune ->
                rejectBudgetedSources(prune.body(), allowWorkAware);
        }
    }

    private static List<RewriteCandidate> distinct(
        List<RewriteCandidate> candidates,
        Context context
    ) {
        Map<RewriteCandidate.Identity, RewriteCandidate> distinct = new LinkedHashMap<>();
        for (RewriteCandidate candidate : candidates) {
            distinct.putIfAbsent(candidate.identity(), candidate);
        }
        context.duplicateCandidatesDropped(candidates.size() - distinct.size());
        return List.copyOf(distinct.values());
    }

    private static String normalizeExpression(String expression) {
        Objects.requireNonNull(expression, "expression");
        String normalized = expression.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        return normalized;
    }

    private record Evaluation(
        List<RewriteCandidate> candidates,
        boolean complete
    ) {
        private Evaluation {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    private static final class Context {
        private final RewriteTraceLevel level;
        private final RewriteTraceSink sink;
        private final List<RewriteExecution.SourceObservation> observations = new ArrayList<>();
        private ExecutionWork candidateWork = ExecutionWork.ZERO;
        private TransformationWorkMetrics delegatedWork = TransformationWorkMetrics.ZERO;
        private long sequence;
        private long programNodeVisits;
        private long sourceInvocations;
        private long sourceCandidates;
        private long composedCandidates;
        private long requirementEvaluations;
        private long requirementRejections;
        private long priorityCandidatesOrdered;
        private long prunedCandidates;
        private long repeatIterations;
        private long repeatEndpoints;
        private long alternativeSelections;
        private long alternativesSkipped;
        private long duplicateCandidatesDropped;

        private Context(RewriteTraceLevel level, RewriteTraceSink sink) {
            this.level = level;
            this.sink = sink;
        }

        private void programNodeVisited() {
            programNodeVisits++;
        }

        private void sourceInvoked() {
            sourceInvocations++;
        }

        private void sourceCandidates(long value) {
            sourceCandidates = add(sourceCandidates, value);
        }

        private void composedCandidate() {
            composedCandidates++;
        }

        private void requirementEvaluated() {
            requirementEvaluations++;
        }

        private void requirementRejected() {
            requirementRejections++;
        }

        private void priorityCandidatesOrdered(long value) {
            priorityCandidatesOrdered = add(priorityCandidatesOrdered, value);
        }

        private void prunedCandidates(long value) {
            prunedCandidates = add(prunedCandidates, value);
        }

        private void repeatIteration() {
            repeatIterations++;
        }

        private void repeatEndpoints(long value) {
            repeatEndpoints = add(repeatEndpoints, value);
        }

        private void alternativeSelected() {
            alternativeSelections++;
        }

        private void alternativeSkipped() {
            alternativesSkipped++;
        }

        private void duplicateCandidatesDropped(long value) {
            duplicateCandidatesDropped = add(
                duplicateCandidatesDropped,
                Math.max(0L, value));
        }

        private TransformationWorkMetrics workMetrics() {
            return new TransformationWorkMetrics(
                1,
                programNodeVisits,
                sourceInvocations,
                sourceCandidates,
                composedCandidates,
                requirementEvaluations,
                requirementRejections,
                priorityCandidatesOrdered,
                prunedCandidates,
                repeatIterations,
                repeatEndpoints,
                alternativeSelections,
                alternativesSkipped,
                duplicateCandidatesDropped, candidateWork).plus(delegatedWork);
        }

        private void summary(
            RewriteTraceEventType type,
            RewriteProgram program,
            String inputExpression,
            String outputExpression,
            List<String> ruleIds,
            int candidateCount,
            boolean complete,
            String detail
        ) {
            emit(
                RewriteTraceLevel.SUMMARY,
                type,
                program,
                inputExpression,
                outputExpression,
                ruleIds,
                candidateCount,
                complete,
                detail
            );
        }

        private void full(
            RewriteTraceEventType type,
            RewriteProgram program,
            String inputExpression,
            String outputExpression,
            List<String> ruleIds,
            int candidateCount,
            boolean complete,
            String detail
        ) {
            emit(
                RewriteTraceLevel.FULL,
                type,
                program,
                inputExpression,
                outputExpression,
                ruleIds,
                candidateCount,
                complete,
                detail
            );
        }

        private void emit(
            RewriteTraceLevel requiredLevel,
            RewriteTraceEventType type,
            RewriteProgram program,
            String inputExpression,
            String outputExpression,
            List<String> ruleIds,
            int candidateCount,
            boolean complete,
            String detail
        ) {
            if (level.ordinal() < requiredLevel.ordinal()) {
                return;
            }
            sink.accept(new RewriteTraceEvent(
                ++sequence,
                type,
                program.id(),
                program.getClass().getSimpleName(),
                program.metadata().sourceLocation(),
                inputExpression,
                outputExpression,
                ruleIds,
                candidateCount,
                complete,
                detail
            ));
        }

        private static long add(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }
    }
}
