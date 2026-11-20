package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ModularKernel.Certificate;
import de.regelsuche.math.algorithms.linalg.ModularKernel.Strength;
import de.regelsuche.math.algorithms.linalg.ModularKernel.Work;
import java.math.BigInteger;
import java.util.List;

/** Checks the original rational matrix and selected minor without running CRT or candidate discovery. */
final class ModularKernelVerifier {
    private ModularKernelVerifier() {}

    static boolean check(ExactMatrix a, Strength strength, Certificate c, Work work) {
        work.shape(a);
        if (!matchesSource(a, c, work)) return false;
        boolean[] selected = checkPartition(a, c, work);
        return selected != null
            && hasProjectionBlocks(c, work)
            && invertible(a, c, work)
            && residualsVanish(a, strength, c, selected, work);
    }

    private static boolean matchesSource(ExactMatrix a, Certificate c, Work work) {
        int n = a.columns(), m = a.rowCount();
        if (c == null || c.source().columns() != n || c.source().rowCount() != m
                || c.projection().columns() != n || c.projection().rowCount() != n) return false;
        for (int i = 0; i < m; i++) for (int j = 0; j < n; j++) {
            work.step();
            work.bound(a.get(i,j));
            work.bound(c.source().get(i,j));
            if (!a.get(i,j).equals(c.source().get(i,j))) return false;
        }
        return true;
    }

    /** Returns the selected-row mask after checking that the pivot and free columns partition all coordinates. */
    private static boolean[] checkPartition(ExactMatrix a, Certificate c, Work work) {
        int n = a.columns(), m = a.rowCount(), r = c.pivots().size();
        if (r > Math.min(n,m) || c.selectedRows().size() != r || c.freeColumns().size() != n-r) return null;
        var selected = new boolean[m];
        var partition = new boolean[n];
        if (!mark(c.selectedRows(), selected, work) || !mark(c.pivots(), partition, work)
                || !mark(c.freeColumns(), partition, work)) return null;
        return selected;
    }

    private static boolean hasProjectionBlocks(Certificate c, Work work) {
        int n = c.projection().columns();
        var free = new boolean[n];
        for (int j : c.freeColumns()) free[j] = true;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            work.step();
            var x = c.projection().get(i,j);
            work.bound(x);
            if (free[j] && !free[i]) continue;
            boolean identityEntry = free[i] && i == j;
            if (identityEntry ? !x.isOne() : !x.isZero()) return false;
        }
        return true;
    }

    private static boolean residualsVanish(ExactMatrix a, Strength strength, Certificate c,
            boolean[] selected, Work work) {
        for (int i = 0; i < a.rowCount(); i++) {
            work.step();
            if (strength == Strength.SUBSTITUTION && !selected[i]) continue;
            for (int j : c.freeColumns()) {
                work.step();
                // The free-by-free block contributes precisely A[i,j].
                Rational sum = a.get(i,j);
                for (int k : c.pivots()) {
                    work.step();
                    var term = a.get(i,k).multiply(c.projection().get(k,j));
                    work.bound(term);
                    sum = sum.add(term);
                    work.bound(sum);
                }
                if (!sum.isZero()) return false;
            }
        }
        return true;
    }

    private static boolean mark(List<Integer> indices, boolean[] used, Work work) {
        for (int i : indices) {
            work.step();
            if (i < 0 || i >= used.length || used[i]) return false;
            used[i] = true;
        }
        return true;
    }

    /** Forward elimination on the selected rational minor reduced directly into its prime field. */
    private static boolean invertible(ExactMatrix a, Certificate c, Work work) {
        int prime = c.witnessPrime(), r = c.pivots().size();
        if (!ModularKernel.isPrime(prime, work)) return false;
        var p = BigInteger.valueOf(prime);
        long[][] minor = new long[r][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < r; j++) {
            work.step();
            Rational x = a.get(c.selectedRows().get(i), c.pivots().get(j));
            long denominator = x.denominator().mod(p).longValueExact();
            if (denominator == 0) return false;
            minor[i][j] = x.numerator().mod(p).longValueExact() * ModularKernel.inverse(denominator, prime) % prime;
        }
        for (int j = 0; j < r; j++) {
            int k = j;
            while (k < r && minor[k][j] == 0) { work.step(); k++; }
            if (k == r) return false;
            work.step();
            long[] swap = minor[j];
            minor[j] = minor[k];
            minor[k] = swap;
            long inverse = ModularKernel.inverse(minor[j][j], prime);
            for (int i = j+1; i < r; i++) {
                work.step();
                long factor = minor[i][j] * inverse % prime;
                for (int col = j+1; col < r; col++) {
                    work.step();
                    minor[i][col] = Math.floorMod(minor[i][col] - factor * minor[j][col], prime);
                }
            }
        }
        return true;
    }
}
