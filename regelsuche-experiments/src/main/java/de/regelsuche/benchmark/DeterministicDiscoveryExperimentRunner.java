package de.regelsuche.benchmark;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.validation.DiscoveryResultKind;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Reproducible experiment runner with deterministic ordering, global budget
 * and optional parallel execution.
 */
public final class DeterministicDiscoveryExperimentRunner implements DiscoveryExperimentRunner {

    private final int globalBudget;
    private final int parallelism;
    private final Function<SeedExpression, SeedRunOutcome> evaluator;

    public DeterministicDiscoveryExperimentRunner(
        int globalBudget,
        int parallelism,
        Function<SeedExpression, SeedRunOutcome> evaluator
    ) {
        this.globalBudget = Math.max(0, globalBudget);
        this.parallelism = Math.max(1, parallelism);
        this.evaluator = evaluator == null ? seed -> SeedRunOutcome.fail("no evaluator") : evaluator;
    }

    @Override
    public List<ExperimentResult> run(List<String> seedExpressions) {
        List<SeedExpression> seeds = (seedExpressions == null ? List.<String>of() : seedExpressions).stream()
            .map(expression -> new SeedExpression(expression, expression, "inline", "general", List.of(), List.of()))
            .toList();
        return execute(seeds, false).stream()
            .map(row -> new ExperimentResult(row.seed().expression(), row.success(), row.summary()))
            .toList();
    }

    public DiscoveryReport runDetailed(List<SeedExpression> seedExpressions) {
        long started = System.nanoTime();
        List<SeedRunReport> rows = execute(seedExpressions, true);
        long runtimeMillis = (System.nanoTime() - started) / 1_000_000L;
        long successful = rows.stream().filter(SeedRunReport::success).count();
        long hypotheses = rows.stream().mapToLong(row -> row.hypotheses().size()).sum();
        long counterexamples = rows.stream().mapToLong(row -> row.counterexamples().size()).sum();
        long rowRuntime = rows.stream().mapToLong(SeedRunReport::elapsedMillis).sum();
        long rowMemory = rows.stream().mapToLong(SeedRunReport::memoryBytes).sum();
        return new DiscoveryReport(
            rows,
            new DiscoveryMetrics(rows.size(), (int) successful, (int) hypotheses, (int) counterexamples, rowRuntime, rowMemory),
            runtimeMillis
        );
    }

    private List<SeedRunReport> execute(List<SeedExpression> seedExpressions, boolean stableOrder) {
        List<SeedExpression> candidates = usable(seedExpressions);
        if (stableOrder) {
            candidates = candidates.stream().sorted(Comparator.comparing(SeedExpression::stableKey)).toList();
        }
        List<SeedExpression> budgeted = candidates.stream().limit(globalBudget).toList();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Callable<IndexedResult>> tasks = new ArrayList<>();
            for (int i = 0; i < budgeted.size(); i++) {
                final int index = i;
                final SeedExpression seed = budgeted.get(i);
                tasks.add(() -> new IndexedResult(index, seed, evaluator.apply(seed)));
            }
            List<Future<IndexedResult>> futures = pool.invokeAll(tasks);
            List<SeedRunReport> rows = new ArrayList<>();
            for (Future<IndexedResult> future : futures) {
                IndexedResult value = future.get();
                SeedRunOutcome outcome = value.outcome();
                rows.add(new SeedRunReport(
                    value.seed(),
                    outcome.success(),
                    outcome.summary(),
                    outcome.hypotheses(),
                    outcome.counterexamples(),
                    outcome.counterexampleSearchStatus(),
                    outcome.counterexampleAttemptedSources(),
                    outcome.inferredAssumptions(),
                    outcome.counterexampleExplanation(),
                    outcome.replayPath(),
                    outcome.resultKind(),
                    outcome.rulePath(),
                    outcome.elapsedMillis(),
                    outcome.memoryBytes()
                ));
            }
            if (!stableOrder) {
                return List.copyOf(rows);
            }
            return rows.stream().sorted(Comparator.comparing(row -> row.seed().stableKey())).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Experiment run failed", exception);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<SeedExpression> usable(List<SeedExpression> input) {
        return (input == null ? List.<SeedExpression>of() : input).stream()
            .filter(seed -> seed != null && !seed.expression().isBlank())
            .toList();
    }

    private record IndexedResult(int index, SeedExpression seed, SeedRunOutcome outcome) {
    }

    public record SeedRunOutcome(
        boolean success,
        String summary,
        List<String> hypotheses,
        List<String> counterexamples,
        CounterexampleSearchService.Status counterexampleSearchStatus,
        List<String> counterexampleAttemptedSources,
        List<String> inferredAssumptions,
        String counterexampleExplanation,
        List<String> replayPath,
        DiscoveryResultKind resultKind,
        List<String> rulePath,
        long elapsedMillis,
        long memoryBytes
    ) {
        public SeedRunOutcome(
            boolean success,
            String summary,
            List<String> hypotheses,
            List<String> counterexamples,
            List<String> replayPath,
            long elapsedMillis,
            long memoryBytes
        ) {
            this(success, summary, hypotheses, counterexamples, deriveStatus(counterexamples, null),
                List.of(), List.of(), "", replayPath, defaultResultKind(success, hypotheses, replayPath), List.of(),
                elapsedMillis, memoryBytes);
        }

        public SeedRunOutcome(
            boolean success,
            String summary,
            List<String> hypotheses,
            CounterexampleSearchService.CounterexampleSearchResult counterexampleResult,
            List<String> replayPath,
            long elapsedMillis,
            long memoryBytes
        ) {
            this(success, summary, hypotheses,
                counterexampleResult.counterexample().map(counterexample -> List.of(counterexample.toString())).orElse(List.of()),
                counterexampleResult.status(),
                counterexampleResult.attemptedSources(),
                counterexampleResult.inferredAssumptions(),
                counterexampleResult.explanation(),
                replayPath, defaultResultKind(success, hypotheses, replayPath), List.of(), elapsedMillis, memoryBytes);
        }

        public SeedRunOutcome(
            boolean success,
            String summary,
            List<String> hypotheses,
            List<String> counterexamples,
            CounterexampleSearchService.Status counterexampleSearchStatus,
            List<String> counterexampleAttemptedSources,
            List<String> inferredAssumptions,
            String counterexampleExplanation,
            List<String> replayPath,
            long elapsedMillis,
            long memoryBytes
        ) {
            this(success, summary, hypotheses, counterexamples, counterexampleSearchStatus,
                counterexampleAttemptedSources, inferredAssumptions, counterexampleExplanation, replayPath,
                defaultResultKind(success, hypotheses, replayPath), List.of(), elapsedMillis, memoryBytes);
        }

        public SeedRunOutcome {
            summary = summary == null ? "" : summary;
            hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
            counterexamples = counterexamples == null ? List.of() : List.copyOf(counterexamples);
            counterexampleSearchStatus = deriveStatus(counterexamples, counterexampleSearchStatus);
            counterexampleAttemptedSources = counterexampleAttemptedSources == null
                ? List.of()
                : List.copyOf(counterexampleAttemptedSources);
            inferredAssumptions = inferredAssumptions == null ? List.of() : List.copyOf(inferredAssumptions);
            counterexampleExplanation = counterexampleExplanation == null ? "" : counterexampleExplanation;
            replayPath = replayPath == null ? List.of() : List.copyOf(replayPath);
            resultKind = resultKind == null ? defaultResultKind(success, hypotheses, replayPath) : resultKind;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }

        public static SeedRunOutcome fail(String summary) {
            return new SeedRunOutcome(false, summary, List.of(), List.of(), List.of(), 0L, 0L);
        }

        private static CounterexampleSearchService.Status deriveStatus(
            List<String> counterexamples,
            CounterexampleSearchService.Status explicitStatus
        ) {
            if (explicitStatus != null) {
                return explicitStatus;
            }
            return counterexamples == null || counterexamples.isEmpty()
                ? CounterexampleSearchService.Status.INCONCLUSIVE
                : CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND;
        }

        private static DiscoveryResultKind defaultResultKind(boolean success, List<String> hypotheses, List<String> replayPath) {
            if (!success && (hypotheses == null || hypotheses.isEmpty())) {
                return DiscoveryResultKind.NO_CANDIDATE;
            }
            if (success && replayPath != null && replayPath.size() > 1) {
                return DiscoveryResultKind.SIMPLIFIED;
            }
            return hypotheses == null || hypotheses.isEmpty()
                ? DiscoveryResultKind.NO_CANDIDATE
                : DiscoveryResultKind.HYPOTHESIS_ONLY;
        }
    }

    public record SeedRunReport(
        SeedExpression seed,
        boolean success,
        String summary,
        List<String> hypotheses,
        List<String> counterexamples,
        CounterexampleSearchService.Status counterexampleSearchStatus,
        List<String> counterexampleAttemptedSources,
        List<String> inferredAssumptions,
        String counterexampleExplanation,
        List<String> replayPath,
        DiscoveryResultKind resultKind,
        List<String> rulePath,
        long elapsedMillis,
        long memoryBytes
    ) {
        public SeedRunReport(
            SeedExpression seed,
            boolean success,
            String summary,
            List<String> hypotheses,
            List<String> counterexamples,
            List<String> replayPath,
            long elapsedMillis,
            long memoryBytes
        ) {
            this(seed, success, summary, hypotheses, counterexamples,
                counterexamples == null || counterexamples.isEmpty()
                    ? CounterexampleSearchService.Status.INCONCLUSIVE
                    : CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND,
                List.of(), List.of(), "", replayPath,
                SeedRunOutcome.defaultResultKind(success, hypotheses, replayPath), List.of(), elapsedMillis, memoryBytes);
        }

        public SeedRunReport(
            SeedExpression seed,
            boolean success,
            String summary,
            List<String> hypotheses,
            List<String> counterexamples,
            CounterexampleSearchService.Status counterexampleSearchStatus,
            List<String> counterexampleAttemptedSources,
            List<String> inferredAssumptions,
            String counterexampleExplanation,
            List<String> replayPath,
            long elapsedMillis,
            long memoryBytes
        ) {
            this(seed, success, summary, hypotheses, counterexamples, counterexampleSearchStatus,
                counterexampleAttemptedSources, inferredAssumptions, counterexampleExplanation, replayPath,
                SeedRunOutcome.defaultResultKind(success, hypotheses, replayPath), List.of(), elapsedMillis, memoryBytes);
        }

        public SeedRunReport {
            resultKind = resultKind == null ? SeedRunOutcome.defaultResultKind(success, hypotheses, replayPath) : resultKind;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }

    public record DiscoveryMetrics(
        int processedSeeds,
        int successfulSeeds,
        int hypotheses,
        int counterexamples,
        long accumulatedRuntimeMillis,
        long accumulatedMemoryBytes
    ) {
    }

    public record DiscoveryReport(
        List<SeedRunReport> rows,
        DiscoveryMetrics metrics,
        long wallClockRuntimeMillis
    ) {
        public DiscoveryReport {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        public String renderDeterministicJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("schema", "regelsuche.discovery-report/v1");
            writer.array("volatileFields", volatileFields -> {
                volatileFields.value("wallClockRuntimeMillis");
                volatileFields.value("rows[].elapsedMillis");
                volatileFields.value("rows[].memoryBytes");
                volatileFields.value("metrics.accumulatedRuntimeMillis");
                volatileFields.value("metrics.accumulatedMemoryBytes");
            });
            writer.property("wallClockRuntimeMillis", 0L);
            writer.object("metrics", metricsObject -> {
                metricsObject.property("processedSeeds", metrics.processedSeeds());
                metricsObject.property("successfulSeeds", metrics.successfulSeeds());
                metricsObject.property("hypotheses", metrics.hypotheses());
                metricsObject.property("counterexamples", metrics.counterexamples());
                metricsObject.property("accumulatedRuntimeMillis", 0L);
                metricsObject.property("accumulatedMemoryBytes", 0L);
            });
            writer.array("rows", rowsArray -> rows.forEach(row -> rowsArray.objectValue(object -> {
                object.property("seedId", row.seed().id());
                object.property("expression", row.seed().expression());
                object.property("success", row.success());
                object.property("resultKind", row.resultKind().name());
                object.property("summary", row.summary());
                object.array("hypotheses", h -> row.hypotheses().forEach(h::value));
                object.property("counterexampleStatus", row.counterexampleSearchStatus().name());
                object.array("counterexamples", c -> row.counterexamples().forEach(c::value));
                object.array("attemptedSources", c -> row.counterexampleAttemptedSources().forEach(c::value));
                object.array("inferredAssumptions", c -> row.inferredAssumptions().forEach(c::value));
                object.property("explanation", row.counterexampleExplanation());
                object.array("replayPath", r -> row.replayPath().forEach(r::value));
                object.array("rulePath", r -> row.rulePath().forEach(r::value));
                object.property("elapsedMillis", 0L);
                object.property("memoryBytes", 0L);
            })));
            writer.endObject();
            return writer.toString();
        }
    }
}
