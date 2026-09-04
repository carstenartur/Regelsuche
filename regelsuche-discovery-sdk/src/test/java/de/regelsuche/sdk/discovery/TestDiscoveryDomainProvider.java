package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.util.Collection;

public final class TestDiscoveryDomainProvider
        implements DiscoveryDomainProvider {
    @Override
    public String id() {
        return "sdk-test-provider";
    }

    @Override
    public String provenance() {
        return "test-runtime";
    }

    @Override
    public Collection<DiscoveryDomain<?, ?, ?>> domains() {
        return ListHolder.DOMAINS;
    }

    private static final class ListHolder {
        private static final Collection<DiscoveryDomain<?, ?, ?>> DOMAINS =
            java.util.List.of(DiscoverySdkTest.sampleDomain());
    }
}
