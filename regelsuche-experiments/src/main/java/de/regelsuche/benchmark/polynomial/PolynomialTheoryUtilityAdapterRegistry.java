package de.regelsuche.benchmark.polynomial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact one-adapter-per-preregistered-profile registry. */
public final class PolynomialTheoryUtilityAdapterRegistry {
    private final Map<String, PolynomialTheoryUtilityProfileAdapter> byId;
    private final Map<String, String> adapterByProfile;

    public PolynomialTheoryUtilityAdapterRegistry(
        List<PolynomialTheoryUtilityProfileAdapter> adapters
    ) {
        Objects.requireNonNull(adapters, "adapters");
        Map<String, String> expected = new LinkedHashMap<>();
        PolynomialTheoryUtilityExecutionPlan.PROFILES.forEach(profile ->
            expected.put(profile.profileId(), profile.adapterId()));

        Map<String, PolynomialTheoryUtilityProfileAdapter> suppliedById =
            new LinkedHashMap<>();
        Map<String, PolynomialTheoryUtilityProfileAdapter> suppliedByProfile =
            new LinkedHashMap<>();
        for (var adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            String profileId = requireText(
                adapter.profileId(),
                "profileId"
            );
            String adapterId = requireText(adapter.adapterId(), "adapterId");
            String expectedAdapterId = expected.get(profileId);
            if (!adapterId.equals(expectedAdapterId)) {
                throw new IllegalArgumentException(
                    "adapter identity differs from its frozen profile: "
                        + profileId
                );
            }
            if (suppliedById.putIfAbsent(adapterId, adapter) != null) {
                throw new IllegalArgumentException(
                    "duplicate polynomial utility adapter: " + adapterId
                );
            }
            if (suppliedByProfile.putIfAbsent(profileId, adapter) != null) {
                throw new IllegalArgumentException(
                    "duplicate polynomial utility profile adapter: "
                        + profileId
                );
            }
        }
        if (!suppliedByProfile.keySet().equals(expected.keySet())) {
            throw new IllegalArgumentException(
                "adapter registry differs from the frozen profile contract"
            );
        }
        byId = Collections.unmodifiableMap(
            new LinkedHashMap<>(suppliedById)
        );
        adapterByProfile = Collections.unmodifiableMap(
            new LinkedHashMap<>(expected)
        );
    }

    public PolynomialTheoryUtilityProfileAdapter require(
        String profileId,
        String adapterId
    ) {
        String profile = requireText(profileId, "profileId");
        String adapter = requireText(adapterId, "adapterId");
        if (!adapter.equals(adapterByProfile.get(profile))) {
            throw new IllegalArgumentException(
                "input adapter does not match its frozen profile: " + profile
            );
        }
        var resolved = byId.get(adapter);
        if (resolved == null) {
            throw new IllegalStateException(
                "missing frozen polynomial utility adapter: " + adapter
            );
        }
        if (!profile.equals(resolved.profileId())
                || !adapter.equals(resolved.adapterId())) {
            throw new IllegalStateException(
                "resolved adapter no longer matches the frozen profile"
            );
        }
        return resolved;
    }

    public List<String> profileIds() {
        return List.copyOf(adapterByProfile.keySet());
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
