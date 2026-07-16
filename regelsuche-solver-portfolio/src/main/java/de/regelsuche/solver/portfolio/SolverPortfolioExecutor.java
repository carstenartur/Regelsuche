package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes a deterministic plan with exact caching, budgets, cancellation and conflict aggregation. */
public final class SolverPortfolioExecutor {
    private static final long CANCELLATION_POLL_MILLIS = 20L;

    private final List<PortfolioBackend> backends;
    private final SolverPortfolioPlanner planner;
    private final PortfolioExecutionCache cache;

    public SolverPortfolioExecutor(List<? extends PortfolioBackend> backends) {
        this(backends, new SolverPortfolioPlanner(), new InMemoryPortfolioExecutionCache());
    }

    public SolverPortfolioExecutor(
        List<? extends PortfolioBackend> backends,
        SolverPortfolioPlanner planner,
        PortfolioExecutionCache cache
    ) {
        this.backends = backends == null ? List.of() : List.copyOf(backends);
        this.planner = Objects.requireNonNull(planner, "planner");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public PortfolioRun execute(PortfolioRequest request) {
        return execute(request, CancellationToken.none());
    }

    public PortfolioRun execute(
        PortfolioRequest request,
        CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        SolverPortfolioPlanner.Plan plan = planner.plan(request, backends);
        List<PortfolioAttempt> attempts = new ArrayList<>();
        List<ObservedExecution> observed = new ArrayList<>();
        int sequence = 0;
        for (SolverPortfolioPlanner.RejectedBackend rejected : plan.rejected()) {
            String configurationHash = attemptConfigurationHash(
                request, rejected.profile(), request.budget().defaultTimeoutMillis());
            attempts.add(PortfolioAttempt.create(
                sequence++, rejected.profile(), rejected.disposition(), "NOT_RUN", "",
                rejected.issues(), 0L, configurationHash));
        }

        long consumedCost = 0L;
        int executedInvocations = 0;
        boolean cancelled = false;
        boolean budgetExhausted = false;
        boolean timedOut = false;
        boolean failed = false;

        for (SolverPortfolioPlanner.PlannedBackend planned : plan.planned()) {
            PortfolioBackend backend = planned.backend();
            BackendCapabilityProfile profile = backend.profile();
            PortfolioBudget.BackendLimit limit = request.budget().limitFor(profile);
            String configurationHash = attemptConfigurationHash(
                request, profile, limit.timeoutMillis());
            String cacheKey = cacheKey(request, profile, configurationHash);

            Optional<SolverExecution> cached = cache.find(cacheKey)
                .filter(execution -> cacheEntryMatches(request, profile, execution));
            if (cached.isPresent()) {
                SolverExecution execution = cached.orElseThrow();
                attempts.add(PortfolioAttempt.create(
                    sequence++, profile, AttemptDisposition.CACHE_HIT,
                    execution.result().status().name(), execution.contentHash(),
                    List.of(), 0L, configurationHash));
                observed.add(new ObservedExecution(profile, execution));
                if (decisive(request, observed)) {
                    break;
                }
                continue;
            }

            if (cancellationToken.isCancelled()) {
                attempts.add(PortfolioAttempt.create(
                    sequence++, profile, AttemptDisposition.CANCELLED, "NOT_RUN", "",
                    List.of("CANCELLED_BEFORE_INVOCATION"), 0L, configurationHash));
                cancelled = true;
                break;
            }
            if (profile.availability() != BackendAvailability.AVAILABLE) {
                attempts.add(PortfolioAttempt.create(
                    sequence++, profile, AttemptDisposition.SKIPPED_UNAVAILABLE,
                    "NOT_RUN", "", List.of("BACKEND_" + profile.availability().name()),
                    0L, configurationHash));
                continue;
            }
            long cost = profile.estimatedCostUnits();
            if (executedInvocations >= request.budget().maxInvocations()
                    || cost > limit.maxCostUnits()
                    || consumedCost + cost > request.budget().totalCostUnits()) {
                attempts.add(PortfolioAttempt.create(
                    sequence++, profile, AttemptDisposition.SKIPPED_BUDGET, "NOT_RUN", "",
                    List.of("PORTFOLIO_BUDGET_EXHAUSTED"), 0L,
                    configurationHash));
                budgetExhausted = true;
                continue;
            }

            Invocation invocation = invoke(
                backend, request, limit.timeoutMillis(), cancellationToken);
            executedInvocations++;
            consumedCost += cost;
            attempts.add(PortfolioAttempt.create(
                sequence++, profile, invocation.disposition(),
                invocation.execution() == null
                    ? "NOT_RUN" : invocation.execution().result().status().name(),
                invocation.execution() == null
                    ? "" : invocation.execution().contentHash(),
                invocation.issues(), cost, configurationHash));

            if (invocation.disposition() == AttemptDisposition.CANCELLED) {
                cancelled = true;
                break;
            }
            if (invocation.disposition() == AttemptDisposition.TIMED_OUT) {
                timedOut = true;
                continue;
            }
            if (invocation.disposition() == AttemptDisposition.FAILED) {
                failed = true;
                continue;
            }
            SolverExecution execution = invocation.execution();
            if (execution != null) {
                observed.add(new ObservedExecution(profile, execution));
                if (profile.deterministic() && profile.reproducible()) {
                    cache.put(cacheKey, execution);
                }
                if (decisive(request, observed)) {
                    break;
                }
            }
        }

        Aggregate aggregate = aggregate(request, plan, observed, cancelled,
            budgetExhausted, timedOut, failed);
        PortfolioReport report = PortfolioReport.create(
            request, aggregate.outcome(), attempts,
            aggregate.selected() == null ? "" : aggregate.selected().execution().contentHash(),
            aggregate.selected() == null ? "" : aggregate.selected().profile().backendId(),
            aggregate.selected() == null ? List.of() : aggregate.selected().profile().roles(),
            aggregate.conflictHashes(), consumedCost, executedInvocations,
            aggregate.proofAuthorized());
        return new PortfolioRun(
            report,
            observed.stream().map(ObservedExecution::execution).toList(),
            aggregate.selected() == null ? null : aggregate.selected().execution());
    }

    private static Invocation invoke(
        PortfolioBackend backend,
        PortfolioRequest request,
        long timeoutMillis,
        CancellationToken cancellationToken
    ) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                runnable, "solver-portfolio-" + backend.profile().backendId());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(factory);
        Future<SolverExecution> future = executor.submit(
            () -> backend.execute(request.obligation()));
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (true) {
                if (cancellationToken.isCancelled()) {
                    future.cancel(true);
                    return new Invocation(
                        AttemptDisposition.CANCELLED, null,
                        List.of("CANCELLED_DURING_INVOCATION"));
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    future.cancel(true);
                    return new Invocation(
                        AttemptDisposition.TIMED_OUT, null,
                        List.of("BACKEND_TIMEOUT:" + timeoutMillis));
                }
                long waitMillis = Math.max(1L, Math.min(
                    CANCELLATION_POLL_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                try {
                    SolverExecution execution = future.get(waitMillis, TimeUnit.MILLISECONDS);
                    if (execution == null) {
                        return new Invocation(
                            AttemptDisposition.FAILED, null,
                            List.of("BACKEND_RETURNED_NULL"));
                    }
                    return new Invocation(
                        AttemptDisposition.EXECUTED, execution, List.of());
                } catch (TimeoutException ignored) {
                    // Poll cancellation until the hard deadline is reached.
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new Invocation(
                AttemptDisposition.CANCELLED, null,
                List.of("PORTFOLIO_THREAD_INTERRUPTED"));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null
                ? exception : exception.getCause();
            return new Invocation(
                AttemptDisposition.FAILED, null,
                List.of(cause.getClass().getSimpleName() + ':'
                    + String.valueOf(cause.getMessage())));
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean decisive(
        PortfolioRequest request,
        List<ObservedExecution> observed
    ) {
        Aggregate aggregate = aggregate(
            request,
            new SolverPortfolioPlanner.Plan(List.of(), List.of(),
                SolverIr.sha256("intermediate-plan")),
            observed, false, false, false, false);
        return aggregate.outcome() == PortfolioOutcome.CONFLICT
            || aggregate.outcome() == PortfolioOutcome.REFUTED
            || aggregate.outcome() == PortfolioOutcome.CONFIRMED;
    }

    private static Aggregate aggregate(
        PortfolioRequest request,
        SolverPortfolioPlanner.Plan plan,
        List<ObservedExecution> observed,
        boolean cancelled,
        boolean budgetExhausted,
        boolean timedOut,
        boolean failed
    ) {
        List<ObservedExecution> lossless = observed.stream()
            .filter(item -> item.execution().translation().status()
                == TranslationStatus.LOSSLESS)
            .toList();
        List<ObservedExecution> confirmations = lossless.stream()
            .filter(item -> item.execution().result().status()
                == ResultStatus.CONFIRMED)
            .toList();
        List<ObservedExecution> refutations = lossless.stream()
            .filter(item -> item.execution().result().status()
                == ResultStatus.REFUTED)
            .filter(item -> item.profile().canRefute())
            .toList();
        if (!confirmations.isEmpty() && !refutations.isEmpty()) {
            List<String> conflicts = java.util.stream.Stream.concat(
                    confirmations.stream(), refutations.stream())
                .map(item -> item.execution().contentHash())
                .distinct().sorted().toList();
            return new Aggregate(
                PortfolioOutcome.CONFLICT, null, conflicts, false);
        }
        if (!refutations.isEmpty()) {
            ObservedExecution selected = refutations.stream()
                .sorted(observedComparator())
                .findFirst().orElseThrow();
            return new Aggregate(
                PortfolioOutcome.REFUTED, selected, List.of(), false);
        }
        List<ObservedExecution> qualifying = confirmations.stream()
            .filter(item -> item.profile().canConfirm(request.objective()))
            .sorted(observedComparator())
            .toList();
        int requiredConfirmations = request.policy()
                == PortfolioPolicy.INDEPENDENT_CONFIRMATION ? 2 : 1;
        long distinctConfirmations = qualifying.stream()
            .map(item -> item.profile().backendId())
            .distinct().count();
        if (distinctConfirmations >= requiredConfirmations) {
            ObservedExecution selected = qualifying.stream()
                .filter(item -> item.profile().roles().contains(
                    request.objective() == SolverObjective.FORMAL_PROOF
                        ? BackendRole.FORMAL_PROOF
                        : request.objective() == SolverObjective.SYMBOLIC_CONFIRMATION
                            ? BackendRole.SYMBOLIC_CONFIRMATION
                            : item.profile().roles().getFirst()))
                .findFirst()
                .orElse(qualifying.getFirst());
            boolean proofAuthorized = request.objective().proofObjective()
                && selected.profile().canConfirm(request.objective());
            return new Aggregate(
                PortfolioOutcome.CONFIRMED, selected, List.of(), proofAuthorized);
        }
        if (cancelled) {
            return new Aggregate(PortfolioOutcome.CANCELLED, null, List.of(), false);
        }
        if (plan.planned().isEmpty() && observed.isEmpty()) {
            return new Aggregate(PortfolioOutcome.UNSUPPORTED, null, List.of(), false);
        }
        if (budgetExhausted) {
            return new Aggregate(
                PortfolioOutcome.BUDGET_EXHAUSTED, null, List.of(), false);
        }
        if (timedOut && observed.isEmpty()) {
            return new Aggregate(PortfolioOutcome.TIMEOUT, null, List.of(), false);
        }
        if (observed.stream().allMatch(item -> item.execution().result().status()
                == ResultStatus.UNSUPPORTED) && !observed.isEmpty()) {
            return new Aggregate(PortfolioOutcome.UNSUPPORTED, null, List.of(), false);
        }
        if (failed && observed.isEmpty()) {
            return new Aggregate(PortfolioOutcome.ERROR, null, List.of(), false);
        }
        return new Aggregate(PortfolioOutcome.INCONCLUSIVE, null, List.of(), false);
    }

    private static Comparator<ObservedExecution> observedComparator() {
        return Comparator
            .comparingInt((ObservedExecution item) ->
                item.profile().roles().contains(BackendRole.FORMAL_PROOF) ? 0
                    : item.profile().roles().contains(BackendRole.SYMBOLIC_CONFIRMATION) ? 1
                    : item.profile().roles().contains(BackendRole.ORACLE_VALIDATION) ? 2
                    : 3)
            .thenComparing(item -> item.profile().backendId());
    }

    private static boolean cacheEntryMatches(
        PortfolioRequest request,
        BackendCapabilityProfile profile,
        SolverExecution execution
    ) {
        return request.obligation().contentHash().equals(execution.obligationHash())
            && profile.backendId().equals(execution.result().backendId())
            && profile.backendVersion().equals(execution.result().backendVersion());
    }

    private static String attemptConfigurationHash(
        PortfolioRequest request,
        BackendCapabilityProfile profile,
        long timeoutMillis
    ) {
        return SolverIr.sha256(
            "requestConfiguration=" + request.configurationId()
                + "\nprofile=" + profile.semanticHash()
                + "\ntimeoutMillis=" + timeoutMillis);
    }

    private static String cacheKey(
        PortfolioRequest request,
        BackendCapabilityProfile profile,
        String attemptConfigurationHash
    ) {
        return SolverIr.sha256(
            "obligation=" + request.obligation().contentHash()
                + "\nbackendProfile=" + profile.semanticHash()
                + "\nattemptConfiguration=" + attemptConfigurationHash);
    }

    private record Invocation(
        AttemptDisposition disposition,
        SolverExecution execution,
        List<String> issues
    ) {
    }

    private record ObservedExecution(
        BackendCapabilityProfile profile,
        SolverExecution execution
    ) {
    }

    private record Aggregate(
        PortfolioOutcome outcome,
        ObservedExecution selected,
        List<String> conflictHashes,
        boolean proofAuthorized
    ) {
    }
}
