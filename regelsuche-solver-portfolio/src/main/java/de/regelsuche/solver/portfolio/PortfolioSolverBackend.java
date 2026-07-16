package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverTranslation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** SolverBackend-compatible facade so existing proof gates consume only a selected execution. */
public final class PortfolioSolverBackend implements SolverBackend {
    private final List<PortfolioBackend> backends;
    private final PortfolioPolicy policy;
    private final PortfolioBudget budget;
    private final String configurationId;
    private final SolverPortfolioExecutor executor;
    private final ThreadLocal<PortfolioRun> lastRun = new ThreadLocal<>();
    private final BackendDescriptor descriptor;

    public PortfolioSolverBackend(
        List<? extends PortfolioBackend> backends,
        PortfolioPolicy policy,
        PortfolioBudget budget,
        String configurationId
    ) {
        this(backends, policy, budget, configurationId,
            new InMemoryPortfolioExecutionCache());
    }

    public PortfolioSolverBackend(
        List<? extends PortfolioBackend> backends,
        PortfolioPolicy policy,
        PortfolioBudget budget,
        String configurationId,
        PortfolioExecutionCache cache
    ) {
        this.backends = backends == null ? List.of() : List.copyOf(backends);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (configurationId == null || configurationId.isBlank()) {
            throw new IllegalArgumentException("configurationId must not be blank");
        }
        this.configurationId = configurationId;
        this.executor = new SolverPortfolioExecutor(
            this.backends, new SolverPortfolioPlanner(), Objects.requireNonNull(cache));
        this.descriptor = new BackendDescriptor(
            "solver-portfolio",
            "1",
            Arrays.asList(Theory.values()),
            Arrays.stream(Relation.values())
                .filter(relation -> relation != Relation.IS_INTEGER).toList(),
            Arrays.asList(RequestedEvidence.values()),
            this.backends.stream().allMatch(item -> item.profile().deterministic()));
    }

    @Override
    public BackendDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SolverExecution execute(Obligation obligation) {
        PortfolioRequest request = PortfolioRequest.create(
            obligation, objective(obligation.requestedEvidence()), policy, budget,
            configurationId);
        PortfolioRun run = executor.execute(request);
        lastRun.set(run);
        if (run.selectedExecution() != null) {
            return run.selectedExecution();
        }
        return syntheticExecution(obligation, run.report());
    }

    public Optional<PortfolioRun> lastRun() {
        return Optional.ofNullable(lastRun.get());
    }

    private SolverExecution syntheticExecution(
        Obligation obligation,
        PortfolioReport report
    ) {
        List<String> issues = report.attempts().stream()
            .flatMap(attempt -> attempt.issues().stream())
            .distinct().sorted().toList();
        boolean rejected = report.outcome() == PortfolioOutcome.UNSUPPORTED;
        TranslationStatus translationStatus = rejected
            ? TranslationStatus.REJECTED : TranslationStatus.LOSSLESS;
        if (rejected && issues.isEmpty()) {
            issues = List.of("NO_CAPABLE_PORTFOLIO_BACKEND");
        }
        SolverTranslation translation = SolverTranslation.create(
            obligation, descriptor, translationStatus, issues,
            Map.of("portfolio.report", report.contentHash()));
        ResultStatus status = switch (report.outcome()) {
            case UNSUPPORTED -> ResultStatus.UNSUPPORTED;
            case TIMEOUT -> ResultStatus.TIMEOUT;
            case ERROR, CONFLICT -> ResultStatus.ERROR;
            case CONFIRMED -> ResultStatus.CONFIRMED;
            case REFUTED -> ResultStatus.REFUTED;
            case INCONCLUSIVE, CANCELLED, BUDGET_EXHAUSTED -> ResultStatus.UNKNOWN;
        };
        SolverResult result = SolverResult.create(
            obligation,
            descriptor,
            status,
            translationStatus,
            List.of("CAPABILITY_AWARE_PORTFOLIO", report.outcome().name()),
            issues,
            "portfolioOutcome=" + report.outcome().name()
                + "; reportHash=" + report.contentHash(),
            Map.of(),
            "");
        return SolverExecution.create(obligation, translation, result);
    }

    private static SolverObjective objective(RequestedEvidence evidence) {
        return switch (evidence) {
            case DECISION -> SolverObjective.VALIDATION;
            case COUNTEREXAMPLE -> SolverObjective.COUNTEREXAMPLE_SEARCH;
            case SYMBOLIC_CERTIFICATE -> SolverObjective.SYMBOLIC_CONFIRMATION;
            case FORMAL_PROOF -> SolverObjective.FORMAL_PROOF;
        };
    }
}
