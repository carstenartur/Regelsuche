package de.regelsuche.discovery.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain-neutral contract for one bounded mathematical discovery domain.
 *
 * <p>The contract intentionally separates generation, canonical identity,
 * invariants, search operators, objectives, candidate construction,
 * counterexample search, validation, certificate rendering and evidence
 * adaptation. A runner may orchestrate these roles, but no single role is
 * allowed to stand in for mathematical proof, novelty or promotion.</p>
 */
public interface DiscoveryDomain<S, C, K> {
    String domainId();

    String revision();

    String stateType();

    String candidateType();

    String certificateType();

    StateGenerator<S> generator();

    CanonicalCodec<S> stateCodec();

    List<Invariant<S>> invariants();

    List<TransitionOperator<S>> operators();

    Objective<S> objective();

    CandidateExtractor<S, C> candidateExtractor();

    CanonicalCodec<C> candidateCodec();

    CounterexampleGenerator<C> counterexampleGenerator();

    CandidateEvaluator<C, K> evaluator();

    CanonicalCodec<K> certificateCodec();

    CertificateRenderer<K> certificateRenderer();

    EvidenceAdapter<S, C, K> evidenceAdapter();

    default DiscoveryDomainDescriptor descriptor() {
        return DiscoveryDomainDescriptor.from(this);
    }

    interface IdentifiedComponent {
        String id();
    }

    interface StateGenerator<S> extends IdentifiedComponent {
        List<S> generate(DiscoverySeed seed);
    }

    interface CanonicalCodec<T> extends IdentifiedComponent {
        String canonicalForm(T value);

        default String contentHash(T value) {
            return DomainCanonical.sha256(canonicalForm(Objects.requireNonNull(value, "value")));
        }
    }

    interface Invariant<S> extends IdentifiedComponent {
        InvariantResult check(S state);
    }

    interface TransitionOperator<S> extends IdentifiedComponent {
        List<Successor<S>> apply(S state);
    }

    interface Objective<S> extends IdentifiedComponent {
        ObjectiveAssessment assess(S state);
    }

    interface CandidateExtractor<S, C> extends IdentifiedComponent {
        C extract(CandidateContext<S> context);
    }

    interface CounterexampleGenerator<C> extends IdentifiedComponent {
        CounterexampleResult search(C candidate, int attemptBudget);
    }

    interface CandidateEvaluator<C, K> extends IdentifiedComponent {
        Evaluation<K> evaluate(C candidate);
    }

    interface CertificateRenderer<K> extends IdentifiedComponent {
        CertificateRendering render(K certificate);
    }

    interface EvidenceAdapter<S, C, K> extends IdentifiedComponent {
        DomainPayload adapt(Optional<S> initialState, Optional<C> candidate, Optional<K> certificate);
    }

    record DiscoverySeed(
        String schema,
        String seedId,
        String domainId,
        String payload,
        String sourceReference,
        String contentHash
    ) {
        public static final String SCHEMA = "regelsuche.discovery-seed/v1";

        public DiscoverySeed {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported discovery seed schema");
            }
            DomainCanonical.requireIdentifier(seedId, "seedId");
            DomainCanonical.requireIdentifier(domainId, "domainId");
            DomainCanonical.requireText(payload, "payload");
            DomainCanonical.requireText(sourceReference, "sourceReference");
            DomainCanonical.requireSha256(contentHash, "contentHash");
            String expected = DomainCanonical.sha256(canonicalMaterial(
                seedId, domainId, payload, sourceReference));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException("discovery seed contentHash mismatch");
            }
        }

        public static DiscoverySeed create(
            String seedId,
            String domainId,
            String payload,
            String sourceReference
        ) {
            String hash = DomainCanonical.sha256(canonicalMaterial(
                seedId, domainId, payload, sourceReference));
            return new DiscoverySeed(
                SCHEMA, seedId, domainId, payload, sourceReference, hash);
        }

        private static String canonicalMaterial(
            String seedId,
            String domainId,
            String payload,
            String sourceReference
        ) {
            return DomainCanonical.canonicalMap(Map.of(
                "schema", SCHEMA,
                "seedId", DomainCanonical.requireIdentifier(seedId, "seedId"),
                "domainId", DomainCanonical.requireIdentifier(domainId, "domainId"),
                "payload", DomainCanonical.requireText(payload, "payload"),
                "sourceReference",
                    DomainCanonical.requireText(sourceReference, "sourceReference")
            ));
        }
    }

    record DiscoveryBudget(
        int maxDepth,
        int maxExploredStates,
        int maxGeneratedSuccessors,
        int maxCandidatesPerState,
        int maxCandidateAttempts,
        int maxCounterexampleAttempts
    ) {
        public DiscoveryBudget {
            if (maxDepth < 0) {
                throw new IllegalArgumentException("maxDepth must be non-negative");
            }
            if (maxExploredStates < 1
                    || maxGeneratedSuccessors < 1
                    || maxCandidatesPerState < 1
                    || maxCandidateAttempts < 1
                    || maxCounterexampleAttempts < 1) {
                throw new IllegalArgumentException("discovery budgets must be positive");
            }
        }
    }

    record InvariantResult(boolean accepted, List<String> blockers) {
        public InvariantResult {
            blockers = DomainCanonical.sortedDistinct(blockers);
            if (accepted && !blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "accepted invariant result must not contain blockers");
            }
            if (!accepted && blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "rejected invariant result must contain a blocker");
            }
        }

        public static InvariantResult pass() {
            return new InvariantResult(true, List.of());
        }

        public static InvariantResult fail(String blocker) {
            return new InvariantResult(false, List.of(blocker));
        }
    }

    record Successor<S>(
        String actionId,
        S state,
        int cost,
        boolean semanticsPreserving,
        List<String> assumptions,
        Map<String, String> metadata
    ) {
        public Successor {
            DomainCanonical.requireIdentifier(actionId, "actionId");
            Objects.requireNonNull(state, "state");
            if (cost < -1_000_000 || cost > 1_000_000) {
                throw new IllegalArgumentException("successor cost is outside bounded range");
            }
            assumptions = DomainCanonical.sortedDistinct(assumptions);
            metadata = DomainCanonical.sortedMap(metadata);
        }
    }

    record ObjectiveAssessment(
        int score,
        boolean candidateReady,
        Map<String, String> metrics
    ) {
        public ObjectiveAssessment {
            metrics = DomainCanonical.sortedMap(metrics);
        }
    }

    record PathStep<S>(
        String fromStateHash,
        String actionId,
        S toState,
        String toStateHash,
        int cost,
        boolean semanticsPreserving,
        List<String> assumptions,
        Map<String, String> metadata
    ) {
        public PathStep {
            DomainCanonical.requireSha256(fromStateHash, "fromStateHash");
            DomainCanonical.requireIdentifier(actionId, "actionId");
            Objects.requireNonNull(toState, "toState");
            DomainCanonical.requireSha256(toStateHash, "toStateHash");
            assumptions = DomainCanonical.sortedDistinct(assumptions);
            metadata = DomainCanonical.sortedMap(metadata);
        }
    }

    record CandidateContext<S>(
        S initialState,
        S currentState,
        List<PathStep<S>> path
    ) {
        public CandidateContext {
            Objects.requireNonNull(initialState, "initialState");
            Objects.requireNonNull(currentState, "currentState");
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    enum CounterexampleStatus {
        NONE_FOUND,
        FOUND,
        INCONCLUSIVE,
        UNSUPPORTED
    }

    record CounterexampleResult(
        CounterexampleStatus status,
        int attempts,
        String witness,
        Map<String, String> metrics
    ) {
        public CounterexampleResult {
            Objects.requireNonNull(status, "status");
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts must be non-negative");
            }
            witness = witness == null ? "" : witness;
            metrics = DomainCanonical.sortedMap(metrics);
            if (status == CounterexampleStatus.FOUND && witness.isBlank()) {
                throw new IllegalArgumentException(
                    "counterexample status FOUND requires a witness");
            }
        }

        public static CounterexampleResult noneFound(
            int attempts,
            Map<String, String> metrics
        ) {
            return new CounterexampleResult(
                CounterexampleStatus.NONE_FOUND, attempts, "", metrics);
        }

        public static CounterexampleResult found(
            int attempts,
            String witness,
            Map<String, String> metrics
        ) {
            return new CounterexampleResult(
                CounterexampleStatus.FOUND, attempts, witness, metrics);
        }

        public static CounterexampleResult inconclusive(
            int attempts,
            String summary,
            Map<String, String> metrics
        ) {
            return new CounterexampleResult(
                CounterexampleStatus.INCONCLUSIVE, attempts, summary, metrics);
        }

        public static CounterexampleResult unsupported(String summary) {
            return new CounterexampleResult(
                CounterexampleStatus.UNSUPPORTED, 0, summary, Map.of());
        }
    }

    enum EvaluationStatus {
        CONFIRMED,
        REFUTED,
        INCONCLUSIVE,
        UNSUPPORTED
    }

    record Evaluation<K>(
        EvaluationStatus status,
        K certificate,
        String summary,
        Map<String, String> metrics
    ) {
        public Evaluation {
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            metrics = DomainCanonical.sortedMap(metrics);
            if (status == EvaluationStatus.CONFIRMED && certificate == null) {
                throw new IllegalArgumentException(
                    "confirmed evaluation requires a certificate object");
            }
            if (status != EvaluationStatus.CONFIRMED && certificate != null) {
                throw new IllegalArgumentException(
                    "non-confirmed evaluation must not carry a certificate object");
            }
        }

        public static <K> Evaluation<K> confirmed(
            K certificate,
            String summary,
            Map<String, String> metrics
        ) {
            return new Evaluation<>(
                EvaluationStatus.CONFIRMED,
                Objects.requireNonNull(certificate, "certificate"),
                summary,
                metrics);
        }

        public static <K> Evaluation<K> refuted(
            String summary,
            Map<String, String> metrics
        ) {
            return new Evaluation<>(
                EvaluationStatus.REFUTED, null, summary, metrics);
        }

        public static <K> Evaluation<K> inconclusive(
            String summary,
            Map<String, String> metrics
        ) {
            return new Evaluation<>(
                EvaluationStatus.INCONCLUSIVE, null, summary, metrics);
        }

        public static <K> Evaluation<K> unsupported(
            String summary,
            Map<String, String> metrics
        ) {
            return new Evaluation<>(
                EvaluationStatus.UNSUPPORTED, null, summary, metrics);
        }
    }

    record CertificateRendering(
        String kind,
        String format,
        String rendered
    ) {
        public CertificateRendering {
            DomainCanonical.requireIdentifier(kind, "certificate kind");
            DomainCanonical.requireIdentifier(format, "certificate format");
            DomainCanonical.requireText(rendered, "rendered certificate");
        }
    }

    record RenderedCertificate(
        String kind,
        String format,
        String certificateObjectHash,
        String rendered,
        String contentHash
    ) {
        public RenderedCertificate {
            DomainCanonical.requireIdentifier(kind, "certificate kind");
            DomainCanonical.requireIdentifier(format, "certificate format");
            DomainCanonical.requireSha256(
                certificateObjectHash, "certificateObjectHash");
            DomainCanonical.requireText(rendered, "rendered certificate");
            DomainCanonical.requireSha256(contentHash, "contentHash");
            String expected = DomainCanonical.sha256(
                kind + "\n" + format + "\n" + certificateObjectHash
                    + "\n" + rendered);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "rendered certificate contentHash mismatch");
            }
        }

        public static RenderedCertificate create(
            CertificateRendering rendering,
            String certificateObjectHash
        ) {
            Objects.requireNonNull(rendering, "rendering");
            DomainCanonical.requireSha256(
                certificateObjectHash, "certificateObjectHash");
            String hash = DomainCanonical.sha256(
                rendering.kind() + "\n" + rendering.format() + "\n"
                    + certificateObjectHash + "\n" + rendering.rendered());
            return new RenderedCertificate(
                rendering.kind(),
                rendering.format(),
                certificateObjectHash,
                rendering.rendered(),
                hash);
        }
    }

    record DomainPayload(String type, Map<String, String> properties) {
        public DomainPayload {
            DomainCanonical.requireIdentifier(type, "domain payload type");
            properties = DomainCanonical.sortedMap(properties);
        }
    }
}
