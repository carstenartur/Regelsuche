package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;

public final class GradedReverseLexOrder implements MonomialOrder {
    @Override
    public String name() {
        return "gradedReverseLex";
    }

    @Override
    public int compare(Monomial left, Monomial right) {
        int degreeComparison = Integer.compare(right.totalDegree(), left.totalDegree());
        if (degreeComparison != 0) {
            return degreeComparison;
        }
        TreeSet<String> sorted = new TreeSet<>();
        sorted.addAll(left.powers().keySet());
        sorted.addAll(right.powers().keySet());
        ArrayList<String> variables = new ArrayList<>(sorted);
        Collections.reverse(variables);
        for (String variable : variables) {
            int comparison = Integer.compare(left.exponentOf(variable), right.exponentOf(variable));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
