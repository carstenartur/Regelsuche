package de.regelsuche.polynomial;

import org.junit.jupiter.api.Test;

class PrimeFieldArithmeticTest {
    @Test void canonicalResiduesDoNotNeedAnotherReduction() {
        PrimeFieldArithmeticChecks.canonicalResiduesDoNotNeedAnotherReduction();
    }
    @Test void exhaustiveSmallFieldsMatchBigInteger() {
        PrimeFieldArithmeticChecks.exhaustiveSmallFieldsMatchBigInteger();
    }
    @Test void largeInputsAndLargestModulusRemainExact() {
        PrimeFieldArithmeticChecks.largeInputsAndLargestModulusRemainExact();
    }
    @Test void invalidInputsAndDomainIdentityAreUnchanged() {
        PrimeFieldArithmeticChecks.invalidInputsAndDomainIdentityAreUnchanged();
    }
    @Test void immutableFieldCanBeSharedAcrossThreads() throws Exception {
        PrimeFieldArithmeticChecks.immutableFieldCanBeSharedAcrossThreads();
    }
}
