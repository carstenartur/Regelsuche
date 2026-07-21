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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact bounded discovery domain for homogeneous linear recurrences with
 * constant rational coefficients.
 *
 * <p>The domain searches increasing recurrence orders and accepts only a
 * uniquely determined coefficient vector that replays every observed term.
 * It then evaluates the frozen holdout with exact rational arithmetic. A
 * retained certificate establishes a fit for the finite observed and holdout
 * data only; it is not a uniqueness theorem for an infinite continuation.</p>
 */
public final class LinearRecurrenceSequenceDomain implements DiscoveryDomain<
    LinearRecurrenceSequenceDomain.SequenceState,
    LinearRecurrenceSequenceDomain.LinearRecurrenceCandidate,
    LinearRecurrenceSequenceDomain.LinearRecurrenceCertificate
> {
    public static final String DOMAIN_ID = "integer-sequence-linear-recurrence";
    public static final String REVISION = "v1";

    private final StateGenerator<SequenceState> generator = new StateGenerator<>() {
        @Override
        public String id() {
            return "linear-recurrence-sequence-generator/v1";
        }

        @Override
        public List<SequenceState> generate(DiscoverySeed seed) {
            ParsedSequence parsed = parsePayload(seed.payload());
            return List.of(stateForOrder(parsed, 1));
        }
    };

    private final CanonicalCodec<SequenceState> stateCodec = new CanonicalCodec<>() {
        @Override
        public String id() {
            return "linear-recurrence-state-codec/v1";
        }

        @Override
        public String canonicalForm(SequenceState value) {
            return "observed=" + canonicalLongs(value.observed())
                + "\nholdout=" + canonicalLongs(value.holdout())
                + "\nmaximumOrder=" + value.maximumOrder()
                + "\ncurrentOrder=" + value.currentOrder()
                + "\nmodel=" + value.model()
                    .map(RecurrenceModel::canonicalForm)
                    .orElse("NO_UNIQUE_MODEL");
        }
    };

    private final List<Invariant<SequenceState>> invariants = List.of(
        new Invariant<>() {
            @Override
            public String id() {
                return "linear-recurrence-order-boundary/v1";
            }

            @Override
            public InvariantResult check(SequenceState state) {
                if (state.currentOrder() < 1
                        || state.currentOrder() > state.maximumOrder()) {
                    return InvariantResult.fail("current-order-outside-maximum-order");
                }
                return InvariantResult.pass();
            }
        },
        new Invariant<>() {
            @Override
            public String id() {
                return "linear-recurrence-model-replay/v1";
            }

            @Override
            public InvariantResult check(SequenceState state) {
                if (state.model().isEmpty()) {
                    return InvariantResult.pass();
                }
                RecurrenceModel model = state.model().orElseThrow();
                if (model.order() != state.currentOrder()) {
                    return InvariantResult.fail("model-order-does-not-match-state");
                }
                return replaysObserved(model, state.observed())
                    ? InvariantResult.pass()
                    : InvariantResult.fail("model-does-not-replay-observed-prefix");
            }
        }
    );

    private final List<TransitionOperator<SequenceState>> operators = List.of(
        new TransitionOperator<>() {
            @Override
            public String id() {
                return "increase-linear-recurrence-order/v1";
            }

            @Override
            public List<Successor<SequenceState>> apply(SequenceState state) {
                if (state.currentOrder() >= state.maximumOrder()) {
                    return List.of();
                }
                int nextOrder = state.currentOrder() + 1;
                ParsedSequence parsed = new ParsedSequence(
                    state.observed(), state.holdout(), state.maximumOrder());
                SequenceState next = stateForOrder(parsed, nextOrder);
                return List.of(new Successor<>(
                    "linear-recurrence-order-" + nextOrder,
                    next,
                    1,
                    true,
                    List.of(),
                    Map.of(
                        "recurrenceOrder", Integer.toString(nextOrder),
                        "uniqueModel", Boolean.toString(next.model().isPresent()))));
            }
        }
    );

    private final Objective<SequenceState> objective = new Objective<>() {
        @Override
        public String id() {
            return "unique-linear-recurrence-objective/v1";
        }

        @Override
        public ObjectiveAssessment assess(SequenceState state) {
            boolean ready = state.model().isPresent();
            int score = ready
                ? 1_000_000 - state.currentOrder() * 1_000
                : state.currentOrder() * 100;
            LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
            metrics.put("recurrenceOrder", Integer.toString(state.currentOrder()));
            metrics.put("maximumOrder", Integer.toString(state.maximumOrder()));
            metrics.put("uniqueModel", Boolean.toString(ready));
            state.model().ifPresent(model ->
                metrics.put("coefficients", canonicalCoefficients(model.coefficients())));
            return new ObjectiveAssessment(score, ready, metrics);
        }
    };

    private final CandidateExtractor<SequenceState, LinearRecurrenceCandidate>
        candidateExtractor = new CandidateExtractor<>() {
            @Override
            public String id() {
                return "linear-recurrence-candidate-extractor/v1";
            }

            @Override
            public LinearRecurrenceCandidate extract(CandidateContext<SequenceState> context) {
                SequenceState state = context.currentState();
                return new LinearRecurrenceCandidate(
                    state.model().orElseThrow(),
                    state.observed(),
                    state.holdout());
            }
        };

    private final CanonicalCodec<LinearRecurrenceCandidate> candidateCodec =
        new CanonicalCodec<>() {
            @Override
            public String id() {
                return "linear-recurrence-candidate-codec/v1";
            }

            @Override
            public String canonicalForm(LinearRecurrenceCandidate value) {
                return value.model().canonicalForm()
                    + "\nobserved=" + canonicalLongs(value.observed())
                    + "\nholdout=" + canonicalLongs(value.holdout());
            }
        };

    private final CounterexampleGenerator<LinearRecurrenceCandidate>
        counterexampleGenerator = new CounterexampleGenerator<>() {
            @Override
            public String id() {
                return "linear-recurrence-prefix-counterexample-search/v1";
            }

            @Override
            public CounterexampleResult search(
                LinearRecurrenceCandidate candidate,
                int attemptBudget
            ) {
                List<Rational> generated = generate(
                    candidate.model(),
                    candidate.observed(),
                    candidate.observed().size());
                int attempts = Math.min(attemptBudget, candidate.observed().size());
                for (int index = 0; index < attempts; index++) {
                    Rational expected = Rational.of(candidate.observed().get(index));
                    if (!generated.get(index).equals(expected)) {
                        return CounterexampleResult.found(
                            index + 1,
                            "observed index " + index + " expected " + expected.canonical()
                                + " but generated " + generated.get(index).canonical(),
                            Map.of(
                                "index", Integer.toString(index),
                                "expected", expected.canonical(),
                                "generated", generated.get(index).canonical()));
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
                        "replay", "exact-rational"));
            }
        };

    private final CandidateEvaluator<LinearRecurrenceCandidate, LinearRecurrenceCertificate>
        evaluator = new CandidateEvaluator<>() {
            @Override
            public String id() {
                return "linear-recurrence-holdout-evaluator/v1";
            }

            @Override
            public Evaluation<LinearRecurrenceCertificate> evaluate(
                LinearRecurrenceCandidate candidate
            ) {
                int total = candidate.observed().size() + candidate.holdout().size();
                List<Rational> generated = generate(
                    candidate.model(), candidate.observed(), total);
                List<Rational> predictedHoldout = generated.subList(
                    candidate.observed().size(), total);
                List<Rational> expectedHoldout = candidate.holdout().stream()
                    .map(Rational::of)
                    .toList();
                if (!predictedHoldout.equals(expectedHoldout)) {
                    return Evaluation.refuted(
                        "linear recurrence failed the retained holdout",
                        Map.of(
                            "expectedHoldout", canonicalRationals(expectedHoldout),
                            "predictedHoldout", canonicalRationals(predictedHoldout)));
                }
                LinearRecurrenceCertificate certificate =
                    new LinearRecurrenceCertificate(
                        candidate.model(),
                        generated.stream().map(Rational::canonical).toList(),
                        candidate.observed().size(),
                        candidate.holdout().size(),
                        "LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF");
                return Evaluation.confirmed(
                    certificate,
                    "constant-coefficient recurrence predicts every retained holdout term",
                    Map.of(
                        "recurrenceOrder", Integer.toString(candidate.model().order()),
                        "coefficients", canonicalCoefficients(
                            candidate.model().coefficients()),
                        "predictedHoldout", canonicalRationals(predictedHoldout)));
            }
        };

    private final CanonicalCodec<LinearRecurrenceCertificate> certificateCodec =
        new CanonicalCodec<>() {
            @Override
            public String id() {
                return "linear-recurrence-certificate-codec/v1";
            }

            @Override
            public String canonicalForm(LinearRecurrenceCertificate value) {
                return value.model().canonicalForm()
                    + "\ngeneratedTerms=" + canonicalStrings(value.generatedTerms())
                    + "\nobservedCount=" + value.observedCount()
                    + "\nholdoutCount=" + value.holdoutCount()
                    + "\nstrength=" + value.evidenceStrength();
            }
        };

    private final CertificateRenderer<LinearRecurrenceCertificate> certificateRenderer =
        new CertificateRenderer<>() {
            @Override
            public String id() {
                return "linear-recurrence-certificate-renderer/v1";
            }

            @Override
            public CertificateRendering render(LinearRecurrenceCertificate certificate) {
                String rendered = certificate.model().canonicalForm()
                    + "\ngeneratedTerms=" + canonicalStrings(certificate.generatedTerms())
                    + "\nobservedCount=" + certificate.observedCount()
                    + "\nholdoutCount=" + certificate.holdoutCount()
                    + "\nstrength=" + certificate.evidenceStrength();
                return new CertificateRendering(
                    "LINEAR_RECURRENCE_WITNESS",
                    "text/plain",
                    rendered);
            }
        };

    private final EvidenceAdapter<SequenceState, LinearRecurrenceCandidate,
        LinearRecurrenceCertificate> evidenceAdapter = new EvidenceAdapter<>() {
            @Override
            public String id() {
                return "linear-recurrence-evidence-adapter/v1";
            }

            @Override
            public DomainPayload adapt(
                Optional<SequenceState> initialState,
                Optional<LinearRecurrenceCandidate> candidate,
                Optional<LinearRecurrenceCertificate> certificate
            ) {
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                values.put("observedTerms", initialState
                    .map(state -> canonicalLongs(state.observed()))
                    .orElse("NOT_AVAILABLE"));
                values.put("holdoutTerms", initialState
                    .map(state -> canonicalLongs(state.holdout()))
                    .orElse("NOT_AVAILABLE"));
                values.put("candidateStatus", candidate.isPresent()
                    ? "SELECTED" : "NOT_SELECTED");
                values.put("recurrenceOrder", candidate
                    .map(value -> Integer.toString(value.model().order()))
                    .orElse("NOT_EVALUATED"));
                values.put("coefficients", candidate
                    .map(value -> canonicalCoefficients(value.model().coefficients()))
                    .orElse("NOT_EVALUATED"));
                values.put("certificateStrength", certificate
                    .map(LinearRecurrenceCertificate::evidenceStrength)
                    .orElse("NOT_EVALUATED"));
                values.put("formalProofStatus", "NOT_EVALUATED");
                values.put("externalNoveltyStatus", "NOT_EVALUATED");
                return new DomainPayload("linear-recurrence-sequence-evidence/v1", values);
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
        return "linear-recurrence-sequence-state/v1";
    }

    @Override
    public String candidateType() {
        return "linear-recurrence-candidate/v1";
    }

    @Override
    public String certificateType() {
        return "linear-recurrence-certificate/v1";
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
    public CandidateExtractor<SequenceState, LinearRecurrenceCandidate> candidateExtractor() {
        return candidateExtractor;
    }

    @Override
    public CanonicalCodec<LinearRecurrenceCandidate> candidateCodec() {
        return candidateCodec;
    }

    @Override
    public CounterexampleGenerator<LinearRecurrenceCandidate> counterexampleGenerator() {
        return counterexampleGenerator;
    }

    @Override
    public CandidateEvaluator<LinearRecurrenceCandidate, LinearRecurrenceCertificate> evaluator() {
        return evaluator;
    }

    @Override
    public CanonicalCodec<LinearRecurrenceCertificate> certificateCodec() {
        return certificateCodec;
    }

    @Override
    public CertificateRenderer<LinearRecurrenceCertificate> certificateRenderer() {
        return certificateRenderer;
    }

    @Override
    public EvidenceAdapter<SequenceState, LinearRecurrenceCandidate,
            LinearRecurrenceCertificate> evidenceAdapter() {
        return evidenceAdapter;
    }

    /** Infer the lowest-order uniquely determined recurrence within the bound. */
    public static Optional<RecurrenceModel> inferUniqueRecurrence(
        List<Long> observed,
        int maximumOrder
    ) {
        List<Long> immutable = immutableLongs(observed, "observed");
        if (maximumOrder < 1 || maximumOrder > 8) {
            throw new IllegalArgumentException("maximumOrder must be between 1 and 8");
        }
        for (int order = 1; order <= maximumOrder; order++) {
            Optional<RecurrenceModel> model = inferForOrder(immutable, order);
            if (model.isPresent()) {
                return model;
            }
        }
        return Optional.empty();
    }

    private static SequenceState stateForOrder(ParsedSequence parsed, int order) {
        return new SequenceState(
            parsed.observed(),
            parsed.holdout(),
            parsed.maximumOrder(),
            order,
            inferForOrder(parsed.observed(), order));
    }

    private static Optional<RecurrenceModel> inferForOrder(
        List<Long> observed,
        int order
    ) {
        if (order < 1 || observed.size() < 2 * order) {
            return Optional.empty();
        }
        int equations = observed.size() - order;
        Rational[][] matrix = new Rational[equations][order + 1];
        for (int row = 0; row < equations; row++) {
            int sequenceIndex = order + row;
            for (int column = 0; column < order; column++) {
                matrix[row][column] = Rational.of(
                    observed.get(sequenceIndex - column - 1));
            }
            matrix[row][order] = Rational.of(observed.get(sequenceIndex));
        }
        Optional<List<Rational>> solution = solveUnique(matrix, order);
        if (solution.isEmpty()) {
            return Optional.empty();
        }
        RecurrenceModel model = new RecurrenceModel(order, solution.orElseThrow());
        return replaysObserved(model, observed) ? Optional.of(model) : Optional.empty();
    }

    private static Optional<List<Rational>> solveUnique(
        Rational[][] source,
        int variableCount
    ) {
        Rational[][] matrix = copyMatrix(source);
        int rowCount = matrix.length;
        int pivotRow = 0;
        int[] pivotRows = new int[variableCount];
        java.util.Arrays.fill(pivotRows, -1);
        for (int column = 0; column < variableCount && pivotRow < rowCount; column++) {
            int selected = pivotRow;
            while (selected < rowCount && matrix[selected][column].isZero()) {
                selected++;
            }
            if (selected == rowCount) {
                continue;
            }
            Rational[] swap = matrix[pivotRow];
            matrix[pivotRow] = matrix[selected];
            matrix[selected] = swap;
            Rational pivot = matrix[pivotRow][column];
            for (int item = column; item <= variableCount; item++) {
                matrix[pivotRow][item] = matrix[pivotRow][item].divide(pivot);
            }
            for (int row = 0; row < rowCount; row++) {
                if (row == pivotRow || matrix[row][column].isZero()) {
                    continue;
                }
                Rational factor = matrix[row][column];
                for (int item = column; item <= variableCount; item++) {
                    matrix[row][item] = matrix[row][item].subtract(
                        factor.multiply(matrix[pivotRow][item]));
                }
            }
            pivotRows[column] = pivotRow;
            pivotRow++;
        }
        for (Rational[] row : matrix) {
            boolean allZero = true;
            for (int column = 0; column < variableCount; column++) {
                allZero &= row[column].isZero();
            }
            if (allZero && !row[variableCount].isZero()) {
                return Optional.empty();
            }
        }
        for (int row : pivotRows) {
            if (row < 0) {
                return Optional.empty();
            }
        }
        List<Rational> solution = new ArrayList<>(variableCount);
        for (int column = 0; column < variableCount; column++) {
            solution.add(matrix[pivotRows[column]][variableCount]);
        }
        return Optional.of(List.copyOf(solution));
    }

    private static Rational[][] copyMatrix(Rational[][] source) {
        Rational[][] result = new Rational[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }

    private static boolean replaysObserved(RecurrenceModel model, List<Long> observed) {
        List<Rational> generated = generate(model, observed, observed.size());
        for (int index = 0; index < observed.size(); index++) {
            if (!generated.get(index).equals(Rational.of(observed.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private static List<Rational> generate(
        RecurrenceModel model,
        List<Long> observed,
        int count
    ) {
        if (count < model.order()) {
            throw new IllegalArgumentException("count must include the recurrence seed terms");
        }
        List<Rational> generated = new ArrayList<>(count);
        for (int index = 0; index < model.order(); index++) {
            generated.add(Rational.of(observed.get(index)));
        }
        while (generated.size() < count) {
            int index = generated.size();
            Rational next = Rational.ZERO;
            for (int coefficient = 0; coefficient < model.order(); coefficient++) {
                next = next.add(model.coefficients().get(coefficient).multiply(
                    generated.get(index - coefficient - 1)));
            }
            generated.add(next);
        }
        return List.copyOf(generated);
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
        if (!values.keySet().equals(java.util.Set.of(
                "observed", "holdout", "maximumOrder"))) {
            throw new IllegalArgumentException(
                "sequence payload requires exactly observed, holdout and maximumOrder");
        }
        List<Long> observed = parseLongs(values.get("observed"));
        List<Long> holdout = parseLongs(values.get("holdout"));
        int maximumOrder = Integer.parseInt(values.get("maximumOrder"));
        if (observed.size() < 3) {
            throw new IllegalArgumentException(
                "at least three observed sequence terms are required");
        }
        if (holdout.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one holdout sequence term is required");
        }
        if (maximumOrder < 1 || maximumOrder > 8) {
            throw new IllegalArgumentException("maximumOrder must be between 1 and 8");
        }
        return new ParsedSequence(observed, holdout, maximumOrder);
    }

    private static List<Long> parseLongs(String value) {
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

    private static String canonicalCoefficients(List<Rational> values) {
        return values.stream()
            .map(Rational::canonical)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String canonicalRationals(List<Rational> values) {
        return values.stream()
            .map(Rational::canonical)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String canonicalLongs(List<Long> values) {
        return values.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String canonicalStrings(List<String> values) {
        return values.stream()
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private record ParsedSequence(
        List<Long> observed,
        List<Long> holdout,
        int maximumOrder
    ) {
        private ParsedSequence {
            observed = List.copyOf(observed);
            holdout = List.copyOf(holdout);
        }
    }

    public record SequenceState(
        List<Long> observed,
        List<Long> holdout,
        int maximumOrder,
        int currentOrder,
        Optional<RecurrenceModel> model
    ) {
        public SequenceState {
            observed = immutableLongs(observed, "observed");
            holdout = immutableLongs(holdout, "holdout");
            if (maximumOrder < 1 || maximumOrder > 8) {
                throw new IllegalArgumentException("maximumOrder must be between 1 and 8");
            }
            if (currentOrder < 1 || currentOrder > maximumOrder) {
                throw new IllegalArgumentException("currentOrder is outside maximumOrder");
            }
            model = model == null ? Optional.empty() : model;
        }
    }

    public record LinearRecurrenceCandidate(
        RecurrenceModel model,
        List<Long> observed,
        List<Long> holdout
    ) {
        public LinearRecurrenceCandidate {
            Objects.requireNonNull(model, "model");
            observed = immutableLongs(observed, "observed");
            holdout = immutableLongs(holdout, "holdout");
            if (observed.size() < model.order()) {
                throw new IllegalArgumentException(
                    "observed terms must contain the recurrence seed terms");
            }
        }
    }

    public record LinearRecurrenceCertificate(
        RecurrenceModel model,
        List<String> generatedTerms,
        int observedCount,
        int holdoutCount,
        String evidenceStrength
    ) {
        public LinearRecurrenceCertificate {
            Objects.requireNonNull(model, "model");
            generatedTerms = immutableStrings(generatedTerms, "generatedTerms");
            if (observedCount < model.order() || holdoutCount < 1) {
                throw new IllegalArgumentException(
                    "certificate counts are inconsistent with the recurrence order");
            }
            if (generatedTerms.size() != observedCount + holdoutCount) {
                throw new IllegalArgumentException(
                    "certificate generated term count is inconsistent");
            }
            DomainCanonical.requireIdentifier(evidenceStrength, "evidenceStrength");
        }
    }

    public record RecurrenceModel(int order, List<Rational> coefficients) {
        public RecurrenceModel {
            if (order < 1 || order > 8) {
                throw new IllegalArgumentException("recurrence order must be between 1 and 8");
            }
            Objects.requireNonNull(coefficients, "coefficients");
            coefficients = List.copyOf(coefficients);
            if (coefficients.size() != order || coefficients.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                    "coefficient count must equal recurrence order");
            }
        }

        public Rational predictNext(List<Long> observed) {
            List<Long> immutable = immutableLongs(observed, "observed");
            if (immutable.size() < order) {
                throw new IllegalArgumentException(
                    "observed terms must contain the recurrence seed terms");
            }
            List<Rational> generated = generate(this, immutable, immutable.size() + 1);
            return generated.getLast();
        }

        public String canonicalForm() {
            return "order=" + order
                + "\ncoefficients=" + canonicalCoefficients(coefficients);
        }
    }

    public record Rational(BigInteger numerator, BigInteger denominator) {
        private static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

        public Rational {
            Objects.requireNonNull(numerator, "numerator");
            Objects.requireNonNull(denominator, "denominator");
            if (denominator.signum() == 0) {
                throw new IllegalArgumentException("rational denominator must be non-zero");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }

        public static Rational of(long value) {
            return new Rational(BigInteger.valueOf(value), BigInteger.ONE);
        }

        public Rational add(Rational other) {
            return new Rational(
                numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
        }

        public Rational subtract(Rational other) {
            return new Rational(
                numerator.multiply(other.denominator)
                    .subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
        }

        public Rational multiply(Rational other) {
            return new Rational(
                numerator.multiply(other.numerator),
                denominator.multiply(other.denominator));
        }

        public Rational divide(Rational other) {
            if (other.isZero()) {
                throw new ArithmeticException("division by zero rational");
            }
            return new Rational(
                numerator.multiply(other.denominator),
                denominator.multiply(other.numerator));
        }

        public boolean isZero() {
            return numerator.signum() == 0;
        }

        public boolean isInteger() {
            return denominator.equals(BigInteger.ONE);
        }

        public long longValueExact() {
            if (!isInteger()) {
                throw new ArithmeticException("rational is not an integer: " + canonical());
            }
            return numerator.longValueExact();
        }

        public String canonical() {
            return denominator.equals(BigInteger.ONE)
                ? numerator.toString()
                : numerator + "/" + denominator;
        }
    }

    private static List<Long> immutableLongs(List<Long> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return List.copyOf(values);
    }

    private static List<String> immutableStrings(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (String value : values) {
            DomainCanonical.requireText(value, name + " item");
        }
        return List.copyOf(values);
    }
}
