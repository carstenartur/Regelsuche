package de.regelsuche.search.program;

import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic interpreter for the Java-internal rewrite program model.
 *
 * <p>The interpreter is intentionally independent of a concrete search
 * strategy. It composes ordinary {@link Transformation} instances and exposes
 * the resulting candidates through the same {@code TransformationEngine}
 * boundary used by best-first, beam, A* and the other existing strategies.</p>
 */
public final class RewriteProgramInterpreter {
    private static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(transformation -> transformation.kind().name());

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
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(traceLevel, "traceLevel");
        Objects.requireNonNull(traceSink, "traceSink");
        String normalizedExpression = normalizeExpression(expression);
        Context context = new Context(traceLevel, traceSink);
        Evaluation evaluation = evaluate(program, normalizedExpression, context);
        return new RewriteExecution(
            distinct(evaluation.candidates()),
            evaluation.complete()
        );
    }

    private Evaluation evaluate(
        RewriteProgram program,
        String inputExpression,
        Context context
    ) {
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
                evaluateSource(source, inputExpression, context);
            case RewriteProgram.Choice choice ->
                evaluateChoice(choice, inputExpression, context);
            case RewriteProgram.FirstApplicable firstApplicable ->
                evaluateFirstApplicable(firstApplicable, inputExpression, context);
            case RewriteProgram.Sequence sequence ->
                evaluateSequence(sequence, inputExpression, context);
            case RewriteProgram.Repeat repeat ->
                evaluateRepeat(repeat, inputExpression, context);
            case RewriteProgram.Require require ->
                evaluateRequire(require, inputExpression, context);
            case RewriteProgram.Prioritize prioritize ->
                evaluatePrioritize(prioritize, inputExpression, context);
            case RewriteProgram.Prune prune ->
                evaluatePrune(prune, inputExpression, context);
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
        Context context
    ) {
        List<Transformation> transformations = new ArrayList<>(
            Objects.requireNonNull(
                source.engine().transform(inputExpression),
                "TransformationEngine.transform must not return null"
            )
        );
        transformations.forEach(transformation ->
            Objects.requireNonNull(transformation, "transformation"));
        transformations.sort(TRANSFORMATION_ORDER);

        List<RewriteCandidate> candidates = new ArrayList<>(transformations.size());
        for (Transformation transformation : transformations) {
            RewriteCandidate candidate = new RewriteCandidate(
                source.id(),
                inputExpression,
                transformation.transformedExpression(),
                List.of(transformation)
            );
            candidates.add(candidate);
            context.full(
                RewriteTraceEventType.SOURCE_CANDIDATE,
                source,
                inputExpression,
                candidate.outputExpression(),
                candidate.ruleIds(),
                1,
                true,
                transformation.applicationKey()
            );
        }
        return new Evaluation(candidates, true);
    }

    private Evaluation evaluateChoice(
        RewriteProgram.Choice choice,
        String inputExpression,
        Context context
    ) {
        List<RewriteCandidate> candidates = new ArrayList<>();
        boolean complete = true;
        for (RewriteProgram alternative : choice.alternatives()) {
            Evaluation evaluated = evaluate(alternative, inputExpression, context);
            candidates.addAll(evaluated.candidates());
            complete &= evaluated.complete();
        }
        return new Evaluation(distinct(candidates), complete);
    }

    private Evaluation evaluateFirstApplicable(
        RewriteProgram.FirstApplicable firstApplicable,
        String inputExpression,
        Context context
    ) {
        boolean complete = true;
        for (int index = 0; index < firstApplicable.alternatives().size(); index++) {
            RewriteProgram alternative = firstApplicable.alternatives().get(index);
            Evaluation evaluated = evaluate(alternative, inputExpression, context);
            complete &= evaluated.complete();
            if (evaluated.candidates().isEmpty()) {
                continue;
            }

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
        Context context
    ) {
        Evaluation first = evaluate(sequence.steps().get(0), inputExpression, context);
        List<RewriteCandidate> current = first.candidates();
        boolean complete = first.complete();

        for (int index = 1; index < sequence.steps().size() && !current.isEmpty(); index++) {
            RewriteProgram step = sequence.steps().get(index);
            List<RewriteCandidate> next = new ArrayList<>();
            for (RewriteCandidate prefix : current) {
                Evaluation suffixes = evaluate(step, prefix.outputExpression(), context);
                complete &= suffixes.complete();
                for (RewriteCandidate suffix : suffixes.candidates()) {
                    next.add(prefix.append(suffix, sequence.id()));
                }
            }
            current = distinct(next);
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
        Context context
    ) {
        List<RewriteCandidate> frontier = List.of();
        List<RewriteCandidate> endpoints = new ArrayList<>();
        boolean complete = true;

        for (int iteration = 1; iteration <= repeat.maxIterations(); iteration++) {
            List<RewriteCandidate> next = new ArrayList<>();
            if (iteration == 1) {
                Evaluation evaluated = evaluate(repeat.body(), inputExpression, context);
                next.addAll(evaluated.candidates());
                complete &= evaluated.complete();
            } else {
                for (RewriteCandidate prefix : frontier) {
                    Evaluation suffixes = evaluate(
                        repeat.body(),
                        prefix.outputExpression(),
                        context
                    );
                    complete &= suffixes.complete();
                    for (RewriteCandidate suffix : suffixes.candidates()) {
                        next.add(prefix.append(suffix, repeat.id()));
                    }
                }
            }

            frontier = distinct(next);
            if (iteration >= repeat.minIterations()) {
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
        return new Evaluation(distinct(endpoints), complete);
    }

    private Evaluation evaluateRequire(
        RewriteProgram.Require require,
        String inputExpression,
        Context context
    ) {
        Evaluation evaluated = evaluate(require.body(), inputExpression, context);
        List<RewriteCandidate> accepted = new ArrayList<>();
        for (RewriteCandidate candidate : evaluated.candidates()) {
            if (require.condition().test(candidate)) {
                accepted.add(candidate);
                continue;
            }
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
        Context context
    ) {
        Evaluation evaluated = evaluate(prioritize.body(), inputExpression, context);
        List<RewriteCandidate> candidates = new ArrayList<>(evaluated.candidates());
        candidates.sort(
            prioritize.comparator()
                .thenComparing(RewriteCandidate::fingerprint)
        );
        return new Evaluation(candidates, evaluated.complete());
    }

    private Evaluation evaluatePrune(
        RewriteProgram.Prune prune,
        String inputExpression,
        Context context
    ) {
        Evaluation evaluated = evaluate(prune.body(), inputExpression, context);
        if (evaluated.candidates().size() <= prune.maxCandidates()) {
            return evaluated;
        }

        int removed = evaluated.candidates().size() - prune.maxCandidates();
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

    private static List<RewriteCandidate> distinct(
        List<RewriteCandidate> candidates
    ) {
        Map<String, RewriteCandidate> distinct = new LinkedHashMap<>();
        for (RewriteCandidate candidate : candidates) {
            distinct.putIfAbsent(candidate.fingerprint(), candidate);
        }
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
        private long sequence;

        private Context(RewriteTraceLevel level, RewriteTraceSink sink) {
            this.level = level;
            this.sink = sink;
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
    }
}
