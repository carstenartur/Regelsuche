package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.representation.RepresentationBridge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/** Discharges a complete ordered matrix-equality obligation, never a sampled equality. */
public final class MatrixRepresentationBridge implements
        RepresentationBridge<MatrixRepresentationBridge.Obligation,
            MatrixRepresentationBridge.View, MatrixRepresentationBridge.Certificate> {
    public static final String SCHEMA = "regelsuche.ordered-matrix-bridge/v1";
    public static final Relation RELATION = Relation.LINEAR_MAP_REPRESENTATION_EQUIVALENCE;

    @Override
    public Result<View, Certificate> analyze(Obligation source, Budget budget) {
        ExactMatrixAlgebra.Work work = new ExactMatrixAlgebra.Work(budget.maxWorkUnits());
        ExactMatrixAlgebra algebra = new ExactMatrixAlgebra(work);
        try {
            PolynomialMatrix expected = algebra.checked(source.coefficients());
            PolynomialMatrix actual = algebra.evaluate(source.expression());
            if (!algebra.equal(expected, actual)) {
                return Result.withoutRepresentation(Status.NOT_APPLICABLE, work.ledger(), "ORDERED_MATRIX_EQUALITY_REJECTED");
            }
            View view = new View(source, capabilities(source.expression()));
            return Result.represented(view, new Certificate(SCHEMA, hash(canonical(source))),
                RELATION, work.ledger(), "COMPLETE_ORDERED_MATRIX_EQUALITY_VERIFIED");
        } catch (ExactMatrixAlgebra.Exhausted exception) {
            return Result.withoutRepresentation(Status.BUDGET_INCONCLUSIVE, work.ledger(), "MATRIX_OBLIGATION_WORK_EXHAUSTED");
        } catch (ExactMatrixAlgebra.Unsupported exception) {
            return Result.withoutRepresentation(Status.DOMAIN_UNSUPPORTED, work.ledger(), exception.getMessage());
        }
    }

    @Override
    public boolean verify(Obligation source, Result<View, Certificate> result) {
        return source != null && result != null && result.represented()
            && analyze(source, new Budget(result.work().configuredWorkUnits())).equals(result);
    }

    private static List<String> capabilities(ExactMatrixExpression expression) {
        return switch (expression) {
            case ExactMatrixExpression.Product ignored -> List.of("STAGED_MATRIX_VECTOR_APPLICATION");
            case ExactMatrixExpression.Sum s -> List.of(s.left() instanceof ExactMatrixExpression.Identity
                || s.right() instanceof ExactMatrixExpression.Identity
                    ? "IDENTITY_PLUS_OPERATOR_APPLICATION" : "ORDERED_DISTRIBUTIVE_APPLICATION");
            case ExactMatrixExpression.Inverse ignored -> List.of("EXACT_INVERSE_APPLICATION");
            case ExactMatrixExpression.BlockDiagonal ignored -> List.of("INDEPENDENT_BLOCK_APPLICATION");
            case ExactMatrixExpression.Mapped m -> capabilities(m.expression());
            default -> List.of();
        };
    }

    public record Obligation(PolynomialMatrix coefficients, List<String> sourceRows,
            List<String> coordinates, String formationRoot, ExactMatrixExpression expression,
            List<String> provenance) {
        public Obligation {
            Objects.requireNonNull(coefficients, "coefficients");
            Objects.requireNonNull(expression, "expression");
            sourceRows = List.copyOf(sourceRows);
            coordinates = List.copyOf(coordinates);
            provenance = List.copyOf(provenance);
            if (sourceRows.size() != coefficients.rows() || coordinates.size() != coefficients.columns()
                    || coordinates.stream().distinct().count() != coordinates.size()
                    || formationRoot == null || formationRoot.isBlank()) {
                throw new IllegalArgumentException("matrix obligation must retain its formation and ordered mappings");
            }
        }
    }

    public record View(Obligation obligation, List<String> newlyUnlockedCapabilities) {
        public View {
            Objects.requireNonNull(obligation, "obligation");
            newlyUnlockedCapabilities = List.copyOf(newlyUnlockedCapabilities);
        }
    }

    public record Certificate(String schema, String contentHash) {
        public Certificate {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    /** A concrete downstream principal: staged composition must replay every coordinate. */
    public static final class VectorReplay implements RepresentationBridge<View, List<Polynomial>, Certificate> {
        @Override
        public Result<List<Polynomial>, Certificate> analyze(View source, Budget budget) {
            ExactMatrixAlgebra.Work work = new ExactMatrixAlgebra.Work(budget.maxWorkUnits());
            ExactMatrixAlgebra algebra = new ExactMatrixAlgebra(work);
            try {
                Obligation obligation = source.obligation();
                List<Polynomial> coordinates = obligation.coordinates().stream().map(Polynomial::variable).toList();
                List<Polynomial> staged = algebra.apply(obligation.expression(), coordinates);
                List<Polynomial> direct = algebra.apply(obligation.coefficients(), coordinates);
                if (!staged.equals(direct)) {
                    return Result.withoutRepresentation(Status.INVALID_CERTIFICATE, work.ledger(), "STAGED_VECTOR_REPLAY_REJECTED");
                }
                String material = canonical(obligation) + "\n" + staged.stream()
                    .map(Polynomial::toCanonicalString).toList();
                return Result.represented(staged, new Certificate("regelsuche.ordered-vector-replay/v1", hash(material)),
                    RELATION, work.ledger(), "COMPLETE_STAGED_VECTOR_REPLAY_VERIFIED");
            } catch (ExactMatrixAlgebra.Exhausted exception) {
                return Result.withoutRepresentation(Status.BUDGET_INCONCLUSIVE, work.ledger(), "VECTOR_REPLAY_WORK_EXHAUSTED");
            } catch (ExactMatrixAlgebra.Unsupported exception) {
                return Result.withoutRepresentation(Status.DOMAIN_UNSUPPORTED, work.ledger(), exception.getMessage());
            }
        }

        @Override
        public boolean verify(View source, Result<List<Polynomial>, Certificate> result) {
            return source != null && result != null && result.represented()
                && analyze(source, new Budget(result.work().configuredWorkUnits())).equals(result);
        }
    }

    public static String canonical(Obligation source) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA).property("relation", RELATION.name())
            .property("formationRoot", source.formationRoot())
            .stringArray("sourceRows", source.sourceRows()).stringArray("coordinates", source.coordinates())
            .stringArray("provenance", source.provenance());
        writeMatrix(writer, "coefficients", source.coefficients());
        writer.object("expression", w -> writeExpression(w, source.expression()));
        return writer.endObject().toString();
    }

    public static void writeExpression(JsonWriter writer, ExactMatrixExpression expression) {
        writer.property("display", expression.display());
        switch (expression) {
            case ExactMatrixExpression.Matrix m -> {
                writer.property("kind", "MATRIX").property("name", m.name());
                writeMatrix(writer, "entries", m.value());
            }
            case ExactMatrixExpression.Identity i -> writer.property("kind", "IDENTITY").property("dimension", i.dimension());
            case ExactMatrixExpression.Product p -> writeBinary(writer, "ORDERED_PRODUCT", p.left(), p.right());
            case ExactMatrixExpression.Sum s -> writeBinary(writer, "SUM", s.left(), s.right());
            case ExactMatrixExpression.Inverse i -> {
                writer.property("kind", "INVERSE").object("operand", w -> writeExpression(w, i.operand()));
                writeMatrix(writer, "inverseWitness", i.witness());
            }
            case ExactMatrixExpression.BlockDiagonal b -> writer.property("kind", "BLOCK_DIAGONAL")
                .array("blocks", w -> b.blocks().forEach(block -> w.objectValue(v -> writeExpression(v, block))));
            case ExactMatrixExpression.Mapped m -> writer.property("kind", "RESTORE_SOURCE_ORDER")
                .array("rows", w -> m.rows().forEach(w::value))
                .array("columns", w -> m.columns().forEach(w::value))
                .object("expression", w -> writeExpression(w, m.expression()));
        }
    }

    private static void writeBinary(JsonWriter writer, String kind, ExactMatrixExpression left, ExactMatrixExpression right) {
        writer.property("kind", kind).object("left", w -> writeExpression(w, left))
            .object("right", w -> writeExpression(w, right));
    }

    public static void writeMatrix(JsonWriter writer, String key, PolynomialMatrix matrix) {
        writer.array(key, w -> matrix.entries().forEach(row -> w.arrayValue(r ->
            row.forEach(p -> r.value(p.toCanonicalString())))));
    }

    public static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
