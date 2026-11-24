package de.regelsuche.release;

import org.junit.jupiter.api.Test;

class SchemaPolynomialComparisonContainerIT {
    @Test void executesAndIndependentlyChecksAll492RowsIn36ActualSequences() throws Throwable {
        FrozenPolynomialStudyContainer.run(FrozenPolynomialStudyContainer.Study.SCHEMA);
    }
}
