package de.regelsuche.math.algorithms.equivalence;

import java.util.Comparator;

public interface MonomialOrder extends Comparator<Monomial> {
    String name();
}
