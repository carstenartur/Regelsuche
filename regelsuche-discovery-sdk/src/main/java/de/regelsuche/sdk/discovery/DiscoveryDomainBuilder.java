package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builds a {@link DiscoveryDomain} from small, named Java functions.
 *
 * <p>The builder removes wiring boilerplate, but it deliberately does not infer
 * mathematical validity. A caller must still provide an invariant, a
 * counterexample search and an evaluator. A confirmed evaluator result must
 * carry a certificate object.</p>
 */
public final class DiscoveryDomainBuilder<S, C, K> {
    private final String domainId;
    private final String revision;
    private final List<Invariant<S>> invariants = new ArrayList<>();
    private final List<TransitionOperator<S>> operators = new ArrayList<>();

    private StateGenerator<S> generator;
    private CanonicalCodec<S> stateCodec;
    private Objective<S> objective;
    private CandidateExtractor<S, C> candidateExtractor;
    private CanonicalCodec<C> candidateCodec;
    private CounterexampleGenerator<C> counterexampleGenerator;
    private CandidateEvaluator<C, K> evaluator;
    private CanonicalCodec<K> certificateCodec;
    private CertificateRenderer<K> certificateRenderer;

    private DiscoveryDomainBuilder(String domainId, String revision) {
        this.domainId = requireIdentifier(domainId, "domainId");
        this.revision = requireIdentifier(revision, "revision");
    }

    /**
     * Starts a domain definition with stable domain and revision identifiers.
     *
     * <p>Changing mathematical semantics requires a new revision. Component
     * identifiers and default type identifiers are derived from these two
     * values.</p>
     */
    public static <S, C, K> DiscoveryDomainBuilder<S, C, K> domain(
            String domainId,
            String revision
    ) {
        return new DiscoveryDomainBuilder<>(domainId, revision);
    }

    /** Defines how a seed creates the initial search states. */
    public DiscoveryDomainBuilder<S, C, K> generator(
            Function<DiscoverySeed, List<S>> function
    ) {
        Objects.requireNonNull(function, "function");
        String id = componentId("generator");
        this.generator = new StateGenerator<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<S> generate(DiscoverySeed seed) {
                List<S> generated = Objects.requireNonNull(
                    function.apply(seed),
                    "generator returned null"
                );
                return List.copyOf(generated);
            }
        };
        return this;
    }

    /** Defines the canonical, content-addressed form of a state. */
    public DiscoveryDomainBuilder<S, C, K> stateCodec(
            Function<S, String> canonicalForm
    ) {
        this.stateCodec = codec("state-codec", canonicalForm);
        return this;
    }

    /** Adds one explicit state invariant. */
    public DiscoveryDomainBuilder<S, C, K> invariant(
            String name,
            Function<S, InvariantResult> check
    ) {
        Objects.requireNonNull(check, "check");
        String id = componentId("invariant-" + requireSegment(name, "invariant name"));
        if (invariants.stream().anyMatch(existing -> existing.id().equals(id))) {
            throw new IllegalArgumentException(
                "duplicate invariant component id: " + id
            );
        }
        invariants.add(new Invariant<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public InvariantResult check(S state) {
                return Objects.requireNonNull(
                    check.apply(state),
                    "invariant returned null"
                );
            }
        });
        return this;
    }

    /** Adds one bounded state-transition operator. */
    public DiscoveryDomainBuilder<S, C, K> operator(
            String name,
            Function<S, List<Successor<S>>> apply
    ) {
        Objects.requireNonNull(apply, "apply");
        String id = componentId("operator-" + requireSegment(name, "operator name"));
        if (operators.stream().anyMatch(existing -> existing.id().equals(id))) {
            throw new IllegalArgumentException(
                "duplicate operator component id: " + id
            );
        }
        operators.add(new TransitionOperator<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Successor<S>> apply(S state) {
                List<Successor<S>> successors = Objects.requireNonNull(
                    apply.apply(state),
                    "operator returned null"
                );
                return List.copyOf(successors);
            }
        });
        return this;
    }

    /** Defines search priority and the point at which a candidate is ready. */
    public DiscoveryDomainBuilder<S, C, K> objective(
            Function<S, ObjectiveAssessment> assess
    ) {
        Objects.requireNonNull(assess, "assess");
        String id = componentId("objective");
        this.objective = new Objective<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ObjectiveAssessment assess(S state) {
                return Objects.requireNonNull(
                    assess.apply(state),
                    "objective returned null"
                );
            }
        };
        return this;
    }

    /** Defines candidate extraction and its canonical representation. */
    public DiscoveryDomainBuilder<S, C, K> candidate(
            Function<CandidateContext<S>, C> extract,
            Function<C, String> canonicalForm
    ) {
        Objects.requireNonNull(extract, "extract");
        String id = componentId("candidate-extractor");
        this.candidateExtractor = new CandidateExtractor<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public C extract(CandidateContext<S> context) {
                return Objects.requireNonNull(
                    extract.apply(context),
                    "candidate extractor returned null"
                );
            }
        };
        this.candidateCodec = codec("candidate-codec", canonicalForm);
        return this;
    }

    /**
     * Defines a bounded search for concrete counterexamples.
     *
     * <p>{@link CounterexampleResult#noneFound(int, Map)} is evidence only for
     * the executed attempts. It is not converted into confirmation by this
     * builder.</p>
     */
    public DiscoveryDomainBuilder<S, C, K> counterexamples(
            BiFunction<C, Integer, CounterexampleResult> search
    ) {
        Objects.requireNonNull(search, "search");
        String id = componentId("counterexample-search");
        this.counterexampleGenerator = new CounterexampleGenerator<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CounterexampleResult search(C candidate, int attemptBudget) {
                return Objects.requireNonNull(
                    search.apply(candidate, attemptBudget),
                    "counterexample search returned null"
                );
            }
        };
        return this;
    }

    /** Defines the independent candidate evaluator. */
    public DiscoveryDomainBuilder<S, C, K> evaluator(
            Function<C, Evaluation<K>> evaluate
    ) {
        Objects.requireNonNull(evaluate, "evaluate");
        String id = componentId("evaluator");
        this.evaluator = new CandidateEvaluator<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Evaluation<K> evaluate(C candidate) {
                return Objects.requireNonNull(
                    evaluate.apply(candidate),
                    "evaluator returned null"
                );
            }
        };
        return this;
    }

    /** Defines canonical certificate bytes and a plain-text rendering. */
    public DiscoveryDomainBuilder<S, C, K> certificate(
            String kind,
            Function<K, String> canonicalForm,
            Function<K, String> render
    ) {
        String checkedKind = requireIdentifier(kind, "certificate kind");
        this.certificateCodec = codec("certificate-codec", canonicalForm);
        Objects.requireNonNull(render, "render");
        String id = componentId("certificate-renderer");
        this.certificateRenderer = new CertificateRenderer<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CertificateRendering render(K certificate) {
                return new CertificateRendering(
                    checkedKind,
                    "text/plain",
                    Objects.requireNonNull(
                        render.apply(certificate),
                        "certificate renderer returned null"
                    )
                );
            }
        };
        return this;
    }

    /** Builds and eagerly validates the complete domain descriptor. */
    public DiscoveryDomain<S, C, K> build() {
        requireComponent(generator, "generator");
        requireComponent(stateCodec, "stateCodec");
        requireComponent(objective, "objective");
        requireComponent(candidateExtractor, "candidateExtractor");
        requireComponent(candidateCodec, "candidateCodec");
        requireComponent(counterexampleGenerator, "counterexampleGenerator");
        requireComponent(evaluator, "evaluator");
        requireComponent(certificateCodec, "certificateCodec");
        requireComponent(certificateRenderer, "certificateRenderer");
        if (invariants.isEmpty()) {
            throw new IllegalStateException(
                "at least one explicit state invariant is required"
            );
        }
        if (operators.isEmpty()) {
            throw new IllegalStateException(
                "at least one transition operator is required"
            );
        }
        DiscoveryDomain<S, C, K> domain = new BuiltDomain<>(
            domainId,
            revision,
            typeId("state"),
            typeId("candidate"),
            typeId("certificate"),
            generator,
            stateCodec,
            invariants,
            operators,
            objective,
            candidateExtractor,
            candidateCodec,
            counterexampleGenerator,
            evaluator,
            certificateCodec,
            certificateRenderer,
            defaultEvidenceAdapter()
        );
        domain.descriptor();
        return domain;
    }

    private EvidenceAdapter<S, C, K> defaultEvidenceAdapter() {
        String id = componentId("evidence-adapter");
        String type = domainId + "/sdk-evidence/" + revision;
        return new EvidenceAdapter<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public DomainPayload adapt(
                    Optional<S> initialState,
                    Optional<C> candidate,
                    Optional<K> certificate
            ) {
                return new DomainPayload(type, Map.of(
                    "initialStatePresent", Boolean.toString(initialState.isPresent()),
                    "candidatePresent", Boolean.toString(candidate.isPresent()),
                    "certificatePresent", Boolean.toString(certificate.isPresent()),
                    "formalProofStatus", "NOT_EVALUATED",
                    "externalNoveltyStatus", "NOT_EVALUATED"
                ));
            }
        };
    }

    private <T> CanonicalCodec<T> codec(
            String role,
            Function<T, String> canonicalForm
    ) {
        Objects.requireNonNull(canonicalForm, "canonicalForm");
        String id = componentId(role);
        return new CanonicalCodec<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String canonicalForm(T value) {
                String encoded = canonicalForm.apply(value);
                if (encoded == null || encoded.isBlank()) {
                    throw new IllegalArgumentException(
                        role + " canonical form must not be blank"
                    );
                }
                return encoded;
            }
        };
    }

    private String componentId(String role) {
        return domainId + "/" + role + "/" + revision;
    }

    private String typeId(String role) {
        return domainId + "/" + role + "-type/" + revision;
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}")) {
            throw new IllegalArgumentException(name + " is not a valid identifier");
        }
        return value;
    }

    private static String requireSegment(String value, String name) {
        if (value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(name + " is not a valid segment");
        }
        return value;
    }

    private static void requireComponent(Object value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
    }

    private record BuiltDomain<S, C, K>(
        String domainId,
        String revision,
        String stateType,
        String candidateType,
        String certificateType,
        StateGenerator<S> generator,
        CanonicalCodec<S> stateCodec,
        List<Invariant<S>> invariants,
        List<TransitionOperator<S>> operators,
        Objective<S> objective,
        CandidateExtractor<S, C> candidateExtractor,
        CanonicalCodec<C> candidateCodec,
        CounterexampleGenerator<C> counterexampleGenerator,
        CandidateEvaluator<C, K> evaluator,
        CanonicalCodec<K> certificateCodec,
        CertificateRenderer<K> certificateRenderer,
        EvidenceAdapter<S, C, K> evidenceAdapter
    ) implements DiscoveryDomain<S, C, K> {
        private BuiltDomain {
            invariants = List.copyOf(invariants);
            operators = List.copyOf(operators);
        }
    }
}
