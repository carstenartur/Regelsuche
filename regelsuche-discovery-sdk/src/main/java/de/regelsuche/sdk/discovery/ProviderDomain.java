package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Keeps host-observed provenance attached when a registration's domain is used. */
final class ProviderDomain<S, C, K> implements DiscoveryDomain<S, C, K> {
    private final DiscoveryDomain<S, C, K> delegate;
    private final Map<String, String> provenance;
    private final java.util.function.Supplier<ProviderProvenance> observedArtifact;

    ProviderDomain(DiscoveryDomain<S, C, K> delegate, Map<String, String> provenance,
            java.util.function.Supplier<ProviderProvenance> observedArtifact) {
        this.delegate = delegate;
        this.provenance = Map.copyOf(provenance);
        this.observedArtifact = observedArtifact;
    }

    void validateArtifact() {
        if (!artifact().equals(observedArtifact.get())) {
            throw new IllegalStateException("provider artifact changed after catalog loading");
        }
    }

    ProviderProvenance artifact() {
        return new ProviderProvenance(provenance.get("sdk.provider.implementationClass"),
            provenance.get("sdk.provider.artifactKind"), provenance.get("sdk.provider.artifactSha256"));
    }

    @Override public String domainId() { return delegate.domainId(); }
    @Override public String revision() { return delegate.revision(); }
    @Override public String stateType() { return delegate.stateType(); }
    @Override public String candidateType() { return delegate.candidateType(); }
    @Override public String certificateType() { return delegate.certificateType(); }
    @Override public StateGenerator<S> generator() { return delegate.generator(); }
    @Override public CanonicalCodec<S> stateCodec() { return delegate.stateCodec(); }
    @Override public List<Invariant<S>> invariants() { return delegate.invariants(); }
    @Override public List<TransitionOperator<S>> operators() { return delegate.operators(); }
    @Override public Objective<S> objective() { return delegate.objective(); }
    @Override public CandidateExtractor<S, C> candidateExtractor() { return delegate.candidateExtractor(); }
    @Override public CanonicalCodec<C> candidateCodec() { return delegate.candidateCodec(); }
    @Override public CounterexampleGenerator<C> counterexampleGenerator() { return delegate.counterexampleGenerator(); }
    @Override public CandidateEvaluator<C, K> evaluator() { return delegate.evaluator(); }
    @Override public CanonicalCodec<K> certificateCodec() { return delegate.certificateCodec(); }
    @Override public CertificateRenderer<K> certificateRenderer() { return delegate.certificateRenderer(); }

    @Override public EvidenceAdapter<S, C, K> evidenceAdapter() {
        var adapter = delegate.evidenceAdapter();
        return new EvidenceAdapter<>() {
            @Override public String id() { return adapter.id(); }
            @Override public DomainPayload adapt(java.util.Optional<S> initial,
                    java.util.Optional<C> candidate, java.util.Optional<K> certificate) {
                validateArtifact();
                var payload = adapter.adapt(initial, candidate, certificate);
                var properties = new TreeMap<>(payload.properties());
                if (properties.keySet().stream().anyMatch(key -> key.startsWith("sdk.provider."))) {
                    throw new IllegalArgumentException("domain evidence uses reserved sdk.provider. properties");
                }
                properties.putAll(provenance);
                return new DomainPayload(payload.type(), properties);
            }
        };
    }
}
