package de.regelsuche.math.algorithms.equivalence;

import java.util.TreeSet;

public final class LexOrder implements MonomialOrder {
    @Override
    public String name() {
        return "lex";
    }

    @Override
    public int compare(Monomial left, Monomial right) {
        TreeSet<String> variables = new TreeSet<>();
        variables.addAll(left.powers().keySet());
        variables.addAll(right.powers().keySet());
        for (String variable : variables) {
            int comparison = Integer.compare(right.exponentOf(variable), left.exponentOf(variable));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
