package de.regelsuche.release;

import org.junit.jupiter.api.Test;

class NativePolynomialComparisonContainerIT {
    @Test void executesAndIndependentlyChecksTheFrozen675Rows() throws Throwable {
        FrozenPolynomialStudyContainer.run(FrozenPolynomialStudyContainer.Study.NATIVE);
    }
}
