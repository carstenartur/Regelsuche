package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Exact symmetric Schur tests and source-bound monomial Gram witnesses. */
public final class GramPositivity {
    private GramPositivity() {}
    public enum Classification { POSITIVE_DEFINITE, POSITIVE_SEMIDEFINITE, NOT_PSD, INVALID, UNAVAILABLE }
    public record MatrixResult(Classification classification, String reason, long work) {}
    public record Certificate(SparsePolynomial source, List<SparsePolynomial.Exponent> basis,
                              List<List<ExactRational>> matrix) {
        public Certificate {
            Objects.requireNonNull(source);
            basis = List.copyOf(basis);
            matrix = matrix.stream().map(List::copyOf).toList();
        }
    }
    public record PolynomialVerification(Optional<BernsteinPositivity.Claim> claim, String reason, long work) {
        public boolean valid() { return claim.isPresent(); }
    }

    public static MatrixResult checkMatrix(List<List<ExactRational>> matrix, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new PositivityWork(limits, cancellation);
        try {
            return classify(matrix, work);
        } catch (PositivityWork.Stop stop) {
            return new MatrixResult(Classification.UNAVAILABLE, stop.getMessage(), work.used);
        }
    }

    private static MatrixResult classify(List<List<ExactRational>> matrix, PositivityWork work) {
        int n = matrix.size();
        work.terms((long) n * n);
        if (n == 0) return new MatrixResult(Classification.INVALID, "EMPTY_MATRIX", work.used);
        work.charge((long) n * n);
        var a = new ExactRational[n][];
        int rowIndex = 0;
        for (var row : matrix) {
            if (row == null || row.size() != n) return new MatrixResult(Classification.INVALID, "MATRIX_SHAPE", work.used);
            a[rowIndex] = new ExactRational[n];
            int col = 0;
            for (var value : row) {
                if (value == null) return new MatrixResult(Classification.INVALID, "NULL_ENTRY", work.used);
                a[rowIndex][col] = work.check(value);
                if (col < rowIndex && !value.equals(a[col][rowIndex])) {
                    return new MatrixResult(Classification.INVALID, "ASYMMETRIC_MATRIX", work.used);
                }
                col++;
            }
            rowIndex++;
        }
        boolean singular = false;
        for (int k = 0; k < n; k++) {
            work.charge(1);
            var pivot = a[k][k];
            if (pivot.signum() < 0) return new MatrixResult(Classification.NOT_PSD, "NEGATIVE_PIVOT", work.used);
            if (pivot.isZero()) {
                singular = true;
                for (int i = k+1; i < n; i++) {
                    work.charge(1);
                    if (!a[k][i].isZero()) {
                        return new MatrixResult(Classification.NOT_PSD, "NONZERO_ZERO_PIVOT_ROW", work.used);
                    }
                }
                continue;
            }
            // Only the upper triangle of the trailing symmetric Schur complement is needed.
            for (int i = k+1; i < n; i++) {
                var multiplier = work.div(a[k][i], pivot);
                for (int j = i; j < n; j++) {
                    a[i][j] = work.sub(a[i][j], work.mul(multiplier, a[k][j]));
                }
            }
        }
        work.charge(0);
        return new MatrixResult(singular ? Classification.POSITIVE_SEMIDEFINITE : Classification.POSITIVE_DEFINITE,
            "VERIFIED", work.used);
    }

    // Adding two int exponents can exceed Integer.MAX_VALUE even when their terms eventually cancel.
    private record Product(long x, long y) {}

    public static PolynomialVerification verifyPolynomial(SparsePolynomial source, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new PositivityWork(limits, cancellation);
        try {
            if (!work.samePolynomial(source, certificate.source())) return rejected("SOURCE_MISMATCH", work);
            var basis = certificate.basis();
            if (basis.size() != certificate.matrix().size()) return rejected("BASIS_DIMENSION", work);
            var matrix = classify(certificate.matrix(), work);
            if (matrix.classification() != Classification.POSITIVE_DEFINITE
                    && matrix.classification() != Classification.POSITIVE_SEMIDEFINITE) {
                return rejected(matrix.reason(), work);
            }
            var expanded = new HashMap<Product, ExactRational>();
            for (int i = 0; i < basis.size(); i++) {
                var left = basis.get(i);
                if (source.degrees().size() == 1 && left.y() != 0) return rejected("BASIS_DIMENSION", work);
                for (int j = 0; j < basis.size(); j++) {
                    work.charge(1);
                    var entry = certificate.matrix().get(i).get(j);
                    if (entry.isZero()) continue;
                    var right = basis.get(j);
                    var key = new Product((long) left.x()+right.x(), (long) left.y()+right.y());
                    var sum = work.add(expanded.getOrDefault(key, ExactRational.ZERO), entry);
                    if (sum.isZero()) expanded.remove(key);
                    else expanded.put(key, sum);
                }
            }
            if (expanded.size() != source.terms().size()) return rejected("GRAM_IDENTITY", work);
            for (var term : source.terms().entrySet()) {
                work.charge(1);
                var key = new Product(term.getKey().x(), term.getKey().y());
                if (!term.getValue().equals(expanded.get(key))) return rejected("GRAM_IDENTITY", work);
            }
            work.charge(0);
            return new PolynomialVerification(Optional.of(BernsteinPositivity.Claim.NONNEGATIVE), "VERIFIED", work.used);
        } catch (PositivityWork.Stop stop) {
            return rejected(stop.getMessage(), work);
        }
    }

    private static PolynomialVerification rejected(String reason, PositivityWork work) {
        return new PolynomialVerification(Optional.empty(), reason, work.used);
    }
}
