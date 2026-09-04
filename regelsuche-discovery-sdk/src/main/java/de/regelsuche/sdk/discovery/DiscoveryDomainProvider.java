package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.util.Collection;

/**
 * Service-provider interface for externally packaged discovery domains.
 *
 * <p>Provider discovery only makes a domain available. It does not establish
 * mathematical correctness, proof status, promotion or artifact trust.</p>
 */
public interface DiscoveryDomainProvider {
    /** Stable provider identity. */
    String id();

    /** Provider/API revision shown in diagnostics and catalogs. */
    default String version() {
        return "1";
    }

    /** Human-auditable source or release reference; may be empty locally. */
    default String provenance() {
        return "";
    }

    /** Domains supplied by this provider. */
    Collection<DiscoveryDomain<?, ?, ?>> domains();
}
