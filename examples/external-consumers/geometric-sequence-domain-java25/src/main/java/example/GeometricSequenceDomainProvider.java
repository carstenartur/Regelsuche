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

/** Minimal external provider used by the Java SDK tutorial and consumer CI. */
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

    public static DiscoveryDomain<Plan, Plan, Certificate> domain() {
        return DiscoveryDomainBuilder.<Plan, Plan, Certificate>domain(
                DOMAIN_ID, REVISION)
            .generator(seed -> List.of(new Plan(1, Input.parse(seed.payload()))))
            .stateCodec(Plan::canonical)
            .invariant("valid-input", plan -> plan.input().valid()
                ? InvariantResult.pass()
                : InvariantResult.fail("invalid-sequence-input"))
            .operator("next-multiplier", plan -> {
                int next = plan.multiplier() + 1;
                return next > plan.input().maxMultiplier()
                    ? List.of()
                    : List.of(new Successor<>(
                        "try-multiplier-" + next,
                        new Plan(next, plan.input()),
                        1,
                        false,
                        List.of(),
                        Map.of("multiplier", Integer.toString(next))));
            })
            .objective(plan -> new ObjectiveAssessment(
                1_000_000 - plan.multiplier(),
                true,
                Map.of("multiplier", Integer.toString(plan.multiplier()))))
            .candidate(context -> context.currentState(), Plan::canonical)
            .counterexamples(GeometricSequenceDomainProvider::counterexamples)
            .evaluator(GeometricSequenceDomainProvider::evaluate)
            .certificate(
                "GEOMETRIC_SEQUENCE_WITNESS",
                Certificate::canonical,
                Certificate::canonical)
            .build();
    }

    private static CounterexampleResult counterexamples(Plan plan, int budget) {
        List<Long> values = plan.input().observed();
        int required = values.size() - 1;
        int checks = Math.min(required, budget);
        for (int index = 0; index < checks; index++) {
            long predicted = Math.multiplyExact(values.get(index), plan.multiplier());
            long actual = values.get(index + 1);
            if (predicted != actual) {
                return CounterexampleResult.found(
                    index + 1,
                    "multiplier " + plan.multiplier() + " predicts "
                        + predicted + " but observed " + actual,
                    Map.of("observedIndex", Integer.toString(index + 1)));
            }
        }
        if (checks < required) {
            return CounterexampleResult.inconclusive(
                checks,
                "counterexample budget did not cover the observed prefix",
                Map.of("requiredTransitions", Integer.toString(required)));
        }
        return CounterexampleResult.noneFound(
            checks,
            Map.of("checkedTransitions", Integer.toString(checks)));
    }

    private static Evaluation<Certificate> evaluate(Plan plan) {
        Input input = plan.input();
        long current = input.observed().getLast();
        for (int index = 0; index < input.holdout().size(); index++) {
            current = Math.multiplyExact(current, plan.multiplier());
            long expected = input.holdout().get(index);
            if (current != expected) {
                return Evaluation.refuted(
                    "candidate failed the retained holdout",
                    Map.of(
                        "holdoutIndex", Integer.toString(index),
                        "predicted", Long.toString(current),
                        "expected", Long.toString(expected)));
            }
        }
        return Evaluation.confirmed(
            new Certificate(plan.multiplier(), input),
            "one multiplier reproduces observed and holdout terms",
            Map.of("holdoutTerms", Integer.toString(input.holdout().size())));
    }

    public record Plan(int multiplier, Input input) {
        public String canonical() {
            return "multiplier=" + multiplier + ";" + input.canonical();
        }
    }

    public record Certificate(int multiplier, Input input) {
        public String canonical() {
            return "multiplier=" + multiplier + ";verified=" + input.canonical();
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

        boolean valid() {
            return observed.size() >= 2 && !holdout.isEmpty() && maxMultiplier >= 1;
        }

        String canonical() {
            return "observed=" + observed + ";holdout=" + holdout
                + ";maxMultiplier=" + maxMultiplier;
        }

        static Input parse(String payload) {
            Map<String, String> values = java.util.Arrays.stream(payload.split(";"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    parts -> parts[0].trim(),
                    parts -> parts[1].trim()));
            return new Input(
                csv(values.get("observed")),
                csv(values.get("holdout")),
                Integer.parseInt(values.getOrDefault("maxMultiplier", "8")));
        }

        private static List<Long> csv(String value) {
            return value == null || value.isBlank()
                ? List.of()
                : java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .map(Long::parseLong)
                    .toList();
        }
    }
}
