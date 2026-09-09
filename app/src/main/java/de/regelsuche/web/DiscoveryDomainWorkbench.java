package de.regelsuche.web;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.sdk.discovery.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Host-enabled domain selection; HTTP input can never enable arbitrary provider classes. */
final class DiscoveryDomainWorkbench {
    private static final Map<HostConfiguration, DiscoveryDomainWorkbench> HOSTS =
        new ConcurrentHashMap<>();

    private final DiscoveryDomainCatalog catalog;

    DiscoveryDomainWorkbench(DiscoveryDomainCatalog catalog) { this.catalog = catalog; }

    static DiscoveryDomainWorkbench forHost(ClassLoader loader, String enabledClasses) {
        Objects.requireNonNull(loader, "loader");
        Set<String> enabled = Arrays.stream(enabledClasses.split(",", -1)).map(String::trim)
            .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        var key = new HostConfiguration(loader, enabled);
        return HOSTS.computeIfAbsent(key, configuration -> new DiscoveryDomainWorkbench(
            DiscoveryDomainCatalog.load(configuration.loader(), configuration.enabled())));
    }

    String catalogJson() {
        return new JsonWriter().beginObject().property("apiVersion", DiscoveryApi.VERSION)
            .array("domains", array -> catalog.registrations().forEach(entry -> array.objectValue(item ->
                item.property("providerId", entry.providerId()).property("providerVersion", entry.providerVersion())
                    .property("domainId", entry.domain().domainId()).property("revision", entry.domain().revision())
                    .property("artifactSha256", entry.artifact().orElseThrow().artifactSha256()))))
            .endObject().toString();
    }

    String run(Map<String, Object> input) {
        if (!input.keySet().equals(Set.of("providerId", "domainId", "revision", "campaignId", "seed", "budget"))) {
            throw new IllegalArgumentException("provide providerId, domainId, revision, campaignId, seed and budget only");
        }
        var entry = catalog.find(text(input, "domainId"), text(input, "revision"))
            .filter(value -> value.providerId().equals(text(input, "providerId")))
            .orElseThrow(() -> new IllegalArgumentException("domain is not enabled by this host"));
        var budget = switch (text(input, "budget")) {
            case "small" -> DiscoveryBudgets.small();
            case "tiny" -> DiscoveryBudgets.tiny();
            default -> throw new IllegalArgumentException("budget must be small or tiny");
        };
        return RegelsucheDiscovery.forRegistration(entry).campaign(text(input, "campaignId"))
            .seed("workbench-seed", text(input, "seed"), "discovery-domain-workbench")
            .budget(budget).run().canonicalEvidence();
    }

    private static String text(Map<String, Object> input, String name) {
        if (!(input.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-empty text");
        }
        return value;
    }

    private record HostConfiguration(ClassLoader loader, Set<String> enabled) {
    }
}
