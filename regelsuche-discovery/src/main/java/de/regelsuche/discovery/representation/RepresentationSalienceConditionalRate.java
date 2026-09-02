package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.append;

import de.regelsuche.json.JsonWriter;

/** Explicit conditional rate that retains zero denominators. */
public record RepresentationSalienceConditionalRate(
    int numerator,
    int denominator,
    boolean defined,
    int permille
) {
    public RepresentationSalienceConditionalRate {
        if (numerator < 0 || denominator < 0 || numerator > denominator) {
            throw new IllegalArgumentException(
                "conditional-rate counts do not balance"
            );
        }
        boolean expectedDefined = denominator > 0;
        int expectedPermille = expectedDefined
            ? Math.toIntExact((long) numerator * 1_000L / denominator)
            : 0;
        if (defined != expectedDefined || permille != expectedPermille) {
            throw new IllegalArgumentException(
                "conditional-rate value differs from counts"
            );
        }
    }

    public static RepresentationSalienceConditionalRate of(
        int numerator,
        int denominator
    ) {
        return new RepresentationSalienceConditionalRate(
            numerator,
            denominator,
            denominator > 0,
            denominator > 0
                ? Math.toIntExact((long) numerator * 1_000L / denominator)
                : 0
        );
    }

    void appendIdentity(StringBuilder descriptor) {
        append(descriptor, Integer.toString(numerator));
        append(descriptor, Integer.toString(denominator));
        append(descriptor, Boolean.toString(defined));
        append(descriptor, Integer.toString(permille));
    }

    void writeJson(JsonWriter json) {
        json.property("numerator", numerator)
            .property("denominator", denominator)
            .property("defined", defined)
            .property("permille", permille);
    }
}
