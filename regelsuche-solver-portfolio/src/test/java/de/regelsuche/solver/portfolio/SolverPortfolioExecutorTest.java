package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.solver.ir.SolverTranslation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SolverPortfolioExecutorTest {

    @Test
    void counterexampleFirstReachesFormalProofWithoutUpgradingSymbolicConfirmation() {
        List<String> order = new ArrayList<>();
        TestBackend symbolic = backend(
            "symbolic", List.of(BackendRole.ORACLE_VALIDATION,
                BackendRole.COUNTEREXAMPLE, BackendRole.SYMBOLIC_CONFIRMATION),
            10L, ResultStatus.CONFIRMED,
            obligation -> order.add("symbolic"));
        TestBackend formal = backend(
            "formal", List.of(BackendRole.FORMAL_PROOF), 50L,
            ResultStatus.CONFIRMED, obligation -> order.add("formal"));
        PortfolioRequest request = request(
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.COUNTEREXAMPLE_FIRST,
            PortfolioBudget.standard());

        PortfolioRun run = new SolverPortfolioExecutor(List.of(formal, symbolic))
            .execute(request);

        assertEquals(List.of("symbolic", "formal"), order);
        assertEquals(PortfolioOutcome.CONFIRMED, run.report().outcome());
        assertTrue(run.report().proofAuthorized());
        assertEquals("formal", run.report().selectedBackendId());
        assertEquals("formal", run.selectedExecution().result().backendId());
    }

    @Test
    void conflictingLosslessBackendsBlockPromotionExplicitly() {
        TestBackend confirming = backend(
            "formal-a", List.of(BackendRole.FORMAL_PROOF), 10L,
            ResultStatus.CONFIRMED, obligation -> { });
        TestBackend refuting = backend(
            "formal-b", List.of(BackendRole.FORMAL_PROOF), 20L,
            ResultStatus.REFUTED, obligation -> { });
        PortfolioRequest request = request(
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.INDEPENDENT_CONFIRMATION,
            PortfolioBudget.standard());

        PortfolioRun run = new SolverPortfolioExecutor(List.of(confirming, refuting))
            .execute(request);

        assertEquals(PortfolioOutcome.CONFLICT, run.report().outcome());
        assertTrue(run.report().promotionBlocked());
        assertFalse(run.report().proofAuthorized());
        assertEquals(2, run.report().conflictExecutionHashes().size());
    }

    @Test
    void unsupportedOperatorIsFilteredBeforeBackendInvocation() {
        AtomicInteger calls = new AtomicInteger();
        TestBackend backend = backend(
            "add-only", List.of(BackendRole.ORACLE_VALIDATION), 5L,
            ResultStatus.CONFIRMED, List.of(BinaryOperator.ADD),
            BackendAvailability.AVAILABLE, obligation -> calls.incrementAndGet());
        PortfolioRequest request = PortfolioRequest.create(
            obligation("x * y", "y * x", RequestedEvidence.SYMBOLIC_CERTIFICATE),
            SolverObjective.VALIDATION,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(), "unsupported-filter/v1");

        PortfolioRun run = new SolverPortfolioExecutor(List.of(backend))
            .execute(request);

        assertEquals(0, calls.get());
        assertEquals(PortfolioOutcome.UNSUPPORTED, run.report().outcome());
        assertEquals(AttemptDisposition.FILTERED_UNSUPPORTED,
            run.report().attempts().getFirst().disposition());
        assertTrue(run.report().attempts().getFirst().issues().contains(
            "UNSUPPORTED_OPERATOR:MULTIPLY"));
    }

    @Test
    void timeoutFallsBackToNextCapableBackend() {
        TestBackend slow = backend(
            "slow-counterexample", List.of(BackendRole.COUNTEREXAMPLE), 1L,
            ResultStatus.UNKNOWN, obligation -> {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        TestBackend formal = backend(
            "fast-formal", List.of(BackendRole.FORMAL_PROOF), 2L,
            ResultStatus.CONFIRMED, obligation -> { });
        PortfolioBudget budget = new PortfolioBudget(
            4, 20L, 1_000L,
            Map.of("slow-counterexample",
                new PortfolioBudget.BackendLimit(5L, 20L)));

        PortfolioRun run = new SolverPortfolioExecutor(List.of(formal, slow))
            .execute(request(
                SolverObjective.FORMAL_PROOF,
                PortfolioPolicy.COUNTEREXAMPLE_FIRST,
                budget));

        assertEquals(AttemptDisposition.TIMED_OUT,
            run.report().attempts().getFirst().disposition());
        assertEquals(PortfolioOutcome.CONFIRMED, run.report().outcome());
        assertEquals("fast-formal", run.report().selectedBackendId());
    }

    @Test
    void cooperativeCancellationStopsConfiguredFallbackChain() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger formalCalls = new AtomicInteger();
        TestBackend first = backend(
            "cancelling-validation", List.of(BackendRole.COUNTEREXAMPLE), 1L,
            ResultStatus.UNKNOWN, obligation -> cancelled.set(true));
        TestBackend formal = backend(
            "should-not-run", List.of(BackendRole.FORMAL_PROOF), 2L,
            ResultStatus.CONFIRMED, obligation -> formalCalls.incrementAndGet());

        PortfolioRun run = new SolverPortfolioExecutor(List.of(first, formal))
            .execute(request(
                SolverObjective.FORMAL_PROOF,
                PortfolioPolicy.COUNTEREXAMPLE_FIRST,
                PortfolioBudget.standard()), cancelled::get);

        assertEquals(PortfolioOutcome.CANCELLED, run.report().outcome());
        assertEquals(0, formalCalls.get());
        assertEquals(AttemptDisposition.CANCELLED,
            run.report().attempts().getLast().disposition());
    }

    @Test
    void exactCacheRemainsMeaningfulWhenBackendBecomesUnavailable() {
        InMemoryPortfolioExecutionCache cache = new InMemoryPortfolioExecutionCache();
        AtomicInteger firstCalls = new AtomicInteger();
        TestBackend available = backend(
            "cacheable", List.of(BackendRole.ORACLE_VALIDATION), 3L,
            ResultStatus.CONFIRMED, obligation -> firstCalls.incrementAndGet());
        PortfolioRequest request = request(
            SolverObjective.VALIDATION,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard());
        PortfolioRun first = new SolverPortfolioExecutor(
            List.of(available), new SolverPortfolioPlanner(), cache).execute(request);

        AtomicInteger secondCalls = new AtomicInteger();
        TestBackend unavailable = new TestBackend(
            available.profile().withAvailability(BackendAvailability.UNAVAILABLE),
            obligation -> {
                secondCalls.incrementAndGet();
                return execution(available.profile(), obligation, ResultStatus.ERROR);
            });
        PortfolioRun second = new SolverPortfolioExecutor(
            List.of(unavailable), new SolverPortfolioPlanner(), cache).execute(request);

        assertEquals(1, firstCalls.get());
        assertEquals(0, secondCalls.get());
        assertEquals(AttemptDisposition.CACHE_HIT,
            second.report().attempts().getFirst().disposition());
        assertEquals(first.selectedExecution().contentHash(),
            second.selectedExecution().contentHash());
    }

    @Test
    void budgetSkipsExpensiveStageAndContinuesWithAffordableFormalBackend() {
        TestBackend expensive = backend(
            "expensive-counterexample", List.of(BackendRole.COUNTEREXAMPLE), 100L,
            ResultStatus.UNKNOWN, obligation -> { });
        TestBackend affordable = backend(
            "affordable-formal", List.of(BackendRole.FORMAL_PROOF), 5L,
            ResultStatus.CONFIRMED, obligation -> { });
        PortfolioBudget budget = new PortfolioBudget(3, 10L, 1_000L, Map.of());

        PortfolioRun run = new SolverPortfolioExecutor(List.of(affordable, expensive))
            .execute(request(
                SolverObjective.FORMAL_PROOF,
                PortfolioPolicy.COUNTEREXAMPLE_FIRST,
                budget));

        assertEquals(AttemptDisposition.SKIPPED_BUDGET,
            run.report().attempts().getFirst().disposition());
        assertEquals(PortfolioOutcome.CONFIRMED, run.report().outcome());
        assertEquals("affordable-formal", run.report().selectedBackendId());
    }

    @Test
    void fixedConfigurationProducesEquivalentCanonicalTrace() {
        TestBackend symbolic = backend(
            "stable-symbolic", List.of(BackendRole.SYMBOLIC_CONFIRMATION), 4L,
            ResultStatus.CONFIRMED, obligation -> { });
        PortfolioRequest request = request(
            SolverObjective.SYMBOLIC_CONFIRMATION,
            PortfolioPolicy.CHEAPEST_CONFIRMATION_FIRST,
            PortfolioBudget.standard());

        PortfolioRun first = new SolverPortfolioExecutor(List.of(symbolic))
            .execute(request);
        PortfolioRun second = new SolverPortfolioExecutor(List.of(symbolic))
            .execute(request);

        assertEquals(first.report().contentHash(), second.report().contentHash());
        assertEquals(first.report().toCanonicalJson(),
            second.report().toCanonicalJson());
    }

    @Test
    void solverBackendFacadeNeverLetsSearchGuidanceSatisfyFormalProof() {
        TestBackend search = backend(
            "guidance-only", List.of(BackendRole.SEARCH_GUIDANCE), 1L,
            ResultStatus.CONFIRMED, obligation -> { });
        PortfolioSolverBackend portfolio = new PortfolioSolverBackend(
            List.of(search), PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(), "proof-consumer/v1");
        Obligation obligation = obligation(
            "x + 0", "x", RequestedEvidence.FORMAL_PROOF);

        SolverExecution execution = portfolio.execute(obligation);

        assertEquals(ResultStatus.UNKNOWN, execution.result().status());
        assertEquals("solver-portfolio", execution.result().backendId());
        assertEquals(PortfolioOutcome.INCONCLUSIVE,
            portfolio.lastRun().orElseThrow().report().outcome());
        assertFalse(portfolio.lastRun().orElseThrow().report().proofAuthorized());
    }

    @Test
    void solverBackendFacadeReturnsExactSelectedFormalExecution() {
        TestBackend formal = backend(
            "selected-formal", List.of(BackendRole.FORMAL_PROOF), 3L,
            ResultStatus.CONFIRMED, obligation -> { });
        PortfolioSolverBackend portfolio = new PortfolioSolverBackend(
            List.of(formal), PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(), "proof-consumer/v1");

        SolverExecution execution = portfolio.execute(obligation(
            "x + 0", "x", RequestedEvidence.FORMAL_PROOF));

        assertEquals("selected-formal", execution.result().backendId());
        assertNotEquals("solver-portfolio", execution.result().backendId());
        assertTrue(portfolio.lastRun().orElseThrow().report().proofAuthorized());
    }

    private static PortfolioRequest request(
        SolverObjective objective,
        PortfolioPolicy policy,
        PortfolioBudget budget
    ) {
        return PortfolioRequest.create(
            obligation("x + 0", "x", RequestedEvidence.SYMBOLIC_CERTIFICATE),
            objective, policy, budget, "test-portfolio/v1");
    }

    private static Obligation obligation(
        String left,
        String right,
        RequestedEvidence evidence
    ) {
        return new SolverObligationFactory().equality(
            "portfolio-test-obligation", left, right, List.of(), evidence,
            new SourceProvenance(
                "portfolio-test", "reference",
                SolverIr.sha256("portfolio-test-revision/v1")));
    }

    private static TestBackend backend(
        String id,
        List<BackendRole> roles,
        long cost,
        ResultStatus status,
        SideEffect sideEffect
    ) {
        return backend(id, roles, cost, status,
            Arrays.asList(BinaryOperator.values()),
            BackendAvailability.AVAILABLE, sideEffect);
    }

    private static TestBackend backend(
        String id,
        List<BackendRole> roles,
        long cost,
        ResultStatus status,
        List<BinaryOperator> operators,
        BackendAvailability availability,
        SideEffect sideEffect
    ) {
        BackendCapabilityProfile profile = BackendCapabilityProfile.create(
            id, "1", List.of(SolverIr.OBLIGATION_SCHEMA),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            Arrays.asList(Relation.values()),
            List.of(Sort.REAL), operators, true,
            Arrays.asList(RequestedEvidence.values()), roles,
            CostClass.LOW, cost, true, true, availability,
            SolverIr.sha256("test-backend-configuration:" + id));
        return new TestBackend(profile, obligation -> {
            sideEffect.run(obligation);
            return execution(profile, obligation, status);
        });
    }

    private static SolverExecution execution(
        BackendCapabilityProfile profile,
        Obligation obligation,
        ResultStatus status
    ) {
        BackendDescriptor descriptor = new BackendDescriptor(
            profile.backendId(), profile.backendVersion(),
            profile.supportedTheories(), profile.supportedGoalRelations(),
            profile.supportedEvidence(), profile.deterministic());
        SolverTranslation translation = SolverTranslation.create(
            obligation, descriptor, TranslationStatus.LOSSLESS, List.of(),
            Map.of(
                "goal.left", obligation.goal().left().canonicalMaterial(),
                "goal.right", obligation.goal().right().canonicalMaterial()));
        String certificate = status == ResultStatus.CONFIRMED
                || status == ResultStatus.REFUTED
            ? SolverIr.sha256(profile.backendId() + ':' + status.name()) : "";
        SolverResult result = SolverResult.create(
            obligation, descriptor, status, TranslationStatus.LOSSLESS,
            profile.roles().stream().map(Enum::name).toList(), List.of(),
            status.name(), Map.of(), certificate);
        return SolverExecution.create(obligation, translation, result);
    }

    @FunctionalInterface
    private interface SideEffect {
        void run(Obligation obligation);
    }

    private record TestBackend(
        BackendCapabilityProfile profile,
        java.util.function.Function<Obligation, SolverExecution> function
    ) implements PortfolioBackend {
        @Override
        public SolverExecution execute(Obligation obligation) {
            return function.apply(obligation);
        }
    }
}
