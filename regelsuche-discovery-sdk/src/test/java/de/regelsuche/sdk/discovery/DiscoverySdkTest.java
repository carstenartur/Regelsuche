package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiscoverySdkTest {
    @Test
    void runsABuilderDefinedDomainAndKeepsCounterexamples() {
        DiscoveryRun<MultiplierCandidate, MultiplierCertificate> run =
            RegelsucheDiscovery.forDomain(sampleDomain())
                .campaign("sdk-multiplier-success")
                .seed(
                    "powers-of-two",
                    "observed=2,4,8,16;holdout=32,64",
                    "sdk-test"
                )
                .budget(DiscoveryBudgets.of(6, 16, 32, 8, 8, 16))
                .run();

        assertEquals(Outcome.CONFIRMED, run.outcome());
        assertEquals(2, run.selectedCandidate().orElseThrow().multiplier());
        assertEquals(2, run.selectedCertificate().orElseThrow().multiplier());
        assertEquals(1, run.counterexamples().size());
        assertTrue(run.counterexamples().getFirst().contains("multiplier 1"));
        assertFalse(run.executedWork().isEmpty());
        assertTrue(run.canonicalEvidence().contains("\"outcome\":\"CONFIRMED\""));
        assertThrows(
            IllegalArgumentException.class,
            () -> new DiscoveryRun<>(
                Optional.empty(),
                Optional.empty(),
                run.evidence()
            )
        );
    }

    @Test
    void reportsBudgetExhaustionWithoutInventingACertificate() {
        DiscoveryRun<MultiplierCandidate, MultiplierCertificate> run =
            RegelsucheDiscovery.forDomain(sampleDomain())
                .campaign("sdk-multiplier-budget")
                .seed(
                    "powers-of-two-budget",
                    "observed=2,4,8,16;holdout=32,64",
                    "sdk-test"
                )
                .budget(DiscoveryBudgets.tiny())
                .run();

        assertEquals(Outcome.BUDGET_EXHAUSTED, run.outcome());
        assertTrue(run.selectedCandidate().isEmpty());
        assertTrue(run.selectedCertificate().isEmpty());
        assertFalse(run.isConfirmed());
        assertThrows(
            IllegalArgumentException.class,
            () -> new DiscoveryRun<>(
                Optional.of(new Object()),
                Optional.empty(),
                run.evidence()
            )
        );
    }

    @Test
    void loadsAnExternalStyleProviderThroughServiceLoader() {
        DiscoveryDomainCatalog catalog =
            DiscoveryDomainCatalog.load(getClass().getClassLoader());

        DiscoveryDomainCatalog.Registration registration = catalog
            .find("sdk-multiplier-search", "v1")
            .orElseThrow();

        assertEquals("sdk-test-provider", registration.providerId());
        assertEquals("1", registration.providerVersion());
    }

    @Test
    void normalizesProviderAndDomainIterationOrder() {
        DiscoveryDomainCatalog catalog = DiscoveryDomainCatalog.fromProviders(List.of(
            provider("z-provider", sampleDomain("z-domain"), sampleDomain("a-domain")),
            provider("a-provider", sampleDomain("m-domain"))
        ));

        assertEquals(
            List.of(
                "a-provider:m-domain",
                "z-provider:a-domain",
                "z-provider:z-domain"
            ),
            catalog.registrations().stream()
                .map(entry -> entry.providerId() + ":" + entry.domain().domainId())
                .toList()
        );
    }

    @Test
    void rejectsIncompleteDomainDefinitions() {
        var builder =
            DiscoveryDomainBuilder.<Integer, Integer, Integer>domain(
                "incomplete-domain",
                "v1"
            );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            builder::build
        );
        assertTrue(failure.getMessage().contains("generator"));
    }

    static DiscoveryDomain<
        Integer,
        MultiplierCandidate,
        MultiplierCertificate
    > sampleDomain() {
        return sampleDomain("sdk-multiplier-search");
    }

    private static DiscoveryDomain<
        Integer,
        MultiplierCandidate,
        MultiplierCertificate
    > sampleDomain(String domainId) {
        List<Long> observed = List.of(2L, 4L, 8L, 16L);
        List<Long> holdout = List.of(32L, 64L);

        return DiscoveryDomainBuilder
            .<Integer, MultiplierCandidate, MultiplierCertificate>domain(
                domainId,
                "v1"
            )
            .generator(seed -> List.of(1))
            .stateCodec(Object::toString)
            .invariant(
                "positive-multiplier",
                value -> value >= 1
                    ? InvariantResult.pass()
                    : InvariantResult.fail("multiplier-must-be-positive")
            )
            .operator(
                "next-multiplier",
                value -> value >= 4
                    ? List.of()
                    : List.of(new Successor<>(
                        "try-multiplier-" + (value + 1),
                        value + 1,
                        1,
                        false,
                        List.of(),
                        Map.of("candidateMultiplier", Integer.toString(value + 1))
                    ))
            )
            .objective(value -> new ObjectiveAssessment(
                100 - value,
                true,
                Map.of("multiplier", Integer.toString(value))
            ))
            .candidate(
                context -> new MultiplierCandidate(
                    context.currentState(),
                    observed,
                    holdout
                ),
                MultiplierCandidate::canonical
            )
            .counterexamples((candidate, budget) ->
                observedCounterexample(candidate, budget))
            .evaluator(candidate -> evaluate(candidate, observed, holdout))
            .certificate(
                "MULTIPLIER_SEQUENCE_WITNESS",
                MultiplierCertificate::canonical,
                MultiplierCertificate::canonical
            )
            .build();
    }

    private static DiscoveryDomainProvider provider(
            String id,
            DiscoveryDomain<?, ?, ?>... domains
    ) {
        return new DiscoveryDomainProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Collection<DiscoveryDomain<?, ?, ?>> domains() {
                return List.of(domains);
            }
        };
    }

    private static CounterexampleResult observedCounterexample(
            MultiplierCandidate candidate,
            int budget
    ) {
        int checks = Math.min(budget, candidate.observed().size() - 1);
        for (int index = 0; index < checks; index++) {
            long expected = candidate.observed().get(index)
                * candidate.multiplier();
            long actual = candidate.observed().get(index + 1);
            if (expected != actual) {
                return CounterexampleResult.found(
                    index + 1,
                    "multiplier " + candidate.multiplier()
                        + " predicts " + expected + " but observed " + actual,
                    Map.of("index", Integer.toString(index + 1))
                );
            }
        }
        if (checks < candidate.observed().size() - 1) {
            return CounterexampleResult.inconclusive(
                checks,
                "counterexample budget did not cover every observed transition",
                Map.of()
            );
        }
        return CounterexampleResult.noneFound(
            checks,
            Map.of("checkedTransitions", Integer.toString(checks))
        );
    }

    private static Evaluation<MultiplierCertificate> evaluate(
            MultiplierCandidate candidate,
            List<Long> observed,
            List<Long> holdout
    ) {
        long current = observed.getLast();
        for (long expected : holdout) {
            current = Math.multiplyExact(current, candidate.multiplier());
            if (current != expected) {
                return Evaluation.refuted(
                    "candidate failed the retained holdout",
                    Map.of(
                        "expected", Long.toString(expected),
                        "actual", Long.toString(current)
                    )
                );
            }
        }
        return Evaluation.confirmed(
            new MultiplierCertificate(
                candidate.multiplier(),
                observed,
                holdout
            ),
            "candidate reproduces observed and retained holdout terms",
            Map.of("holdoutTerms", Integer.toString(holdout.size()))
        );
    }

    record MultiplierCandidate(
        int multiplier,
        List<Long> observed,
        List<Long> holdout
    ) {
        MultiplierCandidate {
            observed = List.copyOf(observed);
            holdout = List.copyOf(holdout);
        }

        String canonical() {
            return "multiplier=" + multiplier
                + ";observed=" + observed
                + ";holdout=" + holdout;
        }
    }

    record MultiplierCertificate(
        int multiplier,
        List<Long> observed,
        List<Long> holdout
    ) {
        MultiplierCertificate {
            observed = List.copyOf(observed);
            holdout = List.copyOf(holdout);
        }

        String canonical() {
            return "multiplier=" + multiplier
                + ";verifiedObserved=" + observed
                + ";verifiedHoldout=" + holdout;
        }
    }
}
