package de.regelsuche.linalg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MatrixVectorTest {

    @Test
    void vectorAdditionAndDotProduct() {
        Vector u = new Vector(1, 2, 3);
        Vector v = new Vector(4, 5, 6);
        assertArrayEquals(new double[]{5, 7, 9}, u.add(v).toArray());
        assertEquals(32.0, u.dot(v));
        assertThrows(IllegalArgumentException.class, () -> u.add(new Vector(1, 2)));
    }

    @Test
    void matrixMultiplicationSmallDimensions() {
        Matrix a = new Matrix(new double[][]{{1, 2}, {3, 4}});
        Matrix b = new Matrix(new double[][]{{5, 6}, {7, 8}});
        Matrix product = a.multiply(b);
        assertEquals(19.0, product.get(0, 0));
        assertEquals(22.0, product.get(0, 1));
        assertEquals(43.0, product.get(1, 0));
        assertEquals(50.0, product.get(1, 1));

        Vector mv = a.multiply(new Vector(1, 1));
        assertArrayEquals(new double[]{3, 7}, mv.toArray());
    }

    @Test
    void linearSystemSolvesSmallSquareCase() {
        // x + y = 3, 2x + 3y = 8 -> x=1, y=2.
        Matrix coefficients = new Matrix(new double[][]{{1, 1}, {2, 3}});
        Vector rhs = new Vector(3, 8);
        Optional<Vector> solution = new LinearSystem(coefficients, rhs).solve();
        assertTrue(solution.isPresent());
        assertArrayEquals(new double[]{1, 2}, solution.get().toArray(), 1e-9);
    }

    @Test
    void singularMatrixYieldsEmptySolution() {
        Matrix singular = new Matrix(new double[][]{{1, 2}, {2, 4}});
        Vector rhs = new Vector(1, 2);
        Optional<Vector> solution = new LinearSystem(singular, rhs).solve();
        assertTrue(solution.isEmpty());
    }
}
