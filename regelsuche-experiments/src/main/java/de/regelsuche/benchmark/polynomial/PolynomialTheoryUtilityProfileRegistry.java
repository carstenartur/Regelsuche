package de.regelsuche.benchmark.polynomial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
        return requireRunnableAdapters(Path.of(System.getProperty("regelsuche.repositoryRoot", ".")));
    }

    public static List<PolynomialTheoryUtilityProfileAdapter> requireRunnableAdapters(Path checkout) {
        var entries = entries();
        var blockers = new ArrayList<String>();
        runtimeLockMismatch(checkout).ifPresent(blockers::add);
        entries.stream().filter(entry -> entry.adapter().isEmpty())
            .map(entry -> entry.profile().profileId() + ":" + entry.blocker())
            .forEach(blockers::add);
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("polynomial utility profiles are not ready: " + blockers);
        }
        return entries.stream().map(value -> value.adapter().orElseThrow()).toList();
    }

    private static Optional<String> runtimeLockMismatch(Path checkout) {
        Path lock = checkout.resolve(PolynomialTheoryUtilityExecutionPlan.EXTERNAL_LOCK_PATH);
        String expected = PolynomialTheoryUtilityExecutionPlan.EXTERNAL_RUNTIME_ID;
        String prefix = "# graalpy-version:";
        var runtime = new ArrayList<String>();
        var packages = new ArrayList<String>();
        try {
            for (String raw : Files.readAllLines(lock)) {
                String line = raw.strip();
                if (line.startsWith(prefix)) {
                    runtime.add("graalpy-" + line.substring(prefix.length()).strip());
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    packages.add(line);
                }
            }
        } catch (IOException e) {
            return Optional.of("EXTERNAL_RUNTIME_LOCK_MISMATCH: cannot read " + lock);
        }
        // Check the complete resolved environment, including duplicate or extra entries.
        // Input-package comments are generator metadata and do not replace resolved pins.
        var parts = expected.split("_");
        var expectedPackages = Arrays.stream(parts).skip(1)
            .map(pin -> pin.replaceFirst("-", "==")).sorted().toList();
        if (runtime.equals(List.of(parts[0])) && packages.stream().sorted().toList().equals(expectedPackages)) {
            return Optional.empty();
        }
        return Optional.of("EXTERNAL_RUNTIME_LOCK_MISMATCH: expected " + expected
            + ", observed runtime " + runtime + " and packages " + packages);
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
