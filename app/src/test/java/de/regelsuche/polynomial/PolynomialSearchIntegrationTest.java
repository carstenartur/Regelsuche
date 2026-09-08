package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.PolynomialTheoryFormationOutcomeLedger;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.search.program.RewritePrograms;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkSearchReplay;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.PolynomialDerivedMacroCache;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolynomialSearchIntegrationTest {
    private static final long WORK = 40_000_000;

    @Test
    void miningHandoffFeedsTheActualRewriteProgramWithoutStandardPromotion() {
        var session = open(PolynomialSearchIntegration.Profile.VERIFIED_DERIVED_MACRO_CACHE);
        var outcomes = new PolynomialTheoryFormationOutcomeLedger(8);
        var macros = new PolynomialDerivedMacroCache(4);
        var observer = session.learningObserver(macros, outcomes, WORK);
        var miner = new RuleCandidateMiner(new KnownRuleRepository(), (left, right) -> true, observer);
        String source = "x^2 - 1";
        String target = "(x - 1) * (x + 1)";
        var path = new SuccessfulTransformationPath("generation:1", source, target,
            List.of(source, target), List.of("observed-factorization"),
            new ExpressionScore(12, 6, 1, 3, 0), new ExpressionScore(8, 5, 0, 3, 0),
            true, "symbolically-verified-source-path", Map.of(), List.of());
        assertTrue(miner.mineFromSinglePathForValidatedSchema(path).isEmpty());
        var authority = outcomes.entries().getFirst().classification().transformation().orElseThrow();
        var searchSource = session.sourceAt(List.of(0)).orElseThrow();
        String expression = "f(" + authority.occurrence().sourceText() + ",9007199254740993)";
        var execution = new RewriteProgramInterpreter().executeBudgetedSource(
            RewritePrograms.budgetedSource("polynomial-cache", searchSource), expression, WORK);
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, execution.status());
        var transition = execution.candidates().getFirst();
        assertEquals(authority.certificateHash(), transition.evidenceHash());
        assertEquals(0, transition.primitiveRewriteSteps());
        assertEquals(List.of(ExactFactorizationTransformationPipeline.TRANSFORMATION_ID), transition.exactTheoryStepIds());
        assertTrue(transition.transformedExpression().contains("9007199254740993"));
        assertTrue(searchSource.lastObservation().orElseThrow().cacheReplay().isPresent());
        assertEquals(1, macros.size());
        assertEquals(1, searchSource.cacheStats().replays());
        // Re-observing the same path must hand off its classification, not the
        // last unrelated ledger record or a newly manufactured string proof.
        assertTrue(miner.mineFromSinglePathForValidatedSchema(path).isEmpty());
        assertEquals(1, searchSource.cacheStats().retainedEntries());
    }

    @Test
    void allProfilesAreAblatableAndSessionsNeverShareCaches() {
        var off = open(PolynomialSearchIntegration.Profile.NO_FACTORIZATION);
        assertTrue(off.sourceAt(List.of()).isEmpty());
        assertTrue(off.specializedControls().isEmpty());
        var quartic = open(PolynomialSearchIntegration.Profile.SPECIALIZED_BINARY_QUARTIC_CONTROL);
        assertTrue(quartic.sourceAt(List.of()).isEmpty());
        assertEquals(1, quartic.specializedControls().size());
        assertFalse(quartic.specializedControls().getFirst().generateCandidates("x^4-5*x^2+4").isEmpty());
        var direct = open(PolynomialSearchIntegration.Profile.ON_DEMAND_VERIFIED_FACTORIZATION);
        var cached = open(PolynomialSearchIntegration.Profile.VERIFIED_DERIVED_MACRO_CACHE);
        var fresh = open(PolynomialSearchIntegration.Profile.VERIFIED_DERIVED_MACRO_CACHE);
        var directSource = direct.sourceAt(List.of()).orElseThrow();
        var cacheSource = cached.sourceAt(List.of()).orElseThrow();
        assertNotEquals(directSource.identity(), cacheSource.identity());
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, cacheSource.transform("x^2-1", WORK).status());
        assertEquals(1, cached.sourceAt(List.of()).orElseThrow().cacheStats().retainedEntries());
        assertEquals(0, fresh.sourceAt(List.of()).orElseThrow().cacheStats().retainedEntries());
        assertEquals(1, cacheSource.cacheStats().misses());
        assertThrows(IllegalStateException.class, () -> direct.learningObserver(
            new PolynomialDerivedMacroCache(1), new PolynomialTheoryFormationOutcomeLedger(1), WORK));
    }

    @Test
    void optionalBackendAbsenceAndFailureNeverTurnIntoNativeFallback() {
        assertThrows(IllegalArgumentException.class,
            () -> open(PolynomialSearchIntegration.Profile.OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION));
        AtomicInteger requests = new AtomicInteger();
        FactorizationEngine<ExactRational> unavailable = new FactorizationEngine<>() {
            public String engineId() { return PolynomialSearchIntegration.EXTERNAL_ENGINE_ID; }
            public String coefficientDomainId() { return ExactRationalField.DOMAIN_ID; }
            public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
                requests.incrementAndGet();
                throw new IllegalStateException("external runtime unavailable");
            }
        };
        var session = PolynomialSearchIntegration.open(
            PolynomialSearchIntegration.Profile.OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION,
            Optional.of(unavailable), 2);
        var externalSource = session.sourceAt(List.of()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> {
            var result = externalSource.transform("x^2-1", WORK);
            fail("external failure became " + result.status() + ":" + result.detailCode()
                + "; requests=" + requests.get());
        });
        assertEquals(1, requests.get());
        assertTrue(externalSource.lastWork().totalWorkUnits() > 0);
        assertEquals(ExactNestedFactorizationTransformationPipeline.Status.TECHNICAL_FAILURE,
            externalSource.lastObservation().orElseThrow().occurrence().status());
        assertThrows(IllegalArgumentException.class, () -> PolynomialSearchIntegration.open(
            PolynomialSearchIntegration.Profile.ON_DEMAND_VERIFIED_FACTORIZATION, Optional.of(unavailable), 2));
    }

    @Test
    void actualBestFirstFrontierRetainsTheoryWorkAndReplaysIndependentColdRuns() {
        String expression = "x^2-1";
        String target = open(PolynomialSearchIntegration.Profile.ON_DEMAND_VERIFIED_FACTORIZATION)
            .sourceAt(List.of()).orElseThrow().transform(expression, WORK).candidates().getFirst().transformedExpression();
        var session = open(PolynomialSearchIntegration.Profile.VERIFIED_DERIVED_MACRO_CACHE);
        var expansion = (SearchExpansionSource.ExactPolynomial) session.frontierAt(List.of()).orElseThrow();
        var strategy = new WorkBudgetBestFirstSearchStrategy();
        var first = strategy.search(problem(expression, target, expansion, WORK));
        assertTrue(first.reached(), first.status().name());
        assertFalse(first.expansions().isEmpty());
        assertEquals(0, first.reachedState().primitiveDepth());
        assertEquals(1, first.reachedState().executionWork().exactTheorySteps());
        assertTrue(first.reachedState().executionWork().exactTheoryWorkUnits() > 0);
        assertEquals(expansion.source().lastWork().totalWorkUnits(),
            first.metrics().transformationWork().delegatedMechanicalWorkUnits());
        String json = first.toCanonicalJson();
        assertTrue(json.contains("delegatedMechanicalWorkUnits"));
        var fresh = open(PolynomialSearchIntegration.Profile.VERIFIED_DERIVED_MACRO_CACHE).frontierAt(List.of()).orElseThrow();
        assertEquals(json, WorkSearchReplay.verify(json, problem(expression, target, fresh, WORK)).toCanonicalJson());
        var replay = strategy.search(problem(expression, target, expansion, WORK));
        assertTrue(replay.reached());
        assertEquals(first.reachedState().executionWork(), replay.reachedState().executionWork());
        assertEquals(1, expansion.source().cacheStats().replays());
        var verified = expansion.source().lastVerifiedExecution().orElseThrow();
        var evidence = ExactTheoryEvidence.fromVerified(verified);
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(verified.result()));
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(evidence.binding()));
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(evidence.binding().canonicalEvidenceJson()));
    }

    @Test
    void frontierRefusesTinyTheoryAuthorityAndChargesMechanicsBeforeEnqueue() {
        var expansion = (SearchExpansionSource.ExactPolynomial) open(
            PolynomialSearchIntegration.Profile.ON_DEMAND_VERIFIED_FACTORIZATION).frontierAt(List.of()).orElseThrow();
        var tiny = expansion.expand("x^2-1", new PathBudget(0, 1));
        assertFalse(tiny.complete());
        assertTrue(tiny.candidates().isEmpty());
        assertTrue(expansion.source().lastVerifiedExecution().isEmpty());
        var exhausted = new WorkBudgetBestFirstSearchStrategy().search(problem("x^2-1", "unreached", expansion, 100));
        assertEquals(WorkBudgetBestFirstSearchStrategy.Status.WORK_BUDGET, exhausted.status());
        assertEquals(0, exhausted.metrics().enqueuedStates());
        assertTrue(exhausted.metrics().transformationWork().delegatedMechanicalWorkUnits() > 100);
        assertFalse(exhausted.reached());
    }

    private WorkBudgetBestFirstSearchStrategy.Problem problem(String source, String target,
            SearchExpansionSource expansion, long mechanical) {
        return new WorkBudgetBestFirstSearchStrategy.Problem(source, target, expansion,
            new ExpressionScorer(), new ExpressionCanonicalizer(),
            new WorkBudgetBestFirstSearchStrategy.Budget(0, WORK, 10, 10, 10, mechanical));
    }

    private PolynomialSearchIntegration.Session open(PolynomialSearchIntegration.Profile profile) {
        return PolynomialSearchIntegration.open(profile, Optional.empty(), 2);
    }
}
