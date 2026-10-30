package de.regelsuche.math.algorithms.equivalence;

import org.junit.jupiter.api.Test;

class AlgebraicConstructionTest {
    private final AlgebraicConstructionContract contract = new AlgebraicConstructionContract();
    @Test void exactAdditiveAndMultiplicativeSplits() { contract.splitsAdditiveAndMultiplicativeStructureExactly(); }
    @Test void multiplicativeDomainIsPreserved() { contract.multiplicativeCompensationRetainsTheDomain(); }
    @Test void enumerationAndFailuresAreBoundedAndPaid() { contract.boundedEnumerationAndFailuresRemainPaid(); }
}
