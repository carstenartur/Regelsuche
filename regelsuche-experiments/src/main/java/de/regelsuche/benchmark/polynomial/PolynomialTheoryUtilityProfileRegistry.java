package de.regelsuche.benchmark.polynomial;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact five-profile readiness inventory; construction never executes a row. */
public final class PolynomialTheoryUtilityProfileRegistry {
    public static final String EXTERNAL_BLOCKER = "EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE";

    private PolynomialTheoryUtilityProfileRegistry() { }

    public static List<Registration> entries() {
        var adapters = List.of(PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter.observed(),
            PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.observed(value -> { }),
            new PolynomialTheoryUtilityDerivedCacheAdapter(), new PolynomialTheoryUtilitySpecializedAdapter());
        return PolynomialTheoryUtilityExecutionPlan.PROFILES.stream().map(profile -> {
            var adapter = adapters.stream().filter(value -> value.profileId().equals(profile.profileId())).findFirst();
            return new Registration(profile, adapter, adapter.isPresent() ? "" : EXTERNAL_BLOCKER);
        }).toList();
    }

    /** Available implementations only; this list cannot pass the complete-run preflight. */
    public static List<PolynomialTheoryUtilityProfileAdapter> availableAdapters() {
        return entries().stream().flatMap(value -> value.adapter().stream()).toList();
    }

    /** Fails before any session opens while a frozen profile lacks complete work evidence. */
    public static List<PolynomialTheoryUtilityProfileAdapter> requireRunnableAdapters() {
        var entries = entries();
        var blocked = entries.stream().filter(value -> value.adapter().isEmpty()).toList();
        if (!blocked.isEmpty()) {
            throw new IllegalStateException("polynomial utility profiles are not ready: " + blocked.stream()
                .map(value -> value.profile().profileId() + ":" + value.blocker()).toList());
        }
        return entries.stream().flatMap(value -> value.adapter().stream()).toList();
    }

    public record Registration(PolynomialTheoryUtilityExecutionProfile profile,
            Optional<PolynomialTheoryUtilityProfileAdapter> adapter, String blocker) {
        public Registration {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(blocker, "blocker");
            if (adapter.isPresent() == !blocker.isEmpty() || adapter.filter(value ->
                    !value.profileId().equals(profile.profileId()) || !value.adapterId().equals(profile.adapterId())
                        || !value.resultSchema().equals(PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA)).isPresent()) {
                throw new IllegalArgumentException("profile readiness differs from its frozen adapter");
            }
        }
    }
}
