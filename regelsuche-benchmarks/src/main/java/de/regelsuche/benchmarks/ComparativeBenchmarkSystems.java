package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.solver.ir.SolverBackend;
import java.util.List;
import java.util.Objects;

/** Runtime system descriptors used by the initial comparative benchmark slice. */
final class ComparativeBenchmarkSystems {
    private ComparativeBenchmarkSystems() {
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
     * <p>Exactly one of {@code strategy} and {@code externalSimplifier} is set.
     * Both competitor kinds receive the input expression only.</p>
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

        static SimplificationSystem internal(
            String id,
            String version,
            SearchStrategy strategy,
            List<String> limitations
        ) {
            return new SimplificationSystem(
                id, version, SystemKind.REGELSUCHE, strategy, null, true,
                "java=21\nsearch-kernel=regelsuche-search/v1", limitations);
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
