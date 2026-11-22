package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Bounded exact row echelon reduction and back-substitution on caller-owned augmented rows. */
final class SosAffineSpace {
    private final List<ExactRational[]> rows;
    private final List<Integer> pivots = new ArrayList<>();
    private final List<Integer> free = new ArrayList<>();
    private final int width;
    private final CertificateWork work;
    private boolean consistent = true;

    SosAffineSpace(Collection<ExactRational[]> equations, int width, CertificateWork work) {
        this.width = width;
        this.work = work;
        rows = new ArrayList<>(equations);
        for (int col = 0; col < width; col++) {
            work.charge(1);
            int next = pivots.size();
            int candidate = next;
            while (candidate < rows.size() && rows.get(candidate)[col].isZero()) {
                work.charge(1);
                candidate++;
            }
            if (candidate == rows.size()) {
                free.add(col);
            } else {
                Collections.swap(rows, next, candidate);
                eliminateBelow(next, col);
                pivots.add(col);
            }
        }
        for (int row = pivots.size(); row < rows.size(); row++) {
            work.charge(1);
            if (!rows.get(row)[width].isZero()) consistent = false;
        }
    }

    private void eliminateBelow(int pivotRow, int column) {
        var pivot = rows.get(pivotRow);
        for (int r = pivotRow+1; r < rows.size(); r++) {
            var row = rows.get(r);
            work.charge(1);
            if (row[column].isZero()) continue;
            var factor = work.div(row[column], pivot[column]);
            row[column] = ExactRational.ZERO;
            for (int j = column+1; j <= width; j++) row[j] = work.sub(row[j], work.mul(factor, pivot[j]));
        }
    }

    boolean consistent() { return consistent; }
    int freeCount() { return free.size(); }

    ExactRational[] solution(List<ExactRational> grid, int[] digits) {
        work.charge(width);
        var result = new ExactRational[width];
        for (int i = 0; i < digits.length; i++) result[free.get(i)] = grid.get(digits[i]);
        for (int r = pivots.size()-1; r >= 0; r--) {
            int col = pivots.get(r);
            var row = rows.get(r);
            var value = row[width];
            for (int j = col+1; j < width; j++) {
                work.charge(1);
                if (!row[j].isZero()) value = work.sub(value, work.mul(row[j], result[j]));
            }
            result[col] = work.div(value, row[col]);
        }
        return result;
    }
}
