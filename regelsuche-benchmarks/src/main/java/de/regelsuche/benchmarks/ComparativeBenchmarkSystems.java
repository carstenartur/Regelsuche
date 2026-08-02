package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Role;
import de.regelsuche.benchmarks.ComparativeBenchmark.SystemKind;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.solver.ir.SolverBackend;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
     * <p>Exactly one of {@code strategy}, {@code externalSimplifier} and
     * {@code saturationSimplifier} is set: a path-based internal search, an
     * external CAS simplifier, or internal equality saturation. Every
     * competitor kind receives the input expression and the declared case
     * assumptions only.</p>
     */
    record SimplificationSystem(
        String id,
        String version,
        SystemKind kind,
        SearchStrategy strategy,
        ExternalSymPySimplificationBaseline externalSimplifier,
        EqualitySaturationSimplificationBaseline saturationSimplifier,
        boolean available,
        String environmentIdentity,
        List<String> limitations
    ) {
        SimplificationSystem {
            requireText(id, "simplification system id");
            requireText(version, "simplification system version");
            Objects.requireNonNull(kind, "kind");
            long implementations = Stream.of(
                    strategy, externalSimplifier, saturationSimplifier)
                .filter(Objects::nonNull)
                .count();
            if (implementations != 1L) {
                throw new IllegalArgumentException(
                    "exactly one simplification competitor implementation is required");
            }
            requireText(environmentIdentity, "environmentIdentity");
            limitations = clean(limitations);
        }

        /** @return the stable identity of the competitor implementation. */
        String implementationIdentity() {
            if (strategy != null) {
                return "internal:" + strategy.getClass().getName();
            }
            if (saturationSimplifier != null) {
                return "saturation:" + saturationSimplifier.configurationHash();
            }
            return "external:" + externalSimplifier.configurationHash();
        }

        static SimplificationSystem internal(
            String id,
            String version,
            SearchStrategy strategy,
            List<String> limitations
        ) {
            return new SimplificationSystem(
                id, version, SystemKind.REGELSUCHE, strategy, null, null, true,
                "java=21\nsearch-kernel=regelsuche-search/v1", limitations);
        }

        static SimplificationSystem saturation(
            EqualitySaturationSimplificationBaseline simplifier,
            List<String> limitations
        ) {
            Objects.requireNonNull(simplifier, "simplifier");
            return new SimplificationSystem(
                simplifier.backendId(),
                simplifier.backendVersion(),
                SystemKind.REGELSUCHE,
                null,
                null,
                simplifier,
                true,
                simplifier.environmentIdentity(),
                limitations);
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
                null,
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
