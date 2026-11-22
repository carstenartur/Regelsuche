package de.regelsuche.release;

import org.junit.jupiter.api.Test;

class TypedPolynomialComparisonContainerIT {
    @Test void executesVerifiesAndAuditsTheFrozen729Rows() throws Throwable {
        FrozenPolynomialStudyContainer.run(FrozenPolynomialStudyContainer.Study.TYPED);
    }
}
