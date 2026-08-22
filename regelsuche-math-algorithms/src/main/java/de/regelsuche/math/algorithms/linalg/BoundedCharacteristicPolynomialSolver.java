package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact bounded determinant solver for the shifted operator {@code A-lambda I}.
 *
 * <p>The initial implementation uses deterministic Laplace expansion. It is
 * intentionally dimension-bounded and work-accounted rather than pretending to
 * be a scalable general determinant backend. The result makes the eigenproblem
 * consequence {@code det(A-lambda I)=0} executable instead of leaving it as a
 * structure label.</p>
 */
public final class BoundedCharacteristicPolynomialSolver {
    public static final String SOLVER_ID =
        "bounded-characteristic-polynomial/laplace/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.characteristic-polynomial-certificate/v1";
    public static final int DEFAULT_MAX_DIMENSION = 5;

    private final int maxDimension;

    public BoundedCharacteristicPolynomialSolver() {
        this(DEFAULT_MAX_DIMENSION);
    }

    public BoundedCharacteristicPolynomialSolver(int maxDimension) {
        if (maxDimension < 1) {
            throw new IllegalArgumentException(
                "maxDimension must be positive");
        }
        this.maxDimension = maxDimension;
    }

    public Result solve(EigenproblemRepresentation source, Budget budget) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        if (source.dimension() > maxDimension) {
            return Result.withoutPolynomial(
                Status.DIMENSION_UNSUPPORTED,
                work.ledger(),
                "EIGENPROBLEM_DIMENSION_EXCEEDS_BOUND");
        }
        try {
            Polynomial polynomial = determinant(
                source.shiftedOperator(),
                work);
            Certificate certificate = certificate(source, polynomial);
            return Result.solved(
                new CharacteristicPolynomial(
                    source.eigenvalueParameter(),
                    polynomial,
                    polynomial.toCanonicalString() + " = 0"),
                certificate,
                work.ledger());
        } catch (BudgetExceeded exception) {
            return Result.withoutPolynomial(
                Status.BUDGET_INCONCLUSIVE,
                work.ledger(),
                "CHARACTERISTIC_POLYNOMIAL_WORK_BUDGET_EXHAUSTED");
        }
    }

    public boolean verify(EigenproblemRepresentation source, Result result) {
        if (source == null
                || result == null
                || result.status() != Status.SOLVED) {
            return false;
        }
        try {
            return solve(
                source,
                new Budget(result.work().configuredWorkUnits()))
                .equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Polynomial determinant(
        PolynomialMatrix matrix,
        WorkCounter work
    ) {
        int dimension = matrix.rows();
        if (dimension != matrix.columns()) {
            throw new IllegalArgumentException(
                "determinant requires a square matrix");
        }
        work.consume();
        if (dimension == 1) {
            return matrix.get(0, 0);
        }
        Polynomial result = Polynomial.zero();
        for (int column = 0; column < dimension; column++) {
            work.consume();
            Polynomial term = matrix.get(0, column).multiply(
                determinant(minor(matrix, 0, column, work), work));
            if ((column & 1) == 1) {
                term = term.multiply(Rational.NEGATIVE_ONE);
            }
            result = result.add(term);
        }
        return result;
    }

    private static PolynomialMatrix minor(
        PolynomialMatrix matrix,
        int removedRow,
        int removedColumn,
        WorkCounter work
    ) {
        List<List<Polynomial>> rows = new ArrayList<>(matrix.rows() - 1);
        for (int row = 0; row < matrix.rows(); row++) {
            if (row == removedRow) {
                continue;
            }
            List<Polynomial> retained = new ArrayList<>(
                matrix.columns() - 1);
            for (int column = 0; column < matrix.columns(); column++) {
                if (column == removedColumn) {
                    continue;
                }
                work.consume();
                retained.add(matrix.get(row, column));
            }
            rows.add(List.copyOf(retained));
        }
        return new PolynomialMatrix(rows);
    }

    private static Certificate certificate(
        EigenproblemRepresentation source,
        Polynomial polynomial
    ) {
        String sourceHash = sourceHash(source);
        String canonical = polynomial.toCanonicalString();
        String payload = String.join("\n",
            "schema=" + CERTIFICATE_SCHEMA,
            "solver=" + SOLVER_ID,
            "source=" + sourceHash,
            "dimension=" + source.dimension(),
            "eigenvalue=" + source.eigenvalueParameter(),
            "polynomial=" + canonical,
            "equation=" + canonical + " = 0");
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            sourceHash,
            source.dimension(),
            source.eigenvalueParameter(),
            canonical,
            canonical + " = 0",
            sha256(payload));
    }

    private static String sourceHash(EigenproblemRepresentation source) {
        StringBuilder payload = new StringBuilder();
        append(payload, Integer.toString(source.dimension()));
        append(payload, source.eigenvalueParameter());
        source.vectorCoordinates().forEach(value -> append(payload, value));
        for (List<Polynomial> row : source.shiftedOperator().entries()) {
            append(payload, Integer.toString(row.size()));
            row.stream().map(Polynomial::toCanonicalString)
                .forEach(value -> append(payload, value));
        }
        source.requiredAssumptions().forEach(value -> append(payload, value));
        return sha256(payload.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        SOLVED,
        DIMENSION_UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        INVALID_CERTIFICATE
    }

    public record Budget(int maxWorkUnits) {
        public static final Budget DEFAULT = new Budget(100_000);

        public Budget {
            if (maxWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "maxWorkUnits must not be negative");
            }
        }
    }

    public record CharacteristicPolynomial(
        String eigenvalueParameter,
        Polynomial polynomial,
        String singularityEquation
    ) {
        public CharacteristicPolynomial {
            if (eigenvalueParameter == null
                    || eigenvalueParameter.isBlank()
                    || singularityEquation == null
                    || singularityEquation.isBlank()) {
                throw new IllegalArgumentException(
                    "characteristic polynomial text fields are required");
            }
            eigenvalueParameter = eigenvalueParameter.trim();
            polynomial = Objects.requireNonNull(polynomial, "polynomial");
            singularityEquation = singularityEquation.trim();
            if (!singularityEquation.equals(
                    polynomial.toCanonicalString() + " = 0")) {
                throw new IllegalArgumentException(
                    "singularity equation must match polynomial");
            }
        }
    }

    public record Certificate(
        String schema,
        String solverId,
        String sourceHash,
        int dimension,
        String eigenvalueParameter,
        String canonicalPolynomial,
        String singularityEquation,
        String contentHash
    ) {
        public Certificate {
            if (schema == null || schema.isBlank()
                    || solverId == null || solverId.isBlank()
                    || sourceHash == null || sourceHash.isBlank()
                    || eigenvalueParameter == null
                    || eigenvalueParameter.isBlank()
                    || canonicalPolynomial == null
                    || canonicalPolynomial.isBlank()
                    || singularityEquation == null
                    || singularityEquation.isBlank()
                    || contentHash == null || contentHash.isBlank()
                    || dimension < 1) {
                throw new IllegalArgumentException(
                    "certificate fields are invalid");
            }
            if (!singularityEquation.equals(
                    canonicalPolynomial + " = 0")) {
                throw new IllegalArgumentException(
                    "certificate equation and polynomial disagree");
            }
        }
    }

    public record Result(
        Status status,
        Optional<CharacteristicPolynomial> characteristicPolynomial,
        Optional<Certificate> certificate,
        WorkLedger work,
        String detailCode
    ) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            characteristicPolynomial = Objects.requireNonNull(
                characteristicPolynomial,
                "characteristicPolynomial");
            certificate = Objects.requireNonNull(certificate, "certificate");
            work = Objects.requireNonNull(work, "work");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            detailCode = detailCode.trim();
            boolean payload = characteristicPolynomial.isPresent()
                && certificate.isPresent();
            if ((status == Status.SOLVED) != payload
                    || characteristicPolynomial.isPresent()
                        != certificate.isPresent()) {
                throw new IllegalArgumentException(
                    "only SOLVED results may retain complete payloads");
            }
        }

        private static Result solved(
            CharacteristicPolynomial polynomial,
            Certificate certificate,
            WorkLedger work
        ) {
            return new Result(
                Status.SOLVED,
                Optional.of(polynomial),
                Optional.of(certificate),
                work,
                "CHARACTERISTIC_POLYNOMIAL_COMPUTED");
        }

        private static Result withoutPolynomial(
            Status status,
            WorkLedger work,
            String detailCode
        ) {
            if (status == Status.SOLVED) {
                throw new IllegalArgumentException(
                    "solved result requires polynomial payload");
            }
            return new Result(
                status,
                Optional.empty(),
                Optional.empty(),
                work,
                detailCode);
        }
    }

    private static final class WorkCounter {
        private final int configured;
        private int consumed;

        private WorkCounter(int configured) {
            this.configured = configured;
        }

        private void consume() {
            if (consumed >= configured) {
                throw new BudgetExceeded();
            }
            consumed++;
        }

        private WorkLedger ledger() {
            return WorkLedger.of(configured, consumed);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
