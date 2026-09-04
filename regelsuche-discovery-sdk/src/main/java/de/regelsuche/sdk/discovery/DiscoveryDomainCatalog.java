package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Fail-closed catalog of discovery domains loaded through {@link ServiceLoader}.
 */
public final class DiscoveryDomainCatalog {
    private final List<Registration> registrations;

    private DiscoveryDomainCatalog(List<Registration> registrations) {
        this.registrations = List.copyOf(registrations);
    }

    /** Loads providers with the current thread context class loader. */
    public static DiscoveryDomainCatalog load() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return load(context == null
            ? DiscoveryDomainCatalog.class.getClassLoader()
            : context);
    }

    /** Loads providers with an explicit class loader. */
    public static DiscoveryDomainCatalog load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        List<DiscoveryDomainProvider> providers = new ArrayList<>();
        try {
            ServiceLoader.load(DiscoveryDomainProvider.class, classLoader)
                .forEach(providers::add);
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException(
                "failed to load discovery-domain providers",
                error
            );
        }
        return fromProviders(providers);
    }

    /** Builds a catalog from explicit providers, useful for tests and embedding. */
    public static DiscoveryDomainCatalog fromProviders(
            Iterable<? extends DiscoveryDomainProvider> providers
    ) {
        Objects.requireNonNull(providers, "providers");
        Map<String, DiscoveryDomainProvider> providerIds = new LinkedHashMap<>();
        Map<String, Registration> domains = new LinkedHashMap<>();

        for (DiscoveryDomainProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String providerId = requireIdentifier(provider.id(), "provider id");
            String providerVersion = requireIdentifier(
                provider.version(),
                "provider version"
            );
            if (providerIds.putIfAbsent(providerId, provider) != null) {
                throw new IllegalArgumentException(
                    "duplicate discovery provider id: " + providerId
                );
            }

            Collection<DiscoveryDomain<?, ?, ?>> supplied = Objects.requireNonNull(
                provider.domains(),
                "provider domains"
            );
            for (DiscoveryDomain<?, ?, ?> domain : supplied) {
                Objects.requireNonNull(domain, "provider domain");
                domain.descriptor();
                String key = domain.domainId() + "@" + domain.revision();
                Registration registration = new Registration(
                    providerId,
                    providerVersion,
                    normalizeProvenance(provider.provenance()),
                    domain
                );
                Registration previous = domains.putIfAbsent(key, registration);
                if (previous != null) {
                    throw new IllegalArgumentException(
                        "duplicate discovery domain revision: " + key
                            + " from " + previous.providerId()
                            + " and " + providerId
                    );
                }
            }
        }
        return new DiscoveryDomainCatalog(new ArrayList<>(domains.values()));
    }

    /** All registrations in deterministic provider/domain order. */
    public List<Registration> registrations() {
        return registrations;
    }

    /** Finds one exact domain revision. */
    public Optional<Registration> find(String domainId, String revision) {
        String checkedDomain = requireIdentifier(domainId, "domainId");
        String checkedRevision = requireIdentifier(revision, "revision");
        return registrations.stream()
            .filter(registration ->
                registration.domain().domainId().equals(checkedDomain)
                    && registration.domain().revision().equals(checkedRevision))
            .findFirst();
    }

    private static String normalizeProvenance(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}")) {
            throw new IllegalArgumentException(name + " is not a valid identifier");
        }
        return value;
    }

    /** Provenance-bearing catalog entry. */
    public record Registration(
        String providerId,
        String providerVersion,
        String providerProvenance,
        DiscoveryDomain<?, ?, ?> domain
    ) {
        public Registration {
            requireIdentifier(providerId, "providerId");
            requireIdentifier(providerVersion, "providerVersion");
            providerProvenance = providerProvenance == null
                ? ""
                : providerProvenance.trim();
            Objects.requireNonNull(domain, "domain");
        }
    }
}
