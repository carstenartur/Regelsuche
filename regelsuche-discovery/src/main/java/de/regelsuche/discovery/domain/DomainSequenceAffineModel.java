package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.FiniteDifferenceCandidate;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.LinearRecurrenceCandidate;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Fixed, bounded projections of the two retained sequence models into the existing affine checker. */
final class DomainSequenceAffineModel {
    static final String SCHEMA = "regelsuche.sequence-affine-model/v1";
    static final String DIFFERENCES = "regelsuche.finite-difference-affine-model/v1";
    static final String RECURRENCE = "regelsuche.linear-recurrence-companion-model/v1";
    private static final String ASSUMPTION = "The selected candidate defines every subsequent model step. "
        + "Finite observations do not establish that an unspecified infinite source sequence follows this model.";

    private final Transition transition;
    private final List<Rational> initial;
    private final List<Long> retained;
    private final ObjectNode json;

    private DomainSequenceAffineModel(String contract, int order, List<List<Rational>> matrix,
            List<Rational> offset, List<Rational> initial, List<Long> observed, List<Long> holdout) {
        this.transition = new Transition(new ExactMatrix(matrix), new ExactVector(offset));
        this.initial = List.copyOf(initial);
        var terms = new ArrayList<>(observed);
        terms.addAll(holdout);
        this.retained = List.copyOf(terms);
        this.json = DomainExportWorkspace.JSON.createObjectNode().put("schema", SCHEMA)
            .put("adapterContract", contract).put("sourceOrder", order).put("dimension", initial.size());
        ArrayNode coordinates = json.putArray("coordinates");
        for (int index = 0; index < initial.size(); index++) coordinates.add("x" + index);
        ArrayNode rows = json.putArray("linear");
        matrix.forEach(row -> rationals(rows.addArray(), row));
        rationals(json.putArray("offset"), offset);
        rationals(json.putArray("initialState"), initial);
        json.put("observation", "x0").put("initialIndex", 0);
        json.putArray("assumptions").add(ASSUMPTION);
        json.put("contentHash", DomainCanonical.sha256(DomainExportWorkspace.write(json)));
    }

    /** Rejects dimensions and rational widths before conversion or mathematical backend invocation. */
    static void requireSupported(Object candidate) {
        int order;
        if (candidate instanceof FiniteDifferenceCandidate value) {
            order = value.order();
        } else if (candidate instanceof LinearRecurrenceCandidate value) {
            order = value.model().order();
            if (order > 3) throw new UnsupportedModel("MODEL_DIMENSION_EXCEEDS_THREE");
            for (var coefficient : value.model().coefficients()) {
                if (coefficient.numerator().bitLength() > 128 || coefficient.denominator().bitLength() > 128) {
                    throw new UnsupportedModel("MODEL_COEFFICIENT_EXCEEDS_128_BITS");
                }
            }
        } else throw new UnsupportedModel("NO_AFFINE_PROJECTION_FOR_SELECTED_TYPE");
        if (order < 1 || order > 3) throw new UnsupportedModel("MODEL_DIMENSION_EXCEEDS_THREE");
    }

    /** Projects a replayed candidate. Projection alone grants no evidence about agreement with retained terms. */
    static DomainSequenceAffineModel from(Object candidate) {
        requireSupported(candidate);
        if (candidate instanceof FiniteDifferenceCandidate value) {
            int dimension = value.order();
            var matrix = new ArrayList<List<Rational>>();
            var offset = new ArrayList<Rational>();
            for (int row = 0; row < dimension; row++) {
                var entries = new ArrayList<Rational>();
                for (int column = 0; column < dimension; column++) {
                    entries.add(column == row || column == row + 1 ? Rational.ONE : Rational.ZERO);
                }
                matrix.add(List.copyOf(entries));
                offset.add(row == dimension - 1 ? Rational.of(value.initialDifferences().get(dimension)) : Rational.ZERO);
            }
            return new DomainSequenceAffineModel(DIFFERENCES, dimension, matrix, offset,
                value.initialDifferences().subList(0, dimension).stream().map(Rational::of).toList(),
                value.observed(), value.holdout());
        }
        var value = (LinearRecurrenceCandidate) candidate;
        int dimension = value.model().order();
        var matrix = new ArrayList<List<Rational>>();
        for (int row = 0; row < dimension; row++) {
            var entries = new ArrayList<Rational>();
            for (int column = 0; column < dimension; column++) {
                if (row == dimension - 1) {
                    var coefficient = value.model().coefficients().get(dimension - 1 - column);
                    entries.add(new Rational(coefficient.numerator(), coefficient.denominator()));
                } else entries.add(column == row + 1 ? Rational.ONE : Rational.ZERO);
            }
            matrix.add(List.copyOf(entries));
        }
        return new DomainSequenceAffineModel(RECURRENCE, dimension, matrix,
            java.util.Collections.nCopies(dimension, Rational.ZERO),
            value.observed().subList(0, dimension).stream().map(Rational::of).toList(),
            value.observed(), value.holdout());
    }

    Transition transition() { return transition; }
    Polynomial observation() { return Polynomial.variable("x0"); }
    List<Rational> initialState() { return initial; }
    List<Long> retained() { return retained; }
    ObjectNode json() { return json.deepCopy(); }
    String contentHash() { return json.path("contentHash").asText(); }

    List<BigInteger> indices() {
        var indices = new ArrayList<BigInteger>();
        for (int index = 0; index < retained.size(); index++) indices.add(BigInteger.valueOf(index));
        if (retained.size() <= 100) indices.add(BigInteger.valueOf(100));
        return List.copyOf(indices);
    }

    static void rationals(ArrayNode target, List<Rational> values) { values.forEach(value -> target.add(value.toString())); }

    static final class UnsupportedModel extends RuntimeException {
        UnsupportedModel(String message) { super(message); }
    }
}
