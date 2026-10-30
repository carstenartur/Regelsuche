package de.regelsuche.evolution;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StructuralLearningStreamTest {
    @Test
    @Timeout(180)
    void cumulativeMixedStreamPaysItsAcquisition() throws Exception {
        new StructuralLearningStreamContract().cumulativeMixedStreamPaysItsAcquisition(
            Path.of("build", "reports", "structural-construction-learning"));
    }
}
