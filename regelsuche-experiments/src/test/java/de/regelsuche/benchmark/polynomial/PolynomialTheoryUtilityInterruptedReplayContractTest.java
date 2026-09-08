package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Characterizes a pre-existing evidence gap; does not execute a study run. */
class PolynomialTheoryUtilityInterruptedReplayContractTest {
    @Test
    void anActualInterruptedReplayCannotBeEncodedByTheFrozenNegativeResultContract() {
        var parser = new ExpressionParser();
        var seed = parser.parseExactTerm("x^2-1");
        var authorized = new ExactNestedFactorizationTransformationPipeline().transform(seed,
            new TreePosition(List.of(), ExpressionFormatter.format(seed.expression())),
            NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
        assertTrue(authorized.transformed());
        var store = new VerifiedPolynomialTransitionCacheStore();
        var primitive = authorized.transformation().orElseThrow();
        var retained = store.retain(primitive, "component-test",
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            new VerifiedPolynomialTransitionCacheStore.Observation(primitive.certificateHash(), List.of("component-fixture"), List.of()));
        var lookup = store.lookup(retained.lookupRequest());
        var released = store.replay(lookup);
        assertTrue(released.replayed());

        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> value.caseId().equals("four-identical-occurrences")).findFirst().orElseThrow();
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.caseId().equals(formation.caseId())
                && value.profileId().equals("VERIFIED_DERIVED_MACRO_CACHE")
                && value.checkpointId().equals("CP03_1_OF_3")).findFirst().orElseThrow();
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        authority.consume(prefixed("cache.lookup.", lookup.lookupWork()));
        authority.consume(prefixed("cache.replay.", released.replayWork()));
        var parsed = parser.parseExactTerm(formation.sourceExpression());
        var selector = new TreePosition(List.of(0, 0), "pending");
        var position = new TreePosition(selector.path(),
            ExpressionFormatter.format(selector.subtreeAt(parsed.expression()).orElseThrow()));
        var replay = new ExactNestedFactorizationTransformationPipeline(authority)
            .replay(parsed, position, released.authorization().orElseThrow());
        assertEquals(ExactNestedFactorizationTransformationPipeline.Status.BUDGET_INCONCLUSIVE, replay.status());
        assertTrue(authority.work().cacheReplayWork() > 0);
        assertTrue(authority.work().mechanicalWork() <= input.totalMechanicalWork());
        var failure = assertThrows(IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(input, formation,
                PolynomialTheoryUtilityCandidateResult.TerminalStatus.BUDGET_INCONCLUSIVE,
                replay.detailCode(), authority.work(), List.of(), "NOT_REQUESTED"));
        assertEquals("transition-free result retained derived-cache mutation work", failure.getMessage());
    }

    private static PolynomialWorkLedger prefixed(String prefix, PolynomialWorkLedger work) {
        var stages = new LinkedHashMap<String, Long>();
        work.stages().forEach((stage, units) -> stages.put(prefix + stage, units));
        return new PolynomialWorkLedger(stages);
    }
}
