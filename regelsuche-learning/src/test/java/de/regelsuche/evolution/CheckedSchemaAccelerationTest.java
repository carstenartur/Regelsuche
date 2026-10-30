package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;

class CheckedSchemaAccelerationTest {
    @Test void shapeFilterAndSharedPathsSatisfyTheExecutableDifferentialContract() {
        CheckedSchemaAccelerationContract.runAll();
    }
}
