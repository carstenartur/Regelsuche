package de.regelsuche.math.algorithms.equivalence;

import java.util.Iterator;
import java.util.Map;

public final class GradedReverseLexOrder implements MonomialOrder {
    @Override
    public String name() {
        return "gradedReverseLex";
    }

    @Override
    public int compare(Monomial left, Monomial right) {
        if (left == right) {
            return 0;
        }
        int degreeComparison = Integer.compare(right.totalDegree(), left.totalDegree());
        if (degreeComparison != 0) {
            return degreeComparison;
        }

        Iterator<Map.Entry<String, Integer>> leftEntries = left.orderedPowers()
            .descendingMap().entrySet().iterator();
        Iterator<Map.Entry<String, Integer>> rightEntries = right.orderedPowers()
            .descendingMap().entrySet().iterator();
        Map.Entry<String, Integer> leftEntry = next(leftEntries);
        Map.Entry<String, Integer> rightEntry = next(rightEntries);

        while (leftEntry != null || rightEntry != null) {
            if (rightEntry == null) {
                return 1;
            }
            if (leftEntry == null) {
                return -1;
            }
            int variableComparison = leftEntry.getKey().compareTo(rightEntry.getKey());
            if (variableComparison > 0) {
                return 1;
            }
            if (variableComparison < 0) {
                return -1;
            }
            int exponentComparison = Integer.compare(leftEntry.getValue(), rightEntry.getValue());
            if (exponentComparison != 0) {
                return exponentComparison;
            }
            leftEntry = next(leftEntries);
            rightEntry = next(rightEntries);
        }
        return 0;
    }

    private static Map.Entry<String, Integer> next(Iterator<Map.Entry<String, Integer>> entries) {
        return entries.hasNext() ? entries.next() : null;
    }
}
