package de.regelsuche.export;

import de.regelsuche.linalg.Matrix;
import de.regelsuche.linalg.Vector;
import java.util.Objects;

/**
 * LaTeX renderer for {@link Vector}s, {@link Matrix matrices} and the
 * (textual) literal forms produced by the math-domain workbench:
 * {@code [a, b, c]} for vectors and {@code [[a, b], [c, d]]} for matrices.
 *
 * <p>Outputs an {@code \begin{bmatrix} ... \end{bmatrix}} block in all
 * cases — column vectors are rendered as a single-column bmatrix so the
 * caller does not need to special-case row/column form.</p>
 *
 * <p>The renderer is intentionally string-based for the literal form so it
 * can be invoked uniformly from the graph renderer / replay overlay, which
 * deal in expression strings rather than typed objects.</p>
 */
public final class MatrixLatexRenderer {

    public String render(Vector vector) {
        Objects.requireNonNull(vector, "vector");
        StringBuilder builder = new StringBuilder("\\begin{bmatrix}");
        for (int i = 0; i < vector.dimension(); i++) {
            if (i > 0) {
                builder.append(" \\\\ ");
            }
            builder.append(formatNumber(vector.get(i)));
        }
        builder.append("\\end{bmatrix}");
        return builder.toString();
    }

    public String render(Matrix matrix) {
        Objects.requireNonNull(matrix, "matrix");
        StringBuilder builder = new StringBuilder("\\begin{bmatrix}");
        for (int r = 0; r < matrix.rows(); r++) {
            if (r > 0) {
                builder.append(" \\\\ ");
            }
            for (int c = 0; c < matrix.columns(); c++) {
                if (c > 0) {
                    builder.append(" & ");
                }
                builder.append(formatNumber(matrix.get(r, c)));
            }
        }
        builder.append("\\end{bmatrix}");
        return builder.toString();
    }

    /**
     * Renders a literal vector or matrix string ({@code "[a, b]"} or
     * {@code "[[a, b], [c, d]]"}) as a bmatrix. Returns {@code null}
     * when the input is not recognised as a matrix/vector literal so
     * callers can fall back to a regular expression renderer.
     */
    public String renderLiteral(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
            return renderMatrixLiteral(trimmed);
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return renderVectorLiteral(trimmed);
        }
        return null;
    }

    private String renderVectorLiteral(String trimmed) {
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        StringBuilder builder = new StringBuilder("\\begin{bmatrix}");
        String[] cells = inner.split("\\s*,\\s*");
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                builder.append(" \\\\ ");
            }
            builder.append(cells[i].trim());
        }
        builder.append("\\end{bmatrix}");
        return builder.toString();
    }

    private String renderMatrixLiteral(String trimmed) {
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        // Split into rows: split on "],[" boundaries.
        StringBuilder builder = new StringBuilder("\\begin{bmatrix}");
        boolean firstRow = true;
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= inner.length(); i++) {
            if (i < inner.length()) {
                char c = inner.charAt(i);
                if (c == '[') {
                    depth++;
                    if (depth == 1) {
                        start = i + 1;
                    }
                    continue;
                }
                if (c == ']') {
                    depth--;
                    if (depth != 0) {
                        continue;
                    }
                    String row = inner.substring(start, i).trim();
                    if (!firstRow) {
                        builder.append(" \\\\ ");
                    }
                    firstRow = false;
                    String[] cells = row.split("\\s*,\\s*");
                    for (int c2 = 0; c2 < cells.length; c2++) {
                        if (c2 > 0) {
                            builder.append(" & ");
                        }
                        builder.append(cells[c2].trim());
                    }
                }
            }
        }
        builder.append("\\end{bmatrix}");
        return builder.toString();
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
