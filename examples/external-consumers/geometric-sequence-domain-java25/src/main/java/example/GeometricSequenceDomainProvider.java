package example;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.sdk.discovery.DiscoveryDomainBuilder;
import de.regelsuche.sdk.discovery.DiscoveryDomainProvider;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Small external discovery-domain provider used by the Java SDK tutorial. */
public final class GeometricSequenceDomainProvider
        implements DiscoveryDomainProvider {
    public static final String DOMAIN_ID = "example-geometric-sequence";
    public static final String REVISION = "v1";

    @Override
    public String id() {
        return "example-geometric-sequence-provider";
    }

    @Override
    public String provenance() {
        return "Regelsuche external-consumer example";
    }

    @Override
    public Collection<DiscoveryDomain<?, ?, ?>> domains() {
        return List.of(domain());
    }

    public static DiscoveryDomain<State, Candidate, Certificate> domain() {
        return DiscoveryDomainBuilder
            .<State, Candidate, Certificate>domain(DOMAIN_ID, REVISION)
            .generator(seed -> {
                Input input = Input.parse(seed.payload());
                return List.of(new State(1, input));
            })
            .stateCodec(State::canonical)
            .invariant("valid-input", state -> state.input().isValid()
                ? InvariantResult.pass()
                : InvariantResult.fail("invalid-sequence-input"))
            .operator("next-multiplier", state ->
                state.multiplier() >= state.input().maxMultiplier()
                    ? List.of()
                    : List.of(new Successor<>(
                        "try-multiplier-" + (state.multiplier() + 1),
                        new State(state.multiplier() + 1, state.input()),
                        1,
                        false,
                        List.of(),
                        Map.of(
                            "multiplier",
                            Integer.toString(state.multiplier() + 1)
                        )
                    )))
            .objective(state -> new ObjectiveAssessment(
                1_000_000 - state.multiplier(),
                true,
                Map.of("multiplier", Integer.toString(state.multiplier()))
            ))
            .candidate(
                context -> new Candidate(
                    context.currentState().multiplier(),
                    context.currentState().input()
                ),
                Candidate::canonical
            )
            .counterexamples(GeometricSequenceDomainProvider::counterexamples)
            .evaluator(GeometricSequenceDomainProvider::evaluate)
            .certificate(
                "GEOMETRIC_SEQUENCE_WITNESS",
                Certificate::canonical,
                Certificate::canonical
            )
            .build();
    }

    private static CounterexampleResult counterexamples(
            Candidate candidate,
            int attemptBudget
    ) {
        List<Long> observed = candidate.input().observed();
        int required = observed.size() - 1;
        int checks = Math.min(required, attemptBudget);
        for (int index = 0; index < checks; index++) {
            long predicted = Math.multiplyExact(
                observed.get(index),
                candidate.multiplier()
            );
            long actual = observed.get(index + 1);
            if (predicted != actual) {
                return CounterexampleResult.found(
                    index + 1,
                    "multiplier " + candidate.multiplier()
                        + " predicts " + predicted
                        + " at observed index " + (index + 1)
                        + " but the value is " + actual,
                    Map.of("observedIndex", Integer.toString(index + 1))
                );
            }
        }
        if (checks < required) {
            return CounterexampleResult.inconclusive(
                checks,
                "counterexample budget did not cover the observed prefix",
                Map.of(
                    "checkedTransitions", Integer.toString(checks),
                    "requiredTransitions", Integer.toString(required)
                )
            );
        }
        return CounterexampleResult.noneFound(
            checks,
            Map.of("checkedTransitions", Integer.toString(checks))
        );
    }

    private static Evaluation<Certificate> evaluate(Candidate candidate) {
        Input input = candidate.input();
        long current = input.observed().getLast();
        for (int index = 0; index < input.holdout().size(); index++) {
            current = Math.multiplyExact(current, candidate.multiplier());
            long expected = input.holdout().get(index);
            if (current != expected) {
                return Evaluation.refuted(
                    "candidate failed the retained holdout",
                    Map.of(
                        "holdoutIndex", Integer.toString(index),
                        "predicted", Long.toString(current),
                        "expected", Long.toString(expected)
                    )
                );
            }
        }
        return Evaluation.confirmed(
            new Certificate(
                candidate.multiplier(),
                input.observed(),
                input.holdout()
            ),
            "one integer multiplier reproduces the observed and holdout terms",
            Map.of("holdoutTerms", Integer.toString(input.holdout().size()))
        );
    }

    public record State(int multiplier, Input input) {
        public String canonical() {
            return "multiplier=" + multiplier + ";" + input.canonical();
        }
    }

    public record Candidate(int multiplier, Input input) {
        public String canonical() {
            return "multiplier=" + multiplier + ";" + input.canonical();
        }
    }

    public record Certificate(
        int multiplier,
        List<Long> observed,
        List<Long> holdout
    ) {
        public Certificate {
            observed = List.copyOf(observed);
            holdout = List.copyOf(holdout);
        }

        public String canonical() {
            return "multiplier=" + multiplier
                + ";verifiedObserved=" + observed
                + ";verifiedHoldout=" + holdout;
        }
    }

    public record Input(
        List<Long> observed,
        List<Long> holdout,
        int maxMultiplier
    ) {
        public Input {
            observed = List.copyOf(observed);
            holdout = List.copyOf(holdout);
        }

        public boolean isValid() {
            return observed.size() >= 2
                && !holdout.isEmpty()
                && maxMultiplier >= 1;
        }

        public String canonical() {
            return "observed=" + observed
                + ";holdout=" + holdout
                + ";maxMultiplier=" + maxMultiplier;
        }

        public static Input parse(String payload) {
            Map<String, String> values = java.util.Arrays.stream(
                    payload.split(";"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    parts -> parts[0].trim(),
                    parts -> parts[1].trim()
                ));
            return new Input(
                csv(values.get("observed")),
                csv(values.get("holdout")),
                Integer.parseInt(values.getOrDefault("maxMultiplier", "8"))
            );
        }

        private static List<Long> csv(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .toList();
        }
    }
}
