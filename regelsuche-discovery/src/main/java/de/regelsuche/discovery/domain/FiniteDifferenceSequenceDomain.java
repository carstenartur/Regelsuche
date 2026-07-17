package de.regelsuche.discovery.domain;

import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateContext;
import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateEvaluator;
import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateExtractor;
import de.regelsuche.discovery.domain.DiscoveryDomain.CanonicalCodec;
import de.regelsuche.discovery.domain.DiscoveryDomain.CertificateRenderer;
import de.regelsuche.discovery.domain.DiscoveryDomain.CertificateRendering;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleGenerator;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DiscoveryDomain.DomainPayload;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.EvidenceAdapter;
import de.regelsuche.discovery.domain.DiscoveryDomain.Invariant;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Objective;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.StateGenerator;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.domain.DiscoveryDomain.TransitionOperator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Small non-expression discovery domain for integer sequences.
 *
 * <p>The domain searches finite-difference rows until it finds a constant row,
 * constructs the corresponding polynomial-difference recurrence, checks the
 * observed prefix for counterexamples and validates against an independently
 * supplied holdout suffix. The retained certificate is a finite-difference
 * witness, not a formal proof or external novelty claim.</p>
 */
public final class FiniteDifferenceSequenceDomain implements DiscoveryDomain<
    FiniteDifferenceSequenceDomain.SequenceState,
    FiniteDifferenceSequenceDomain.FiniteDifferenceCandidate,
    FiniteDifferenceSequenceDomain.FiniteDifferenceCertificate
> {
    public static final String DOMAIN_ID = "integer-sequence-finite-difference";
    public static final String REVISION = "v1";

    private final StateGenerator<SequenceState> generator = new StateGenerator<>() {
        @Override
        public String id() {
            return "finite-difference-sequence-generator/v1";
        }

        @Override
        public List<SequenceState> generate(DiscoverySeed seed) {
            ParsedSequence parsed = parsePayload(seed.payload());
            return List.of(new SequenceState(
                parsed.observed(),
                parsed.holdout(),
                List.of(parsed.observed())));
        }
    };

    private final CanonicalCodec<SequenceState> stateCodec = new CanonicalCodec<>() {
        @Override
        public String id() {
            return "finite-difference-state-codec/v1";
        }

        @Override
        public String canonicalForm(SequenceState value) {
            return "observed=" + canonicalNumbers(value.observed())
                + "\nholdout=" + canonicalNumbers(value.holdout())
                + "\nrows=" + value.rows().stream()
                    .map(FiniteDifferenceSequenceDomain::canonicalNumbers)
                    .collect(java.util.stream.Collectors.joining("|"));
        }
    };

    private final List<Invariant<SequenceState>> invariants = List.of(
        new Invariant<>() {
            @Override
            public String id() {
                return "finite-difference-row-shape/v1";
            }

            @Override
            public InvariantResult check(SequenceState state) {
                if (state.rows().isEmpty()
                        || !state.rows().getFirst().equals(state.observed())) {
                    return InvariantResult.fail("first-row-does-not-match-observed");
                }
                for (int index = 1; index < state.rows().size(); index++) {
                    List<Long> previous = state.rows().get(index - 1);
                    List<Long> current = state.rows().get(index);
                    if (current.size() != previous.size() - 1) {
                        return InvariantResult.fail("row-length-does-not-decrease-by-one");
                    }
                    List<Long> expected;
                    try {
                        expected = difference(previous);
                    } catch (ArithmeticException exception) {
                        return InvariantResult.fail("difference-overflow");
                    }
                    if (!expected.equals(current)) {
                        return InvariantResult.fail("row-is-not-the-previous-finite-difference");
                    }
                }
                return InvariantResult.pass();
            }
        },
        new Invariant<>() {
            @Override
            public String id() {
                return "finite-difference-support/v1";
            }

            @Override
            public InvariantResult check(SequenceState state) {
                int order = state.rows().size() - 1;
                return state.observed().size() >= order + 2
                    ? InvariantResult.pass()
                    : InvariantResult.fail("insufficient-observed-support-for-order");
            }
        }
    );

    private final List<TransitionOperator<SequenceState>> operators = List.of(
        new TransitionOperator<>() {
            @Override
            public String id() {
                return "finite-difference-operator/v1";
            }

            @Override
            public List<Successor<SequenceState>> apply(SequenceState state) {
                List<Long> last = state.rows().getLast();
                if (last.size() < 2) {
                    return List.of();
                }
                List<Long> next;
                try {
                    next = difference(last);
                } catch (ArithmeticException exception) {
                    return List.of();
                }
                List<List<Long>> rows = new ArrayList<>(state.rows());
                rows.add(next);
                int order = rows.size() - 1;
                return List.of(new Successor<>(
                    "finite-difference-order-" + order,
                    new SequenceState(state.observed(), state.holdout(), rows),
                    1,
                    true,
                    List.of(),
                    Map.of(
                        "differenceOrder", Integer.toString(order),
                        "rowLength", Integer.toString(next.size()))));
            }
        }
    );

    private final Objective<SequenceState> objective = new Objective<>() {
        @Override
        public String id() {
            return "constant-finite-difference-objective/v1";
        }

        @Override
        public ObjectiveAssessment assess(SequenceState state) {
            int order = state.rows().size() - 1;
            List<Long> last = state.rows().getLast();
            boolean constant = order >= 1 && isConstant(last);
            int score = constant ? 1_000_000 - order * 1_000 : order * 100;
            LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
            metrics.put("differenceOrder", Integer.toString(order));
            metrics.put("lastRowLength", Integer.toString(last.size()));
            metrics.put("constantDifference", Boolean.toString(constant));
            if (constant) {
                metrics.put("constantValue", Long.toString(last.getFirst()));
            }
            return new ObjectiveAssessment(score, constant, metrics);
        }
    };

    private final CandidateExtractor<SequenceState, FiniteDifferenceCandidate> candidateExtractor =
        new CandidateExtractor<>() {
            @Override
            public String id() {
                return "finite-difference-candidate-extractor/v1";
            }

            @Override
            public FiniteDifferenceCandidate extract(CandidateContext<SequenceState> context) {
                SequenceState state = context.currentState();
                int order = state.rows().size() - 1;
                List<Long> initialDifferences = state.rows().stream()
                    .map(List::getFirst)
                    .toList();
                return new FiniteDifferenceCandidate(
                    order,
                    initialDifferences,
                    state.observed(),
                    state.holdout());
            }
        };

    private final CanonicalCodec<FiniteDifferenceCandidate> candidateCodec =
        new CanonicalCodec<>() {
            @Override
            public String id() {
                return "finite-difference-candidate-codec/v1";
            }

            @Override
            public String canonicalForm(FiniteDifferenceCandidate value) {
                return "order=" + value.order()
                    + "\ninitialDifferences=" + canonicalNumbers(value.initialDifferences())
                    + "\nobserved=" + canonicalNumbers(value.observed())
                    + "\nholdout=" + canonicalNumbers(value.holdout());
            }
        };

    private final CounterexampleGenerator<FiniteDifferenceCandidate> counterexampleGenerator =
        new CounterexampleGenerator<>() {
            @Override
            public String id() {
                return "finite-difference-prefix-counterexample-search/v1";
            }

            @Override
            public CounterexampleResult search(
                FiniteDifferenceCandidate candidate,
                int attemptBudget
            ) {
                List<Long> generated;
                try {
                    generated = generate(candidate.initialDifferences(),
                        candidate.observed().size());
                } catch (ArithmeticException exception) {
                    return CounterexampleResult.unsupported(
                        "integer overflow while replaying observed sequence");
                }
                int attempts = Math.min(attemptBudget, candidate.observed().size());
                for (int index = 0; index < attempts; index++) {
                    if (!generated.get(index).equals(candidate.observed().get(index))) {
                        return CounterexampleResult.found(
                            index + 1,
                            "observed index " + index + " expected "
                                + candidate.observed().get(index) + " but generated "
                                + generated.get(index),
                            Map.of(
                                "index", Integer.toString(index),
                                "expected", Long.toString(candidate.observed().get(index)),
                                "generated", Long.toString(generated.get(index))));
                    }
                }
                if (attempts < candidate.observed().size()) {
                    return CounterexampleResult.inconclusive(
                        attempts,
                        "prefix audit budget did not cover every observed term",
                        Map.of(
                            "checkedTerms", Integer.toString(attempts),
                            "requiredTerms", Integer.toString(candidate.observed().size())));
                }
                return CounterexampleResult.noneFound(
                    attempts,
                    Map.of(
                        "checkedTerms", Integer.toString(attempts),
                        "replay", "exact-integer"));
            }
        };

    private final CandidateEvaluator<FiniteDifferenceCandidate, FiniteDifferenceCertificate>
        evaluator = new CandidateEvaluator<>() {
            @Override
            public String id() {
                return "finite-difference-holdout-evaluator/v1";
            }

            @Override
            public Evaluation<FiniteDifferenceCertificate> evaluate(
                FiniteDifferenceCandidate candidate
            ) {
                int total = candidate.observed().size() + candidate.holdout().size();
                List<Long> generated;
                try {
                    generated = generate(candidate.initialDifferences(), total);
                } catch (ArithmeticException exception) {
                    return Evaluation.unsupported(
                        "integer overflow while generating holdout continuation",
                        Map.of());
                }
                List<Long> predictedHoldout = generated.subList(
                    candidate.observed().size(), total);
                if (!predictedHoldout.equals(candidate.holdout())) {
                    return Evaluation.refuted(
                        "finite-difference recurrence failed the retained holdout",
                        Map.of(
                            "expectedHoldout", canonicalNumbers(candidate.holdout()),
                            "predictedHoldout", canonicalNumbers(predictedHoldout)));
                }
                FiniteDifferenceCertificate certificate =
                    new FiniteDifferenceCertificate(
                        candidate.order(),
                        candidate.initialDifferences(),
                        generated,
                        candidate.observed().size(),
                        candidate.holdout().size(),
                        "FINITE_DIFFERENCE_VALIDATION_NOT_FORMAL_PROOF");
                return Evaluation.confirmed(
                    certificate,
                    "constant finite difference predicts every retained holdout term",
                    Map.of(
                        "differenceOrder", Integer.toString(candidate.order()),
                        "holdoutTerms", Integer.toString(candidate.holdout().size()),
                        "predictedHoldout", canonicalNumbers(predictedHoldout)));
            }
        };

    private final CanonicalCodec<FiniteDifferenceCertificate> certificateCodec =
        new CanonicalCodec<>() {
            @Override
            public String id() {
                return "finite-difference-certificate-codec/v1";
            }

            @Override
            public String canonicalForm(FiniteDifferenceCertificate value) {
                return "order=" + value.order()
                    + "\ninitialDifferences=" + canonicalNumbers(value.initialDifferences())
                    + "\ngeneratedTerms=" + canonicalNumbers(value.generatedTerms())
                    + "\nobservedCount=" + value.observedCount()
                    + "\nholdoutCount=" + value.holdoutCount()
                    + "\nstrength=" + value.evidenceStrength();
            }
        };

    private final CertificateRenderer<FiniteDifferenceCertificate> certificateRenderer =
        new CertificateRenderer<>() {
            @Override
            public String id() {
                return "finite-difference-certificate-renderer/v1";
            }

            @Override
            public CertificateRendering render(FiniteDifferenceCertificate certificate) {
                String rendered = "differenceOrder=" + certificate.order()
                    + "\ninitialDifferences="
                    + canonicalNumbers(certificate.initialDifferences())
                    + "\ngeneratedTerms="
                    + canonicalNumbers(certificate.generatedTerms())
                    + "\nobservedCount=" + certificate.observedCount()
                    + "\nholdoutCount=" + certificate.holdoutCount()
                    + "\nstrength=" + certificate.evidenceStrength();
                return new CertificateRendering(
                    "FINITE_DIFFERENCE_WITNESS",
                    "text/plain",
                    rendered);
            }
        };

    private final EvidenceAdapter<SequenceState, FiniteDifferenceCandidate, FiniteDifferenceCertificate>
        evidenceAdapter = new EvidenceAdapter<>() {
            @Override
            public String id() {
                return "finite-difference-evidence-adapter/v1";
            }

            @Override
            public DomainPayload adapt(
                Optional<SequenceState> initialState,
                Optional<FiniteDifferenceCandidate> candidate,
                Optional<FiniteDifferenceCertificate> certificate
            ) {
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                values.put("observedTerms", initialState
                    .map(state -> canonicalNumbers(state.observed()))
                    .orElse("NOT_AVAILABLE"));
                values.put("holdoutTerms", initialState
                    .map(state -> canonicalNumbers(state.holdout()))
                    .orElse("NOT_AVAILABLE"));
                values.put("candidateStatus", candidate.isPresent()
                    ? "SELECTED"
                    : "NOT_SELECTED");
                values.put("differenceOrder", candidate
                    .map(value -> Integer.toString(value.order()))
                    .orElse("NOT_EVALUATED"));
                values.put("initialDifferences", candidate
                    .map(value -> canonicalNumbers(value.initialDifferences()))
                    .orElse("NOT_EVALUATED"));
                values.put("certificateStrength", certificate
                    .map(FiniteDifferenceCertificate::evidenceStrength)
                    .orElse("NOT_EVALUATED"));
                values.put("formalProofStatus", "NOT_EVALUATED");
                values.put("externalNoveltyStatus", "NOT_EVALUATED");
                return new DomainPayload("finite-difference-sequence-evidence/v1", values);
            }
        };

    @Override
    public String domainId() {
        return DOMAIN_ID;
    }

    @Override
    public String revision() {
        return REVISION;
    }

    @Override
    public String stateType() {
        return "finite-difference-sequence-state/v1";
    }

    @Override
    public String candidateType() {
        return "finite-difference-recurrence-candidate/v1";
    }

    @Override
    public String certificateType() {
        return "finite-difference-certificate/v1";
    }

    @Override
    public StateGenerator<SequenceState> generator() {
        return generator;
    }

    @Override
    public CanonicalCodec<SequenceState> stateCodec() {
        return stateCodec;
    }

    @Override
    public List<Invariant<SequenceState>> invariants() {
        return invariants;
    }

    @Override
    public List<TransitionOperator<SequenceState>> operators() {
        return operators;
    }

    @Override
    public Objective<SequenceState> objective() {
        return objective;
    }

    @Override
    public CandidateExtractor<SequenceState, FiniteDifferenceCandidate> candidateExtractor() {
        return candidateExtractor;
    }

    @Override
    public CanonicalCodec<FiniteDifferenceCandidate> candidateCodec() {
        return candidateCodec;
    }

    @Override
    public CounterexampleGenerator<FiniteDifferenceCandidate> counterexampleGenerator() {
        return counterexampleGenerator;
    }

    @Override
    public CandidateEvaluator<FiniteDifferenceCandidate, FiniteDifferenceCertificate> evaluator() {
        return evaluator;
    }

    @Override
    public CanonicalCodec<FiniteDifferenceCertificate> certificateCodec() {
        return certificateCodec;
    }

    @Override
    public CertificateRenderer<FiniteDifferenceCertificate> certificateRenderer() {
        return certificateRenderer;
    }

    @Override
    public EvidenceAdapter<SequenceState, FiniteDifferenceCandidate, FiniteDifferenceCertificate>
            evidenceAdapter() {
        return evidenceAdapter;
    }

    private static ParsedSequence parsePayload(String payload) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String part : payload.split(";")) {
            int separator = part.indexOf('=');
            if (separator < 1 || separator == part.length() - 1) {
                throw new IllegalArgumentException(
                    "sequence payload entries must use key=value");
            }
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate sequence payload key: " + key);
            }
        }
        if (!values.keySet().equals(java.util.Set.of("observed", "holdout"))) {
            throw new IllegalArgumentException(
                "sequence payload requires exactly observed and holdout");
        }
        List<Long> observed = parseNumbers(values.get("observed"));
        List<Long> holdout = parseNumbers(values.get("holdout"));
        if (observed.size() < 3) {
            throw new IllegalArgumentException(
                "at least three observed sequence terms are required");
        }
        if (holdout.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one holdout sequence term is required");
        }
        return new ParsedSequence(observed, holdout);
    }

    private static List<Long> parseNumbers(String value) {
        List<Long> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("empty sequence term");
            }
            result.add(Long.parseLong(trimmed));
        }
        return List.copyOf(result);
    }

    private static List<Long> difference(List<Long> row) {
        List<Long> result = new ArrayList<>(Math.max(0, row.size() - 1));
        for (int index = 0; index + 1 < row.size(); index++) {
            result.add(Math.subtractExact(row.get(index + 1), row.get(index)));
        }
        return List.copyOf(result);
    }

    private static boolean isConstant(List<Long> row) {
        if (row.isEmpty()) {
            return false;
        }
        long first = row.getFirst();
        return row.stream().allMatch(value -> value == first);
    }

    private static List<Long> generate(List<Long> initialDifferences, int count) {
        if (count < 1) {
            return List.of();
        }
        List<Long> levels = new ArrayList<>(initialDifferences);
        List<Long> generated = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            generated.add(levels.getFirst());
            for (int level = 0; level + 1 < levels.size(); level++) {
                levels.set(level, Math.addExact(levels.get(level), levels.get(level + 1)));
            }
        }
        return List.copyOf(generated);
    }

    private static String canonicalNumbers(List<Long> values) {
        return values.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private record ParsedSequence(List<Long> observed, List<Long> holdout) {
        private ParsedSequence {
            observed = List.copyOf(observed);
            holdout = List.copyOf(holdout);
        }
    }

    public record SequenceState(
        List<Long> observed,
        List<Long> holdout,
        List<List<Long>> rows
    ) {
        public SequenceState {
            observed = immutableNumbers(observed, "observed");
            holdout = immutableNumbers(holdout, "holdout");
            Objects.requireNonNull(rows, "rows");
            rows = rows.stream()
                .map(row -> immutableNumbers(row, "difference row"))
                .toList();
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("rows must not be empty");
            }
        }
    }

    public record FiniteDifferenceCandidate(
        int order,
        List<Long> initialDifferences,
        List<Long> observed,
        List<Long> holdout
    ) {
        public FiniteDifferenceCandidate {
            if (order < 1) {
                throw new IllegalArgumentException("difference order must be positive");
            }
            initialDifferences = immutableNumbers(
                initialDifferences, "initialDifferences");
            observed = immutableNumbers(observed, "observed");
            holdout = immutableNumbers(holdout, "holdout");
            if (initialDifferences.size() != order + 1) {
                throw new IllegalArgumentException(
                    "initialDifferences must contain order+1 values");
            }
        }
    }

    public record FiniteDifferenceCertificate(
        int order,
        List<Long> initialDifferences,
        List<Long> generatedTerms,
        int observedCount,
        int holdoutCount,
        String evidenceStrength
    ) {
        public FiniteDifferenceCertificate {
            if (order < 1 || observedCount < 1 || holdoutCount < 1) {
                throw new IllegalArgumentException(
                    "certificate order and counts must be positive");
            }
            initialDifferences = immutableNumbers(
                initialDifferences, "initialDifferences");
            generatedTerms = immutableNumbers(
                generatedTerms, "generatedTerms");
            if (generatedTerms.size() != observedCount + holdoutCount) {
                throw new IllegalArgumentException(
                    "certificate generated term count is inconsistent");
            }
            DomainCanonical.requireIdentifier(evidenceStrength, "evidenceStrength");
        }
    }

    private static List<Long> immutableNumbers(List<Long> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
