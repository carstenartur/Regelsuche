package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryRunner;
import java.util.Objects;

/**
 * Headless entry point for one bounded discovery run.
 *
 * <p>The facade owns no mathematical authority. It delegates execution to the
 * deterministic {@link DomainDiscoveryRunner} and returns the original
 * canonical evidence together with typed candidate and certificate objects.</p>
 */
public final class RegelsucheDiscovery {
    private RegelsucheDiscovery() {
    }

    /** Starts a fluent request for the supplied domain at the serialized seed boundary. */
    public static <S, C, K> Request<S, C, K> forDomain(
            DiscoveryDomain<S, C, K> domain
    ) {
        return new Request<>(domain);
    }

    /** Starts a Java request whose input type is fixed by the selected domain. */
    public static <I, S, C, K> TypedRequest<I, S, C, K> forDomain(
            TypedDiscoveryDomain<I, S, C, K> domain
    ) {
        return new TypedRequest<>(Objects.requireNonNull(domain, "domain"));
    }

    /**
     * Starts an exact catalog registration and retains its host-observed provider artifact in evidence.
     * Manual registrations without observed artifact provenance are rejected rather than silently
     * degrading the evidence contract.
     */
    public static Request<?, ?, ?> forRegistration(DiscoveryDomainCatalog.Registration registration) {
        var checked = Objects.requireNonNull(registration, "registration");
        if (checked.artifact().isEmpty()) {
            throw new IllegalArgumentException(
                "registration must carry host-observed provider artifact provenance"
            );
        }
        return forDomain(checked.domain());
    }

    /** Loads discovery-domain providers visible to the context class loader. */
    public static DiscoveryDomainCatalog loadDomains() {
        return DiscoveryDomainCatalog.load();
    }

    /**
     * Typed Java input builder. Deliberately has no Object or serialized-seed overload:
     * input types cannot be widened by a generic seed method. Not thread-safe.
     */
    public static final class TypedRequest<I, S, C, K> {
        private final Request<S, C, K> request;
        private final DiscoveryInputCodec<I> inputCodec;

        private TypedRequest(TypedDiscoveryDomain<I, S, C, K> domain) {
            request = new Request<>(domain.domain());
            inputCodec = domain.inputCodec();
        }

        public TypedRequest<I, S, C, K> campaign(String value) {
            request.campaign(value);
            return this;
        }

        /** Snapshots the input into the existing content-addressed seed format. */
        public TypedRequest<I, S, C, K> seed(String seedId, I input, String sourceReference) {
            String payload = Objects.requireNonNull(
                inputCodec.encode(Objects.requireNonNull(input, "input")), "encoded payload");
            request.seed(seedId, payload, sourceReference);
            return this;
        }

        public TypedRequest<I, S, C, K> budget(DiscoveryBudget value) {
            request.budget(value);
            return this;
        }

        public DiscoveryRun<C, K> run() {
            return request.run();
        }
    }

    /** Mutable request builder for serialized seeds; not thread-safe. */
    public static final class Request<S, C, K> {
        private final DiscoveryDomain<S, C, K> domain;
        private String campaignId;
        private DiscoverySeed seed;
        private DiscoveryBudget budget = DiscoveryBudgets.small();

        private Request(DiscoveryDomain<S, C, K> domain) {
            this.domain = Objects.requireNonNull(domain, "domain");
        }

        /** Sets the stable run/campaign identity retained in evidence. */
        public Request<S, C, K> campaign(String value) {
            this.campaignId = requireText(value, "campaignId");
            return this;
        }

        /** Uses a fully constructed seed. */
        public Request<S, C, K> seed(DiscoverySeed value) {
            this.seed = Objects.requireNonNull(value, "seed");
            return this;
        }

        /**
         * Creates a content-addressed seed at the text import boundary.
         * Prefer the TypedDiscoveryDomain overload of forDomain for ordinary Java calls.
         */
        public Request<S, C, K> seed(
                String seedId,
                String payload,
                String sourceReference
        ) {
            this.seed = DiscoverySeed.create(
                seedId,
                domain.domainId(),
                payload,
                sourceReference
            );
            return this;
        }

        /** Replaces the documented small default budget. */
        public Request<S, C, K> budget(DiscoveryBudget value) {
            this.budget = Objects.requireNonNull(value, "budget");
            return this;
        }

        /** Executes the request synchronously. */
        public DiscoveryRun<C, K> run() {
            if (campaignId == null) {
                throw new IllegalStateException("campaignId is required");
            }
            if (seed == null) {
                throw new IllegalStateException("seed is required");
            }
            if (!domain.domainId().equals(seed.domainId())) {
                throw new IllegalStateException(
                    "seed domain does not match the selected domain"
                );
            }
            if (domain instanceof ProviderDomain<?, ?, ?> registered) registered.validateArtifact();
            DomainDiscoveryRunner.RunResult<C, K> result =
                new DomainDiscoveryRunner().run(
                    campaignId,
                    domain,
                    seed,
                    budget
                );
            String replayCampaign = campaignId;
            DiscoverySeed replaySeed = seed;
            DiscoveryBudget replayBudget = budget;
            return new DiscoveryRun<>(
                result.selectedCandidate(),
                result.selectedCertificate(),
                result.evidence(),
                domain.candidateCodec(),
                domain.certificateCodec(),
                () -> RegelsucheDiscovery.forDomain(domain).campaign(replayCampaign)
                    .seed(replaySeed).budget(replayBudget).run()
            );
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
