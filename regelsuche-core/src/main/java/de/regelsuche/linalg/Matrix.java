package de.regelsuche.linalg;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable real matrix with the operations used by the linalg demo:
 * matrix-vector multiplication, matrix-matrix multiplication (for small
 * dimensions) and a small-system inversion needed by
 * {@link LinearSystem}.
 *
 * <p>Storage is row-major. The class intentionally stays minimal — heavy
 * lifting (e.g. dense factorisations) is out of scope here.</p>
 */
public final class Matrix {
    private final int rows;
    private final int columns;
    private final double[][] entries;

    public Matrix(double[][] entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.length == 0) {
            throw new IllegalArgumentException("Matrix must have at least one row");
        }
        int columns = entries[0].length;
        if (columns == 0) {
            throw new IllegalArgumentException("Matrix must have at least one column");
        }
        for (double[] row : entries) {
            if (row.length != columns) {
                throw new IllegalArgumentException("Inconsistent row lengths");
            }
        }
        this.rows = entries.length;
        this.columns = columns;
        this.entries = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(entries[i], 0, this.entries[i], 0, columns);
        }
    }

    public int rows() {
        return rows;
    }

    public int columns() {
        return columns;
    }

    public double get(int row, int column) {
        return entries[row][column];
    }

    public Vector multiply(Vector vector) {
        Objects.requireNonNull(vector, "vector");
        if (vector.dimension() != columns) {
            throw new IllegalArgumentException(
                "Matrix columns (" + columns + ") do not match vector dimension (" + vector.dimension() + ")");
        }
        double[] result = new double[rows];
        for (int i = 0; i < rows; i++) {
            double sum = 0.0;
            for (int j = 0; j < columns; j++) {
                sum += entries[i][j] * vector.get(j);
            }
            result[i] = sum;
        }
        return new Vector(result);
    }

    public Matrix multiply(Matrix other) {
        Objects.requireNonNull(other, "other");
        if (other.rows != columns) {
            throw new IllegalArgumentException(
                "Matrix multiplication dimension mismatch: " + rows + "x" + columns
                    + " * " + other.rows + "x" + other.columns);
        }
        double[][] product = new double[rows][other.columns];
        for (int i = 0; i < rows; i++) {
            for (int k = 0; k < columns; k++) {
                double aik = entries[i][k];
                if (aik == 0.0) {
                    continue;
                }
                for (int j = 0; j < other.columns; j++) {
                    product[i][j] += aik * other.entries[k][j];
                }
            }
        }
        return new Matrix(product);
    }

    public boolean isSquare() {
        return rows == columns;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Matrix other
            && other.rows == rows
            && other.columns == columns
            && Arrays.deepEquals(this.entries, other.entries);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(entries);
    }
}
