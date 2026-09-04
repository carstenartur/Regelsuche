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

    /** Starts a fluent request for the supplied domain. */
    public static <S, C, K> Request<S, C, K> forDomain(
            DiscoveryDomain<S, C, K> domain
    ) {
        return new Request<>(domain);
    }

    /** Loads discovery-domain providers visible to the context class loader. */
    public static DiscoveryDomainCatalog loadDomains() {
        return DiscoveryDomainCatalog.load();
    }

    /** Mutable request builder for one execution; not thread-safe. */
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

        /** Creates a content-addressed seed for this domain. */
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
            DomainDiscoveryRunner.RunResult<C, K> result =
                new DomainDiscoveryRunner().run(
                    campaignId,
                    domain,
                    seed,
                    budget
                );
            return new DiscoveryRun<>(
                result.selectedCandidate(),
                result.selectedCertificate(),
                result.evidence()
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
