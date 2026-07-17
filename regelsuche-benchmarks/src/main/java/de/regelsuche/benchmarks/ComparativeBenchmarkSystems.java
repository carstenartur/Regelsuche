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
