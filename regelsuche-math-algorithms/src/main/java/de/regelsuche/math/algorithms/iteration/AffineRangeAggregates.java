package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.math.algorithms.iteration.AffineReductionPlan.Window;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class AffineRangeAggregates {
    private AffineRangeAggregates() {}

    static List<BigInteger> sums(List<BigInteger> input, List<Window> windows, boolean weighted, Work work) {
        var prefix = new BigInteger[input.size() + 1];
        var moments = weighted ? new BigInteger[prefix.length] : null;
        prefix[0] = BigInteger.ZERO;
        if (moments != null) moments[0] = BigInteger.ZERO;
        for (int i = 0; i < input.size(); i++) {
            var value = input.get(i);
            prefix[i + 1] = checked(prefix[i].add(value), work);
            if (moments != null) {
                var moment = checked(value.multiply(BigInteger.valueOf(i)), work);
                moments[i + 1] = checked(moments[i].add(moment), work);
            }
        }
        var values = new ArrayList<BigInteger>();
        for (var window : windows) {
            var value = rangeSum(prefix, window, work);
            if (moments != null) value = weightedSum(value, moments, window, work);
            values.add(value);
        }
        return values;
    }

    private static BigInteger rangeSum(BigInteger[] summary, Window window, Work work) {
        return checked(summary[window.upper() + 1].subtract(summary[window.lower()]), work);
    }

    private static BigInteger weightedSum(BigInteger sum, BigInteger[] moments, Window window, Work work) {
        var constant = checked(sum.multiply(BigInteger.valueOf(window.weightConstant())), work);
        var linear = checked(rangeSum(moments, window, work)
            .multiply(BigInteger.valueOf(window.weightSlope())), work);
        return checked(constant.add(linear), work);
    }

    static List<BigInteger> maxima(List<BigInteger> input, List<Window> windows, Work work) {
        boolean prefixes = true;
        boolean monotone = true;
        int left = 0;
        int right = 0;
        for (var window : windows) {
            work.charge(1);
            prefixes &= window.lower() == 0;
            monotone &= window.lower() >= left && window.upper() >= right;
            left = window.lower();
            right = window.upper();
        }
        if (prefixes) return prefixMaxima(input, windows, work);
        if (monotone) return monotoneMaxima(input, windows, work);
        return indexedMaxima(input, windows, work);
    }

    private static List<BigInteger> prefixMaxima(List<BigInteger> input, List<Window> windows, Work work) {
        var prefix = new ArrayList<BigInteger>();
        BigInteger maximum = null;
        for (var value : input) {
            maximum = max(maximum, value, work);
            prefix.add(maximum);
        }
        var result = new ArrayList<BigInteger>();
        for (var window : windows) {
            work.charge(1);
            result.add(prefix.get(window.upper()));
        }
        return result;
    }

    private static List<BigInteger> monotoneMaxima(List<BigInteger> input, List<Window> windows, Work work) {
        var active = new ArrayDeque<Integer>();
        var result = new ArrayList<BigInteger>();
        int next = 0;
        for (var window : windows) {
            work.charge(1);
            while (!active.isEmpty() && active.getFirst() < window.lower()) {
                work.charge(1);
                active.removeFirst();
            }
            next = Math.max(next, window.lower());
            while (next <= window.upper()) {
                while (!active.isEmpty() && compare(input.get(active.getLast()), input.get(next), work) <= 0) {
                    active.removeLast();
                }
                work.charge(1);
                active.addLast(next++);
            }
            result.add(input.get(active.getFirst()));
        }
        return result;
    }

    private static List<BigInteger> indexedMaxima(List<BigInteger> input, List<Window> windows, Work work) {
        int leaves = input.size();
        var tree = new BigInteger[2 * leaves];
        for (int i = 0; i < leaves; i++) {
            work.charge(1);
            tree[leaves + i] = input.get(i);
        }
        for (int i = leaves - 1; i > 0; i--) tree[i] = max(tree[2 * i], tree[2 * i + 1], work);
        var result = new ArrayList<BigInteger>();
        for (var window : windows) result.add(query(tree, leaves, window, work));
        return result;
    }

    private static BigInteger query(BigInteger[] tree, int leaves, Window window, Work work) {
        int left = leaves + window.lower(), right = leaves + window.upper();
        BigInteger result = null;
        while (left <= right) {
            work.charge(1);
            if (left % 2 == 1) result = max(result, tree[left++], work);
            if (right % 2 == 0) result = max(result, tree[right--], work);
            left /= 2;
            right /= 2;
        }
        return result;
    }

    private static BigInteger max(BigInteger left, BigInteger right, Work work) {
        if (left != null) return compare(left, right, work) <= 0 ? right : left;
        work.charge(1);
        return right;
    }

    private static int compare(BigInteger left, BigInteger right, Work work) {
        work.charge(1 + Math.max(left.bitLength(), right.bitLength()) / 64L);
        return left.compareTo(right);
    }

    private static BigInteger checked(BigInteger value, Work work) {
        work.runtimeInteger(value);
        return value;
    }
}
