package org.primachsenraum.regelsuche;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.sdk.discovery.DiscoveryDomainBuilder;
import de.regelsuche.sdk.discovery.DiscoveryDomainProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * External number-theory domain used to verify the Regelsuche student SDK.
 *
 * <p>The domain starts with Miller-Rabin base 2 and searches bounded ordered
 * base sets. Candidate correctness is exhaustively evaluated only on the
 * declared finite interval; no unbounded primality claim is made.</p>
 */
public final class BoundedMillerRabinPlanProvider
        implements DiscoveryDomainProvider {
    public static final String DOMAIN_ID = "primachsenraum-bounded-mr-plan";
    public static final String REVISION = "v1";
    public static final int DEFAULT_LIMIT = 100_000;
    private static final List<Integer> ALLOWED_BASES =
        List.of(2, 3, 5, 7, 11, 13, 17);

    @Override
    public String id() {
        return "primachsenraum-number-theory-provider";
    }

    @Override
    public String provenance() {
        return "carstenartur/primachsenraum";
    }

    @Override
    public Collection<DiscoveryDomain<?, ?, ?>> domains() {
        return List.of(domain());
    }

    public static DiscoveryDomain<PlanState, PlanCandidate, PlanCertificate> domain() {
        return DiscoveryDomainBuilder
            .<PlanState, PlanCandidate, PlanCertificate>domain(DOMAIN_ID, REVISION)
            .generator(seed -> {
                Input input = Input.parse(seed.payload());
                return List.of(new PlanState(List.of(2), input));
            })
            .stateCodec(PlanState::canonical)
            .invariant("valid-plan", state -> valid(state)
                ? InvariantResult.pass()
                : InvariantResult.fail("invalid-bounded-witness-plan"))
            .operator("add-base", BoundedMillerRabinPlanProvider::successors)
            .objective(state -> new ObjectiveAssessment(
                1_000_000
                    - state.bases().size() * 10_000
                    - state.bases().stream().mapToInt(Integer::intValue).sum(),
                true,
                Map.of(
                    "baseCount", Integer.toString(state.bases().size()),
                    "bases", state.bases().toString(),
                    "limit", Integer.toString(state.input().limit())
                )
            ))
            .candidate(
                context -> new PlanCandidate(
                    context.currentState().bases(),
                    context.currentState().input().limit()
                ),
                PlanCandidate::canonical
            )
            .counterexamples(BoundedMillerRabinPlanProvider::findCounterexample)
            .evaluator(BoundedMillerRabinPlanProvider::evaluateExhaustively)
            .certificate(
                "BOUNDED_MILLER_RABIN_PLAN_WITNESS",
                PlanCertificate::canonical,
                PlanCertificate::canonical
            )
            .build();
    }

    private static boolean valid(PlanState state) {
        if (state.input().limit() < 3 || state.bases().isEmpty()) {
            return false;
        }
        int previous = 1;
        for (int base : state.bases()) {
            if (!ALLOWED_BASES.contains(base) || base <= previous) {
                return false;
            }
            previous = base;
        }
        return state.bases().getFirst() == 2;
    }

    private static List<Successor<PlanState>> successors(PlanState state) {
        if (state.bases().size() >= state.input().maxBases()) {
            return List.of();
        }
        int last = state.bases().getLast();
        List<Successor<PlanState>> result = new ArrayList<>();
        for (int base : ALLOWED_BASES) {
            if (base <= last) {
                continue;
            }
            List<Integer> extended = new ArrayList<>(state.bases());
            extended.add(base);
            result.add(new Successor<>(
                "append-base-" + base,
                new PlanState(extended, state.input()),
                1,
                false,
                List.of(),
                Map.of("appendedBase", Integer.toString(base))
            ));
        }
        return List.copyOf(result);
    }

    private static CounterexampleResult findCounterexample(
            PlanCandidate candidate,
            int attemptBudget
    ) {
        int attempts = 0;
        for (int value = 3; value <= candidate.limit(); value += 2) {
            if (isPrimeByTrialDivision(value)) {
                continue;
            }
            if (attempts >= attemptBudget) {
                return CounterexampleResult.inconclusive(
                    attempts,
                    "counterexample budget did not cover the finite interval",
                    Map.of("lastChecked", Integer.toString(value - 2))
                );
            }
            attempts++;
            if (passesAllBases(value, candidate.bases())) {
                return CounterexampleResult.found(
                    attempts,
                    "composite " + value + " passes bases " + candidate.bases(),
                    Map.of(
                        "composite", Integer.toString(value),
                        "bases", candidate.bases().toString()
                    )
                );
            }
        }
        return CounterexampleResult.noneFound(
            attempts,
            Map.of(
                "checkedCompositeValues", Integer.toString(attempts),
                "limit", Integer.toString(candidate.limit())
            )
        );
    }

    private static Evaluation<PlanCertificate> evaluateExhaustively(
            PlanCandidate candidate
    ) {
        boolean[] prime = sieve(candidate.limit());
        int oddComposites = 0;
        int rejectedComposites = 0;
        int falsePrimes = 0;
        int falseCompositeDecisions = 0;
        for (int value = 3; value <= candidate.limit(); value += 2) {
            boolean accepted = passesAllBases(value, candidate.bases());
            if (prime[value]) {
                if (!accepted) {
                    falseCompositeDecisions++;
                }
            } else {
                oddComposites++;
                if (accepted) {
                    falsePrimes++;
                } else {
                    rejectedComposites++;
                }
            }
        }
        if (falsePrimes != 0 || falseCompositeDecisions != 0) {
            return Evaluation.refuted(
                "plan is not exact on the declared finite interval",
                Map.of(
                    "falsePrimes", Integer.toString(falsePrimes),
                    "falseCompositeDecisions",
                    Integer.toString(falseCompositeDecisions)
                )
            );
        }
        PlanCertificate certificate = new PlanCertificate(
            candidate.bases(),
            candidate.limit(),
            oddComposites,
            rejectedComposites,
            falsePrimes,
            falseCompositeDecisions,
            "EXHAUSTIVE_FINITE_RANGE_VALIDATION_NOT_UNBOUNDED_PROOF"
        );
        return Evaluation.confirmed(
            certificate,
            "every odd value in the finite interval matches the sieve truth",
            Map.of(
                "oddComposites", Integer.toString(oddComposites),
                "rejectedComposites", Integer.toString(rejectedComposites)
            )
        );
    }

    static boolean passesAllBases(int value, List<Integer> bases) {
        for (int base : bases) {
            if (!strongProbablePrime(value, base)) {
                return false;
            }
        }
        return true;
    }

    static boolean strongProbablePrime(int value, int base) {
        if (value == 2 || value == 3) {
            return true;
        }
        if (value < 2 || (value & 1) == 0) {
            return false;
        }
        if (value == base) {
            return true;
        }
        int d = value - 1;
        int s = Integer.numberOfTrailingZeros(d);
        d >>= s;
        long x = modularPow(Math.floorMod(base, value), d, value);
        if (x == 1 || x == value - 1L) {
            return true;
        }
        for (int round = 1; round < s; round++) {
            x = x * x % value;
            if (x == value - 1L) {
                return true;
            }
        }
        return false;
    }

    private static long modularPow(long base, int exponent, int modulus) {
        long result = 1;
        long power = base;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                result = result * power % modulus;
            }
            power = power * power % modulus;
            remaining >>>= 1;
        }
        return result;
    }

    private static boolean isPrimeByTrialDivision(int value) {
        if (value < 2) {
            return false;
        }
        if ((value & 1) == 0) {
            return value == 2;
        }
        for (int divisor = 3; (long) divisor * divisor <= value; divisor += 2) {
            if (value % divisor == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean[] sieve(int limit) {
        boolean[] prime = new boolean[limit + 1];
        java.util.Arrays.fill(prime, true);
        prime[0] = false;
        if (limit >= 1) {
            prime[1] = false;
        }
        for (int p = 2; (long) p * p <= limit; p++) {
            if (!prime[p]) {
                continue;
            }
            for (int multiple = p * p; multiple <= limit; multiple += p) {
                prime[multiple] = false;
            }
        }
        return prime;
    }

    public record Input(int limit, int maxBases) {
        static Input parse(String payload) {
            Map<String, String> values = java.util.Arrays.stream(payload.split(";"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    parts -> parts[0].trim(),
                    parts -> parts[1].trim()
                ));
            return new Input(
                Integer.parseInt(values.getOrDefault(
                    "limit",
                    Integer.toString(DEFAULT_LIMIT)
                )),
                Integer.parseInt(values.getOrDefault("maxBases", "3"))
            );
        }

        String canonical() {
            return "limit=" + limit + ";maxBases=" + maxBases;
        }
    }

    public record PlanState(List<Integer> bases, Input input) {
        public PlanState {
            bases = List.copyOf(bases);
        }

        String canonical() {
            return "bases=" + bases + ";" + input.canonical();
        }
    }

    public record PlanCandidate(List<Integer> bases, int limit) {
        public PlanCandidate {
            bases = List.copyOf(bases);
        }

        String canonical() {
            return "bases=" + bases + ";limit=" + limit;
        }
    }

    public record PlanCertificate(
        List<Integer> bases,
        int limit,
        int oddComposites,
        int rejectedComposites,
        int falsePrimes,
        int falseCompositeDecisions,
        String strength
    ) {
        public PlanCertificate {
            bases = List.copyOf(bases);
        }

        String canonical() {
            return "bases=" + bases
                + ";limit=" + limit
                + ";oddComposites=" + oddComposites
                + ";rejectedComposites=" + rejectedComposites
                + ";falsePrimes=" + falsePrimes
                + ";falseCompositeDecisions=" + falseCompositeDecisions
                + ";strength=" + strength;
        }
    }
}
