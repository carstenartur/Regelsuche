package de.regelsuche.math.algorithms.linalg;

import java.util.List;
import java.util.Objects;

/** In-memory semantic proof. Local operation indices refer to the ordered source row map. */
public record ExactLinearSolutionCertificate(String schema, List<Part> parts) {
    public static final String SCHEMA = "regelsuche.exact-linear-solution-certificate/v1";
    public ExactLinearSolutionCertificate {
        Objects.requireNonNull(schema);
        parts = List.copyOf(parts);
    }
    public record Part(List<Integer> sourceRows, List<ExactRrefReduction.RowOperation> operations) {
        public Part {
            sourceRows = List.copyOf(sourceRows);
            operations = List.copyOf(operations);
        }
    }
}
