package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical terminal consequence shared by independent exact system-solving
 * routes. It intentionally contains no matrix- or scalar-elimination lineage;
 * route-specific evidence must prove how the consequence was obtained.
 */
public record ExactLinearSolutionConsequence(
    List<String> variables,
    SolutionClassification classification,
    Optional<ExactVector> particularSolution,
    List<ExactVector> nullspaceBasis,
    Optional<Rational> normalizedContradiction
) {
    public ExactLinearSolutionConsequence {
        variables = normalizedVariables(variables);
        classification = Objects.requireNonNull(
            classification,
            "classification");
        particularSolution = Objects.requireNonNull(
            particularSolution,
            "particularSolution");
        nullspaceBasis = List.copyOf(Objects.requireNonNull(
            nullspaceBasis,
            "nullspaceBasis"));
        normalizedContradiction = Objects.requireNonNull(
            normalizedContradiction,
            "normalizedContradiction");

        int dimension = variables.size();
        particularSolution.ifPresent(vector ->
            requireDimension(vector, dimension, "particularSolution"));
        nullspaceBasis.forEach(vector ->
            requireDimension(vector, dimension, "nullspaceBasis"));

        switch (classification) {
            case UNIQUE -> {
                if (particularSolution.isEmpty()
                        || !nullspaceBasis.isEmpty()
                        || normalizedContradiction.isPresent()) {
                    throw new IllegalArgumentException(
                        "unique consequence requires one solution only");
                }
            }
            case UNDERDETERMINED -> {
                if (particularSolution.isEmpty()
                        || nullspaceBasis.isEmpty()
                        || normalizedContradiction.isPresent()) {
                    throw new IllegalArgumentException(
                        "underdetermined consequence requires affine basis");
                }
            }
            case INCONSISTENT -> {
                if (particularSolution.isPresent()
                        || !nullspaceBasis.isEmpty()
                        || normalizedContradiction.isEmpty()
                        || !normalizedContradiction.orElseThrow().isOne()) {
                    throw new IllegalArgumentException(
                        "inconsistent consequence requires normalized 0=1 witness");
                }
            }
        }
    }

    public static ExactLinearSolutionConsequence fromRref(
        ExactRrefReduction reduction
    ) {
        Objects.requireNonNull(reduction, "reduction");
        return switch (reduction.solutionClassification()) {
            case UNIQUE, UNDERDETERMINED ->
                new ExactLinearSolutionConsequence(
                    reduction.variables(),
                    reduction.solutionClassification(),
                    reduction.particularSolution(),
                    reduction.nullspaceBasis(),
                    Optional.empty());
            case INCONSISTENT -> new ExactLinearSolutionConsequence(
                reduction.variables(),
                SolutionClassification.INCONSISTENT,
                Optional.empty(),
                List.of(),
                Optional.of(Rational.ONE));
        };
    }

    public List<String> canonicalLines() {
        List<String> lines = new ArrayList<>();
        lines.add("classification=" + classification.name());
        lines.add("variables=" + String.join(",", variables));
        particularSolution.ifPresent(vector ->
            lines.add("particular=" + canonicalVector(vector)));
        for (int index = 0; index < nullspaceBasis.size(); index++) {
            lines.add("basis[" + index + "]="
                + canonicalVector(nullspaceBasis.get(index)));
        }
        normalizedContradiction.ifPresent(value ->
            lines.add("contradiction=0=" + value));
        return List.copyOf(lines);
    }

    private static String canonicalVector(ExactVector vector) {
        return vector.values().stream()
            .map(Rational::toString)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<String> normalizedVariables(List<String> values) {
        Objects.requireNonNull(values, "variables");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("variables must not be empty");
        }
        List<String> normalized = values.stream().map(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "variable names must not be blank");
            }
            return value.trim();
        }).toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(
                "variable names must be unique");
        }
        return normalized;
    }

    private static void requireDimension(
        ExactVector vector,
        int dimension,
        String field
    ) {
        Objects.requireNonNull(vector, field);
        if (vector.dimension() != dimension) {
            throw new IllegalArgumentException(
                field + " dimension disagrees with variables");
        }
    }
}
