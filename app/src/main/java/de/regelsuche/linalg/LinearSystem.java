package de.regelsuche.linalg;

import java.util.Objects;
import java.util.Optional;

/**
 * Linear system {@code A * x = b}. Currently exposes a Gauss-elimination
 * solver for square dense systems — sufficient for the demo flow which
 * touches 2x2 and 3x3 systems.
 */
public final class LinearSystem {
    private final Matrix coefficients;
    private final Vector rhs;

    public LinearSystem(Matrix coefficients, Vector rhs) {
        Objects.requireNonNull(coefficients, "coefficients");
        Objects.requireNonNull(rhs, "rhs");
        if (!coefficients.isSquare()) {
            throw new IllegalArgumentException("LinearSystem expects a square coefficient matrix");
        }
        if (coefficients.rows() != rhs.dimension()) {
            throw new IllegalArgumentException(
                "Coefficient matrix and RHS vector dimensions disagree");
        }
        this.coefficients = coefficients;
        this.rhs = rhs;
    }

    public Matrix coefficients() {
        return coefficients;
    }

    public Vector rhs() {
        return rhs;
    }

    /**
     * Solve via Gauss elimination with partial pivoting. Returns
     * {@link Optional#empty()} if the matrix is (numerically) singular.
     */
    public Optional<Vector> solve() {
        int n = coefficients.rows();
        double[][] augmented = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                augmented[i][j] = coefficients.get(i, j);
            }
            augmented[i][n] = rhs.get(i);
        }
        for (int pivot = 0; pivot < n; pivot++) {
            int max = pivot;
            for (int row = pivot + 1; row < n; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[max][pivot])) {
                    max = row;
                }
            }
            if (Math.abs(augmented[max][pivot]) < 1e-12) {
                return Optional.empty();
            }
            double[] swap = augmented[pivot];
            augmented[pivot] = augmented[max];
            augmented[max] = swap;
            for (int row = pivot + 1; row < n; row++) {
                double factor = augmented[row][pivot] / augmented[pivot][pivot];
                for (int col = pivot; col <= n; col++) {
                    augmented[row][col] -= factor * augmented[pivot][col];
                }
            }
        }
        double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = augmented[row][n];
            for (int col = row + 1; col < n; col++) {
                sum -= augmented[row][col] * x[col];
            }
            x[row] = sum / augmented[row][row];
        }
        return Optional.of(new Vector(x));
    }
}
