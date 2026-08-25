package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic Berlekamp kernel construction over one declared prime field. */
final class BerlekampKernel {
    static final String METHOD_ID =
        "regelsuche.berlekamp-kernel/v1";

    private BerlekampKernel() {
    }

    static Kernel compute(
        UnivariatePolynomialView<BigInteger> source,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        if (source.isZero() || source.isConstant()) {
            throw new IllegalArgumentException(
                "Berlekamp kernel requires a nonconstant polynomial");
        }
        if (!field.isOne(source.leadingCoefficient())) {
            throw new IllegalArgumentException(
                "Berlekamp kernel requires a monic polynomial");
        }
        int dimension = source.degree();
        BigInteger[][] matrix = matrix(
            source,
            field,
            work);
        Nullspace nullspace = nullspace(
            matrix,
            source,
            field,
            work);
        verify(
            matrix,
            nullspace.basis(),
            field,
            work);

        StringBuilder material = new StringBuilder(METHOD_ID);
        AlgorithmEvidence.append(material, field.id());
        AlgorithmEvidence.append(
            material,
            source.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            Integer.toString(dimension));
        nullspace.pivotColumns().forEach(column ->
            AlgorithmEvidence.append(
                material,
                "pivot=" + column));
        nullspace.basis().forEach(vector ->
            AlgorithmEvidence.append(
                material,
                vector.canonicalMaterial()));
        return new Kernel(
            nullspace.basis(),
            nullspace.basis().size(),
            AlgorithmEvidence.sha256(material.toString()));
    }

    private static BigInteger[][] matrix(
        UnivariatePolynomialView<BigInteger> source,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        int dimension = source.degree();
        BigInteger[][] result = new BigInteger[dimension][dimension];
        for (int row = 0; row < dimension; row++) {
            for (int column = 0; column < dimension; column++) {
                result[row][column] = field.zero();
            }
        }

        UnivariatePolynomialView<BigInteger> x =
            FiniteFieldPolynomialArithmetic.x(source.ring());
        UnivariatePolynomialView<BigInteger> xToPrime =
            FiniteFieldPolynomialArithmetic.powMod(
                x,
                field.characteristic(),
                source,
                field,
                work,
                "berlekamp.matrix.x-to-prime");
        UnivariatePolynomialView<BigInteger> columnPolynomial =
            UnivariatePolynomialView.one(source.ring());

        for (int column = 0;
                column < dimension;
                column++) {
            work.consume("berlekamp.matrix.columns", 1);
            for (int row = 0; row < dimension; row++) {
                work.consume(
                    "berlekamp.matrix.coefficient-copies",
                    1);
                result[row][column] =
                    columnPolynomial.coefficient(row);
            }
            work.consume(
                "berlekamp.matrix.identity-subtractions",
                1);
            result[column][column] = field.subtract(
                result[column][column],
                field.one());
            if (column + 1 < dimension) {
                columnPolynomial =
                    FiniteFieldPolynomialArithmetic.multiplyMod(
                        columnPolynomial,
                        xToPrime,
                        source,
                        field,
                        work,
                        "berlekamp.matrix.next-column");
            }
        }
        return result;
    }

    private static Nullspace nullspace(
        BigInteger[][] matrix,
        UnivariatePolynomialView<BigInteger> source,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        int rows = matrix.length;
        int columns = rows;
        BigInteger[][] reduced = copy(matrix);
        ArrayList<Integer> pivotColumns = new ArrayList<>();
        int pivotRow = 0;

        for (int column = 0;
                column < columns && pivotRow < rows;
                column++) {
            int selected = selectPivot(
                reduced,
                pivotRow,
                column,
                field,
                work);
            if (selected < 0) {
                continue;
            }
            if (selected != pivotRow) {
                BigInteger[] swapped = reduced[pivotRow];
                reduced[pivotRow] = reduced[selected];
                reduced[selected] = swapped;
                work.consume("berlekamp.rref.row-swaps", 1);
            }
            normalizePivotRow(
                reduced,
                pivotRow,
                column,
                field,
                work);
            eliminateColumn(
                reduced,
                pivotRow,
                column,
                field,
                work);
            pivotColumns.add(column);
            pivotRow++;
        }

        boolean[] pivot = new boolean[columns];
        pivotColumns.forEach(column -> pivot[column] = true);
        ArrayList<UnivariatePolynomialView<BigInteger>> basis =
            new ArrayList<>();
        for (int freeColumn = 0;
                freeColumn < columns;
                freeColumn++) {
            if (pivot[freeColumn]) {
                continue;
            }
            ArrayList<BigInteger> vector = zeros(field, columns);
            vector.set(freeColumn, field.one());
            for (int row = 0; row < pivotColumns.size(); row++) {
                work.consume(
                    "berlekamp.nullspace.back-substitutions",
                    1);
                vector.set(
                    pivotColumns.get(row),
                    field.negate(reduced[row][freeColumn]));
            }
            basis.add(UnivariatePolynomialView.of(
                source.ring(),
                vector));
        }
        basis.sort(Comparator.comparing(
            UnivariatePolynomialView::canonicalMaterial));
        if (basis.isEmpty()) {
            throw new IllegalStateException(
                "Berlekamp kernel unexpectedly has dimension zero");
        }
        return new Nullspace(
            List.copyOf(basis),
            List.copyOf(pivotColumns));
    }

    private static int selectPivot(
        BigInteger[][] matrix,
        int firstRow,
        int column,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        for (int row = firstRow; row < matrix.length; row++) {
            work.consume("berlekamp.rref.pivot-tests", 1);
            if (!field.isZero(matrix[row][column])) {
                return row;
            }
        }
        return -1;
    }

    private static void normalizePivotRow(
        BigInteger[][] matrix,
        int row,
        int column,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        work.consume("berlekamp.rref.pivot-divisions", 1);
        BigInteger inverse = field.divide(
            field.one(),
            matrix[row][column]);
        for (int current = 0;
                current < matrix[row].length;
                current++) {
            work.consume(
                "berlekamp.rref.row-normalizations",
                1);
            matrix[row][current] = field.multiply(
                matrix[row][current],
                inverse);
        }
    }

    private static void eliminateColumn(
        BigInteger[][] matrix,
        int pivotRow,
        int column,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        for (int row = 0; row < matrix.length; row++) {
            if (row == pivotRow
                    || field.isZero(matrix[row][column])) {
                continue;
            }
            BigInteger multiplier = matrix[row][column];
            for (int current = 0;
                    current < matrix[row].length;
                    current++) {
                work.consume(
                    "berlekamp.rref.elimination-updates",
                    2);
                matrix[row][current] = field.subtract(
                    matrix[row][current],
                    field.multiply(
                        multiplier,
                        matrix[pivotRow][current]));
            }
        }
    }

    private static void verify(
        BigInteger[][] matrix,
        List<UnivariatePolynomialView<BigInteger>> basis,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        for (UnivariatePolynomialView<BigInteger> vector : basis) {
            for (BigInteger[] row : matrix) {
                BigInteger sum = field.zero();
                for (int column = 0;
                        column < row.length;
                        column++) {
                    work.consume(
                        "berlekamp.verify.kernel-products",
                        2);
                    sum = field.add(
                        sum,
                        field.multiply(
                            row[column],
                            vector.coefficient(column)));
                }
                work.consume(
                    "berlekamp.verify.kernel-comparisons",
                    1);
                if (!field.isZero(sum)) {
                    throw new IllegalStateException(
                        "Berlekamp nullspace verification failed");
                }
            }
        }
    }

    private static BigInteger[][] copy(BigInteger[][] source) {
        BigInteger[][] result = new BigInteger[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }

    private static ArrayList<BigInteger> zeros(
        PrimeField field,
        int size
    ) {
        ArrayList<BigInteger> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(field.zero());
        }
        return result;
    }

    record Kernel(
        List<UnivariatePolynomialView<BigInteger>> basis,
        int nullity,
        String certificateHash
    ) {
        Kernel {
            basis = List.copyOf(basis);
            if (basis.isEmpty()
                    || nullity != basis.size()
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "Berlekamp kernel result is invalid");
            }
        }
    }

    private record Nullspace(
        List<UnivariatePolynomialView<BigInteger>> basis,
        List<Integer> pivotColumns
    ) {
    }
}
