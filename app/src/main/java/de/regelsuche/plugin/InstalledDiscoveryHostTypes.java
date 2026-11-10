package de.regelsuche.plugin;

import de.regelsuche.sdk.discovery.*;
import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomainDescriptor;
import java.util.Set;

/** Explicit Discovery API exports; adding a type never exports its package. */
final class InstalledDiscoveryHostTypes {
    private InstalledDiscoveryHostTypes() { }
    static final Set<Class<?>> TYPES = Set.of(
        DiscoveryExtensionPoints.class, DiscoveryDomainProvider.class, DiscoveryApi.class,
        DiscoveryDomainBuilder.class, DiscoveryDomain.class, DiscoveryDomainDescriptor.class,
        DiscoveryDomain.IdentifiedComponent.class,
        DiscoveryDomain.StateGenerator.class,
        DiscoveryDomain.CanonicalCodec.class,
        DiscoveryDomain.Invariant.class,
        DiscoveryDomain.TransitionOperator.class,
        DiscoveryDomain.Objective.class,
        DiscoveryDomain.CandidateExtractor.class,
        DiscoveryDomain.CounterexampleGenerator.class,
        DiscoveryDomain.CandidateEvaluator.class,
        DiscoveryDomain.CertificateRenderer.class,
        DiscoveryDomain.EvidenceAdapter.class,
        DiscoveryDomain.DiscoverySeed.class,
        DiscoveryDomain.DiscoveryBudget.class,
        DiscoveryDomain.InvariantResult.class,
        DiscoveryDomain.Successor.class,
        DiscoveryDomain.ObjectiveAssessment.class,
        DiscoveryDomain.PathStep.class,
        DiscoveryDomain.CandidateContext.class,
        DiscoveryDomain.CounterexampleStatus.class,
        DiscoveryDomain.CounterexampleResult.class,
        DiscoveryDomain.EvaluationStatus.class,
        DiscoveryDomain.Evaluation.class,
        DiscoveryDomain.CertificateRendering.class,
        DiscoveryDomain.RenderedCertificate.class,
        DiscoveryDomain.DomainPayload.class);
}
