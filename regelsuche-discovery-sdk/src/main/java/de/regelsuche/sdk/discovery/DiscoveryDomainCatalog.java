package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
        return load(classLoader, null);
    }

    /**
     * Loads only explicitly enabled provider class names. An empty set disables
     * all providers; null retains the embedding application's classpath policy.
     * Selection is not a security sandbox for enabled code.
     */
    public static DiscoveryDomainCatalog load(ClassLoader classLoader, java.util.Set<String> enabledClasses) {
        Objects.requireNonNull(classLoader, "classLoader");
        java.util.Set<String> enabled = enabledClasses == null ? null : java.util.Set.copyOf(enabledClasses);
        java.util.Set<String> found = new java.util.HashSet<>();
        List<DiscoveryDomainProvider> providers = new ArrayList<>();
        try {
            for (var entry : ServiceLoader.load(DiscoveryDomainProvider.class, classLoader).stream().toList()) {
                String name = entry.type().getName();
                if (enabled == null || enabled.contains(name)) {
                    found.add(name);
                    providers.add(entry.get());
                }
            }
            if (enabled != null && !found.equals(enabled)) {
                var missing = new java.util.TreeSet<>(enabled);
                missing.removeAll(found);
                throw new IllegalArgumentException("enabled provider classes not installed: " + missing);
            }
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException(
                "failed to load discovery-domain providers",
                error
            );
        }
        return fromProviders(providers);
    }

    /**
     * Builds a catalog from explicit providers, useful for tests and embedding.
     *
     * <p>Provider order and each provider's domain-collection order are not
     * trusted. Both are normalized by stable identifiers before registrations
     * are exposed or duplicate revisions are diagnosed.</p>
     */
    public static DiscoveryDomainCatalog fromProviders(
            Iterable<? extends DiscoveryDomainProvider> providers
    ) {
        Objects.requireNonNull(providers, "providers");
        List<ProviderEntry> normalizedProviders = new ArrayList<>();
        Map<String, DiscoveryDomainProvider> providerIds = new LinkedHashMap<>();

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
            if (!DiscoveryApi.VERSION.equals(provider.apiVersion())) {
                throw new IllegalArgumentException("incompatible discovery provider " + providerId
                    + ": requires API " + provider.apiVersion() + ", runtime API " + DiscoveryApi.VERSION);
            }
            normalizedProviders.add(new ProviderEntry(
                providerId,
                providerVersion,
                normalizeProvenance(provider.provenance()),
                ProviderProvenance.capture(provider.getClass()),
                provider
            ));
        }
        normalizedProviders.sort(Comparator.comparing(ProviderEntry::id));

        Map<String, Registration> domains = new LinkedHashMap<>();
        for (ProviderEntry provider : normalizedProviders) {
            Collection<DiscoveryDomain<?, ?, ?>> supplied = Objects.requireNonNull(
                provider.provider().domains(),
                "provider domains"
            );
            List<DiscoveryDomain<?, ?, ?>> normalizedDomains = new ArrayList<>(supplied);
            normalizedDomains.forEach(domain -> {
                Objects.requireNonNull(domain, "provider domain");
                domain.descriptor();
            });
            normalizedDomains.sort(
                Comparator.comparing(DiscoveryDomain<?, ?, ?>::domainId)
                    .thenComparing(DiscoveryDomain<?, ?, ?>::revision)
            );

            for (DiscoveryDomain<?, ?, ?> domain : normalizedDomains) {
                String key = domain.domainId() + "@" + domain.revision();
                Registration registration = new Registration(
                    provider.id(),
                    provider.version(),
                    provider.provenance(),
                    new ProviderDomain<>(domain, provider.artifact().properties(
                        provider.id(), provider.version(), provider.provenance()),
                        () -> ProviderProvenance.capture(provider.provider().getClass()))
                );
                Registration previous = domains.putIfAbsent(key, registration);
                if (previous != null) {
                    throw new IllegalArgumentException(
                        "duplicate discovery domain revision: " + key
                            + " from " + previous.providerId()
                            + " and " + provider.id()
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

    private record ProviderEntry(
        String id,
        String version,
        String provenance,
        ProviderProvenance artifact,
        DiscoveryDomainProvider provider
    ) {
    }

    /** Provenance-bearing catalog entry. */
    public record Registration(
        String providerId,
        String providerVersion,
        String providerProvenance,
        DiscoveryDomain<?, ?, ?> domain
    ) {
        /** Host-observed artifact for catalog-loaded domains; empty for manual entries. */
        public Optional<ProviderProvenance> artifact() {
            return domain instanceof ProviderDomain<?, ?, ?> registered
                ? Optional.of(registered.artifact()) : Optional.empty();
        }

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
