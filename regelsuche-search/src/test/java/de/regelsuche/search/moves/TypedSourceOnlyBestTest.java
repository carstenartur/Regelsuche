package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TypedSourceOnlyBestTest {
    private static final Expr X = new VariableExpr("x");
    private static final Expr ZERO = new NumberExpr(0);
    private static final Expr INPUT = new BinaryExpr(X, BinaryOperator.ADD, ZERO);
    private static final Expr OTHER = new BinaryExpr(ZERO, BinaryOperator.ADD, X);
    private static final PatternExpr A = PatternExpr.var("A");
    private static final PatternExpr PATTERN = PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0));
    private static final AstRewriteTransport TRANSPORT = new AstRewriteTransport(List.of(
        new PatternRewriteRule("zero", PATTERN, A),
        new PatternRewriteRule("commute", PATTERN, PatternExpr.op(BinaryOperator.ADD, PatternExpr.num(0), A))), 64, 128);

    @Test void bestSearchKeepsExploringEqualQualityAlternativesAndPaysForTheirInspection() {
        for (var contract : SearchContinuationContract.values()) {
            var firstInspections = new AtomicInteger();
            var bestInspections = new AtomicInteger();
            var first = new TypedSourceOnlySearch().searchUntil(problem(10000, MoveSearch.Mode.FAST),
                objective(firstInspections), 0, contract);
            var best = new TypedSourceOnlySearch().searchBest(problem(10000, MoveSearch.Mode.FAST),
                objective(bestInspections), contract);

            assertEquals(MoveSearch.Outcome.QUALITY_REACHED, first.search().outcome());
            assertNotEquals(MoveSearch.Outcome.QUALITY_REACHED, best.search().outcome());
            assertTrue(best.search().reachedStates().stream().anyMatch(s -> s.expression().equals(X)));
            assertTrue(best.search().reachedStates().stream().anyMatch(s -> s.expression().equals(OTHER)));
            assertTrue(bestInspections.get() > firstInspections.get());
            assertTrue(best.queryWork() > first.queryWork());
            assertEquals(1, best.witness().size());
            assertTrue(best.witness().get(0).verification().accepted());
            assertTrue(best.replayWork() > 0);
            assertTrue(best.withinBudget());
        }
    }

    @Test void exhaustedBudgetCannotBeReportedAsAQualityStop() {
        for (var contract : SearchContinuationContract.values()) {
            var result = new TypedSourceOnlySearch().searchBest(problem(10, MoveSearch.Mode.FAST),
                state -> new TypedSourceOnlySearch.Score(0, 11), contract);
            assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.search().outcome());
            assertFalse(result.withinBudget());
        }
    }

    @Test void bestSearchCannotSilentlyChangeCompleteReferenceMode() {
        assertThrows(IllegalArgumentException.class, () -> new TypedSourceOnlySearch().searchBest(
            problem(10000, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE), objective(new AtomicInteger()),
            SearchContinuationContract.PATH_SENSITIVE));
    }

    @Test void alternativesAreChosenWithoutATargetAndEachPathIsReplayed() {
        for (var contract : SearchContinuationContract.values()) {
            var alternatives = new TypedSourceOnlySearch().searchAlternatives(problem(10000, MoveSearch.Mode.FAST),
                objective(new AtomicInteger()), 0, contract, 2000, 8);
            assertTrue(alternatives.withinBudget());
            assertEquals(List.of(X, OTHER), alternatives.candidates().stream().map(c -> c.state().expression()).toList());
            assertFalse(alternatives.truncated());
            assertTrue(alternatives.selectionWork() > 0);
            assertTrue(alternatives.replayWork() > 0);
            assertEquals(alternatives.search().totalWork() + alternatives.selectionWork() + alternatives.replayWork(),
                alternatives.totalWork());
            for (var candidate : alternatives.candidates()) {
                assertEquals(1, candidate.witness().size());
                assertEquals(INPUT, candidate.witness().get(0).source().expression());
                assertEquals(candidate.state(), candidate.witness().get(0).target());
                assertTrue(candidate.witness().get(0).verification().accepted());
            }
        }
    }

    @Test void alternativeLimitAndReserveAreExplicit() {
        var engine = new TypedSourceOnlySearch();
        var limited = engine.searchAlternatives(problem(10000, MoveSearch.Mode.FAST),
            objective(new AtomicInteger()), 0, SearchContinuationContract.PATH_SENSITIVE, 2000, 1);
        assertEquals(1, limited.candidates().size());
        assertTrue(limited.truncated());
        assertThrows(IllegalArgumentException.class, () -> engine.searchAlternatives(problem(100, MoveSearch.Mode.FAST),
            objective(new AtomicInteger()), 0, SearchContinuationContract.PATH_SENSITIVE, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> engine.searchAlternatives(problem(100, MoveSearch.Mode.FAST),
            objective(new AtomicInteger()), 0, SearchContinuationContract.PATH_SENSITIVE, 1, 0));
    }

    @Test void differentAdmittedPathsToTheSameExpressionProduceOneAlternative() {
        var duplicates = new AstRewriteTransport(List.of(
            new PatternRewriteRule("commute-one", PATTERN, PatternExpr.op(BinaryOperator.ADD, PatternExpr.num(0), A)),
            new PatternRewriteRule("commute-two", PATTERN, PatternExpr.op(BinaryOperator.ADD, PatternExpr.num(0), A))), 64, 128);
        var base = problem(10000, MoveSearch.Mode.FAST);
        var descriptor = base.providers().getFirst().descriptor();
        var problem = new TypedMoveSearch.Problem(base.source(), base.context(),
            List.of(TypedMoveSearch.primitiveProvider(descriptor, duplicates)), base.policy(),
            TypedMoveSearch.primitiveReplay(duplicates), base.stateScore(), base.mode(), base.scheduling(), base.budget());
        var result = new TypedSourceOnlySearch().searchAlternatives(problem, objective(new AtomicInteger()), 0,
            SearchContinuationContract.PATH_SENSITIVE, 2000, 8);
        assertEquals(2, result.search().search().events().stream()
            .filter(e -> e.decision() == MoveSearch.Decision.ENQUEUED).count());
        assertEquals(List.of(OTHER), result.candidates().stream().map(c -> c.state().expression()).toList());
        assertTrue(result.withinBudget());
    }

    @Test void independentReplayMayCostMoreWithoutChangingTheProof() {
        var calls = new AtomicInteger();
        var base = problem(10000, MoveSearch.Mode.FAST);
        var verifier = base.verifier();
        var changing = new TypedMoveSearch.Problem(base.source(), base.context(), base.providers(), base.policy(),
            (source, move, context) -> {
                var receipt = verifier.verify(source, move, context);
                long extra = move.ruleId().equals("commute") ? calls.getAndIncrement() : 0;
                return new MoveVerifier.Verification(receipt.accepted(), receipt.work() + extra, receipt.receipts(), receipt.reason());
            }, base.stateScore(), base.mode(), base.scheduling(), base.budget(), base.stateValue());
        var alternatives = new TypedSourceOnlySearch().searchAlternatives(changing,
            objective(new AtomicInteger()), 0, SearchContinuationContract.PATH_SENSITIVE, 2000, 8);
        assertTrue(calls.get() >= 2);
        assertEquals(2, alternatives.candidates().size());
        assertTrue(alternatives.withinBudget());
    }

    @Test void aReplayThatExceedsTheSharedBudgetCannotBeAdvertisedAsSuccess() {
        var calls = new AtomicInteger();
        var base = problem(2000, MoveSearch.Mode.FAST);
        var verifier = base.verifier();
        var expensive = new TypedMoveSearch.Problem(base.source(), base.context(), base.providers(), base.policy(),
            (source, move, context) -> {
                var receipt = verifier.verify(source, move, context);
                long extra = move.ruleId().equals("commute") && calls.getAndIncrement() > 0 ? 5000 : 0;
                return new MoveVerifier.Verification(receipt.accepted(), receipt.work() + extra, receipt.receipts(), receipt.reason());
            }, base.stateScore(), base.mode(), base.scheduling(), base.budget(), base.stateValue());
        var alternatives = new TypedSourceOnlySearch().searchAlternatives(expensive,
            objective(new AtomicInteger()), 0, SearchContinuationContract.PATH_SENSITIVE, 1000, 8);
        assertFalse(alternatives.withinBudget());
        assertTrue(alternatives.totalWork() > 2000);
        assertEquals(1, alternatives.candidates().size(), "the over-budget path is not admitted");
    }

    @Test void rejectingAnIndependentAlternativeReplayCannotIssueEvidence() {
        var calls = new AtomicInteger();
        var base = problem(10000, MoveSearch.Mode.FAST);
        var verifier = base.verifier();
        var rejecting = new TypedMoveSearch.Problem(base.source(), base.context(), base.providers(), base.policy(),
            (source, move, context) -> {
                var receipt = verifier.verify(source, move, context);
                if (move.ruleId().equals("commute") && calls.getAndIncrement() > 0)
                    return new MoveVerifier.Verification(false, receipt.work(), List.of(), "replay rejected");
                return receipt;
            }, base.stateScore(), base.mode(), base.scheduling(), base.budget(), base.stateValue());
        var failure = assertThrows(TypedSearchAlternatives.Failure.class,
            () -> new TypedSourceOnlySearch().searchAlternatives(rejecting, objective(new AtomicInteger()),
                0, SearchContinuationContract.PATH_SENSITIVE, 2000, 8));
        assertTrue(failure.knownWork() > 0);
        assertFalse(failure.accountingComplete());
    }

    @Test void throwingDuringCandidateSelectionCannotIssueEvidence() {
        var calls = new AtomicInteger();
        var failure = assertThrows(TypedSearchAlternatives.Failure.class,
            () -> new TypedSourceOnlySearch().searchAlternatives(problem(10000, MoveSearch.Mode.FAST), state -> {
                if (state.expression().equals(OTHER) && calls.incrementAndGet() > 1)
                    throw new IllegalStateException("selection failed");
                return new TypedSourceOnlySearch.Score(state.searchDepth() == 0 ? 1 : 0, 1);
            }, 0, SearchContinuationContract.PATH_SENSITIVE, 2000, 8));
        assertTrue(failure.knownWork() > 0);
        assertFalse(failure.accountingComplete());
    }

    @Test void exhaustedExpressionDebitPreservesCheckedCandidatesWithoutStartingTheObjective() {
        for (var contract : SearchContinuationContract.values()) {
            var engine = new TypedSourceOnlySearch();
            long allowance = searchAllowance(contract);
            var marker = new IllegalStateException("objective started at boundary");
            var observed = assertThrows(TypedSearchAlternatives.Failure.class,
                () -> engine.searchAlternatives(problem(10000, MoveSearch.Mode.FAST),
                    selectionBoundaryObjective(new AtomicInteger(), marker), 0, contract, 10000 - allowance, 8));
            assertSame(marker, observed.getCause());
            // The failed callback reports its actually paid prefix, before its own unknown work.
            long budget = observed.knownWork();
            assertTrue(budget > allowance);
            var calls = new AtomicInteger();
            var result = assertDoesNotThrow(() -> engine.searchAlternatives(problem(budget, MoveSearch.Mode.FAST),
                selectionBoundaryObjective(calls, marker), 0, contract, budget - allowance, 8));
            assertEquals(1, calls.get(), "only the search inspection, no unfunded selection callback");
            assertTruncatedFirstCandidate(result, budget);
        }
    }

    @Test void exhaustedReplayDispatchPreservesCheckedCandidatesWithoutStartingTheVerifier() {
        for (var contract : SearchContinuationContract.values()) {
            var engine = new TypedSourceOnlySearch();
            long allowance = searchAllowance(contract);
            var marker = new IllegalStateException("verifier started at boundary");
            var observed = assertThrows(TypedSearchAlternatives.Failure.class,
                () -> engine.searchAlternatives(replayBoundaryProblem(10000, new AtomicInteger(), marker),
                    objective(new AtomicInteger()), 0, contract, 10000 - allowance, 8));
            assertSame(marker, observed.getCause());
            long budget = observed.knownWork();
            assertTrue(budget > allowance);
            var calls = new AtomicInteger();
            var result = assertDoesNotThrow(() -> engine.searchAlternatives(replayBoundaryProblem(budget, calls, marker),
                objective(new AtomicInteger()), 0, contract, budget - allowance, 8));
            assertEquals(1, calls.get(), "only admission verification, no unfunded independent replay");
            assertTruncatedFirstCandidate(result, budget);
        }
    }

    private static void assertTruncatedFirstCandidate(TypedSearchAlternatives.Result result, long budget) {
        assertTrue(result.withinBudget());
        assertEquals(budget, result.totalWork());
        assertTrue(result.truncated());
        assertEquals(List.of(X), result.candidates().stream().map(c -> c.state().expression()).toList());
        assertTrue(result.candidates().getFirst().witness().getFirst().verification().accepted());
    }

    private static long searchAllowance(SearchContinuationContract contract) {
        return new TypedSourceOnlySearch().searchBest(problem(10000, MoveSearch.Mode.FAST),
            objective(new AtomicInteger()), contract).totalWork() + 1;
    }

    private static TypedSourceOnlySearch.Objective selectionBoundaryObjective(AtomicInteger calls, RuntimeException failure) {
        return state -> {
            if (state.expression().equals(OTHER) && calls.incrementAndGet() > 1) throw failure;
            return new TypedSourceOnlySearch.Score(state.searchDepth() == 0 ? 1 : 0, 1);
        };
    }

    private static TypedMoveSearch.Problem replayBoundaryProblem(long budget, AtomicInteger calls, RuntimeException failure) {
        var base = problem(budget, MoveSearch.Mode.FAST);
        return new TypedMoveSearch.Problem(base.source(), base.context(), base.providers(), base.policy(),
            (source, move, context) -> {
                if (move.ruleId().equals("commute") && calls.incrementAndGet() > 1) throw failure;
                return base.verifier().verify(source, move, context);
            }, base.stateScore(), base.mode(), base.scheduling(), base.budget(), base.stateValue());
    }

    private static TypedSourceOnlySearch.Objective objective(AtomicInteger inspections) {
        return state -> {
            inspections.incrementAndGet();
            return new TypedSourceOnlySearch.Score(state.searchDepth() == 0 ? 1 : 0, 1);
        };
    }

    private static TypedMoveSearch.Problem problem(long work, MoveSearch.Mode mode) {
        var descriptor = new MoveProvider.Descriptor("algebra", "algebra", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture");
        return new TypedMoveSearch.Problem(INPUT,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(TypedMoveSearch.primitiveProvider(descriptor, TRANSPORT)),
            TypedMoveSearch.Policy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(TRANSPORT), state -> 0,
            mode, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(8, 8, 0, 100, work));
    }
}
