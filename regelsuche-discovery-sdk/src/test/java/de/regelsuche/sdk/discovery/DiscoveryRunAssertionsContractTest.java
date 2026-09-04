package de.regelsuche.sdk.discovery;

import static de.regelsuche.sdk.discovery.DiscoveryRunAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Resource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the assertion contract through real bounded discovery runs. */
class DiscoveryRunAssertionsContractTest {
    @Test
    void acceptsRefutedRunsWithoutPublishingSelectedObjects() {
        var run = run(Evaluation.refuted("retained evaluator rejected candidate", Map.of()),
            true, List.of());

        assertSame(run, assertThat(run)
            .isRefuted()
            .hasNoCounterexamples()
            .hasContentAddressedEvidence()
            .hasNoTransitionAssumptions()
            .actual());
        assertCannotConfirm(run);
    }

    @Test
    void acceptsInconclusiveRunsWithoutUpgradingAbsentCounterexamplesToProof() {
        var run = run(Evaluation.inconclusive("insufficient evidence", Map.of()),
            true, List.of());

        assertThat(run)
            .isInconclusive()
            .hasNoCounterexamples()
            .hasContentAddressedEvidence()
            .hasNoTransitionAssumptions();
        assertCannotConfirm(run);
    }

    @Test
    void acceptsUnsupportedRunsWithoutPublishingSelectedObjects() {
        var run = run(Evaluation.unsupported("outside evaluator scope", Map.of()),
            true, List.of());

        assertThat(run)
            .isUnsupported()
            .hasNoCounterexamples()
            .hasContentAddressedEvidence()
            .hasNoTransitionAssumptions();
        assertCannotConfirm(run);
    }

    @Test
    void acceptsInvalidSeedsAndDiagnosesMissingObjectsAndWork() {
        var run = run(Evaluation.confirmed(1, "unused evaluator", Map.of()),
            false, List.of());
        String evidenceBefore = run.canonicalEvidence();

        assertThat(run)
            .isInvalidSeed()
            .hasNoCounterexamples()
            .hasExecutedWork(Resource.EXPLORED_STATES, 0)
            .hasContentAddressedEvidence()
            .hasNoTransitionAssumptions();

        for (Runnable mismatch : new Runnable[] {
            () -> assertThat(run).hasCandidate(),
            () -> assertThat(run).hasCertificate(),
            () -> assertThat(run).hasPositiveExecutedWork(Resource.EXPLORED_STATES),
            () -> assertThat(run).hasCounterexampleCount(1)
        }) {
            AssertionError failure = assertThrows(AssertionError.class, mismatch::run);
            assertTrue(failure.getMessage().contains(run.evidence().contentHash()));
        }
        assertCannotConfirm(run);
        assertEquals(evidenceBefore, run.canonicalEvidence());
    }

    @Test
    void doesNotHideTransitionAssumptionsInAConfirmedRun() {
        var run = run(Evaluation.confirmed(1, "bounded fixture certificate", Map.of()),
            true, List.of("fixture-assumption"));
        String evidenceBefore = run.canonicalEvidence();

        assertThat(run)
            .isConfirmed()
            .hasNoCounterexamples()
            .hasExecutedWork(Resource.EXPLORED_STATES, 2)
            .candidateSatisfies(value -> assertEquals(Integer.valueOf(1), value))
            .certificateSatisfies(value -> assertEquals(Integer.valueOf(1), value))
            .hasContentAddressedEvidence();

        AssertionError failure = assertThrows(AssertionError.class,
            () -> assertThat(run).hasNoTransitionAssumptions());
        assertTrue(failure.getMessage().contains("fixture-assumption"));
        assertTrue(failure.getMessage().contains(run.evidence().contentHash()));
        assertEquals(evidenceBefore, run.canonicalEvidence());
    }

    @Test
    void rejectsNullAndEmptyCounterexampleFragments() {
        var run = run(Evaluation.refuted("fixture rejection", Map.of()),
            true, List.of());

        assertThrows(IllegalArgumentException.class,
            () -> assertThat(run).hasCounterexampleContaining(null));
        assertThrows(IllegalArgumentException.class,
            () -> assertThat(run).hasCounterexampleContaining(""));
    }

    private static void assertCannotConfirm(DiscoveryRun<Integer, Integer> run) {
        String evidenceBefore = run.canonicalEvidence();
        AssertionError failure = assertThrows(AssertionError.class,
            () -> assertThat(run).isConfirmed());
        assertTrue(failure.getMessage().contains(run.evidence().contentHash()));
        assertTrue(run.outcome() != Outcome.CONFIRMED);
        assertEquals(evidenceBefore, run.canonicalEvidence());
    }

    private static DiscoveryRun<Integer, Integer> run(
        Evaluation<Integer> evaluation,
        boolean validSeed,
        List<String> assumptions
    ) {
        var domain = DiscoveryDomainBuilder.<Integer, Integer, Integer>domain(
                "sdk-assertion-contract", "v1")
            .generator(seed -> List.of(0))
            .stateCodec(Object::toString)
            .invariant("valid-fixture-state", state -> validSeed
                ? InvariantResult.pass()
                : InvariantResult.fail("invalid-fixture-seed"))
            .operator("advance-fixture", state -> state == 0
                ? List.of(new Successor<>("next-fixture-state", 1, 1,
                    false, assumptions, Map.of()))
                : List.of())
            .objective(state -> new ObjectiveAssessment(state, state == 1, Map.of()))
            .candidate(context -> context.currentState(), Object::toString)
            .counterexamples((candidate, budget) ->
                CounterexampleResult.noneFound(1, Map.of()))
            .evaluator(candidate -> evaluation)
            .certificate("BOUNDED_ASSERTION_FIXTURE", Object::toString, Object::toString)
            .build();

        return RegelsucheDiscovery.forDomain(domain)
            .campaign("sdk-assertion-contract")
            .seed("fixture-seed", "initial-state=0", "sdk-assertion-contract-test")
            .budget(DiscoveryBudgets.of(3, 8, 8, 2, 4, 4))
            .run();
    }
}
