package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Keeps both successful controls and blocked historical learning attempts visible in CI. */
class HistoricalAbstractionDiagnosticTest {
    @Test
    void distinguishesBoundTermConstructionFromUnboundAuxiliarySynthesis() throws IOException {
        retain("expression-boundary", HistoricalAbstractionBoundaryContract.expressionBoundary());
    }

    @Test
    @Timeout(180)
    void repeatedPaidLearningCannotInventMissingPrimitiveMoves() throws IOException {
        retain("cumulative-rounds", HistoricalAbstractionBoundaryContract.cumulativeRounds());
    }

    @Test
    @Timeout(180)
    void recordsTheCurrentCustomObjectiveRoutingBoundary() throws IOException {
        retain("custom-goal-routing", HistoricalAbstractionBoundaryContract.customGoalRouting());
    }

    @Test
    @Timeout(300)
    void reproducesGeneralHistoricalPrecursorsAndResidualComposition() throws IOException {
        retain("historical-controls", new HistoricalSearchControlContract().run());
    }

    private static void retain(String name, Object report) throws IOException {
        Path directory = Path.of("build", "reports", "historical-abstraction-diagnostic");
        Files.createDirectories(directory);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(directory.resolve(name + ".json").toFile(), report);
    }
}
