package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.solver.ir.SolverBackend;
import java.util.List;
import java.util.Objects;

/** Runtime system descriptors used by the comparative benchmark. */
final class ComparativeBenchmarkSystems {
    private ComparativeBenchmarkSystems() {
    }

    /** Search strategy whose complete frozen configuration is externally visible. */
    interface BenchmarkIdentifiedSearchStrategy extends SearchStrategy {
        String implementationIdentity();
    }

    record SearchSystem(
        String id,
        String version,
        SearchStrategy strategy,
        List<String> limitations
    ) {
        SearchSystem {
            requireText(id, "search system id");
            requireText(version, "search system version");
            Objects.requireNonNull(strategy, "strategy");
            limitations = clean(limitations);
        }
    }

    record ValidationSystem(
        SolverBackend backend,
        SystemKind kind,
        List<Role> roles,
        boolean available,
        String environmentIdentity,
        List<String> limitations
    ) {
        ValidationSystem {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(kind, "kind");
            roles = roles == null ? List.of() : roles.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparing(Enum::name))
                .toList();
            if (roles.isEmpty()) {
                throw new IllegalArgumentException(
                    "validation system roles must not be empty");
            }
            requireText(environmentIdentity, "environmentIdentity");
            limitations = clean(limitations);
        }
    }

    /**
     * One competitor in the target-free simplification track.
     *
     * <p>Exactly one of {@code strategy} and {@code externalSimplifier} is set:
     * either a path-based internal search or an external CAS simplifier. Both
     * receive the input expression without the pinned reference form and emit
     * exactly one expression under the output policy encoded in
     * {@link #implementationIdentity()}.</p>
     */
    record SimplificationSystem(
        String id,
        String version,
        SystemKind kind,
        SearchStrategy strategy,
        ExternalSymPySimplificationBaseline externalSimplifier,
        boolean available,
        String environmentIdentity,
        List<String> limitations
    ) {
        SimplificationSystem {
            requireText(id, "simplification system id");
            requireText(version, "simplification system version");
            Objects.requireNonNull(kind, "kind");
            if ((strategy == null) == (externalSimplifier == null)) {
                throw new IllegalArgumentException(
                    "exactly one simplification competitor implementation is required");
            }
            requireText(environmentIdentity, "environmentIdentity");
            limitations = clean(limitations);
        }

        /** @return the stable identity of implementation and target-free output selection. */
        String implementationIdentity() {
            if (strategy != null) {
                String strategyIdentity = strategy
                        instanceof BenchmarkIdentifiedSearchStrategy identified
                    ? identified.implementationIdentity()
                    : strategy.getClass().getName();
                return "internal:" + strategyIdentity
                    + "\noutputSelection=min-expression-score-then-text-then-depth/v1";
            }
            return "external:" + externalSimplifier.configurationHash()
                + "\noutputSelection=native-single-output/v1";
        }

        static SimplificationSystem internal(
            String id,
            String version,
            SearchStrategy strategy,
            List<String> limitations
        ) {
            return new SimplificationSystem(
                id, version, SystemKind.REGELSUCHE, strategy, null, true,
                "java=25\nsearch-kernel=regelsuche-search/v1", limitations);
        }

        static SimplificationSystem internalControl(
            String id,
            String version,
            SearchStrategy strategy,
            List<String> limitations
        ) {
            return new SimplificationSystem(
                id, version, SystemKind.ABLATION, strategy, null, true,
                "java=25\nsearch-kernel=regelsuche-search/v1", limitations);
        }

        static SimplificationSystem external(
            ExternalSymPySimplificationBaseline simplifier,
            List<String> limitations
        ) {
            Objects.requireNonNull(simplifier, "simplifier");
            return new SimplificationSystem(
                simplifier.backendId(),
                simplifier.backendVersion(),
                SystemKind.EXTERNAL_BASELINE,
                null,
                simplifier,
                simplifier.available(),
                simplifier.environmentIdentity(),
                limitations);
        }
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
