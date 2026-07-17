package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.regelsuche.mining.InterestingnessIndependentReviewIntake.EvidenceStatus;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.IntakeReport;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class InterestingnessIndependentReviewEvidenceTest {
    @Test
    void writesByteIdenticalProtocolValidationEvidence() throws IOException {
        StudyPlan firstPlan = IndependentReviewProtocolFixtures.plan();
        StudyPlan secondPlan = IndependentReviewProtocolFixtures.plan();
        InterestingnessIndependentReviewIntake intake =
            new InterestingnessIndependentReviewIntake();
        IntakeReport firstIntake = intake.evaluate(
            firstPlan,
            IndependentReviewProtocolFixtures.developmentSubmissions(firstPlan),
            1,
            ""
        );
        IntakeReport secondIntake = intake.evaluate(
            secondPlan,
            IndependentReviewProtocolFixtures.developmentSubmissions(secondPlan),
            1,
            ""
        );

        assertEquals(firstPlan.toCanonicalJson(), secondPlan.toCanonicalJson());
        assertEquals(firstIntake.toCanonicalJson(), secondIntake.toCanonicalJson());
        assertEquals(EvidenceStatus.DEVELOPMENT_ONLY, firstIntake.evidenceStatus());
        assertEquals(0, firstIntake.countedExpertReviews());
        assertFalse(firstIntake.eligibleForEmpiricalConsensus());

        Path output = Path.of("build", "reports", "independent-review-protocol");
        deleteRecursively(output);
        Files.createDirectories(output);
        Files.writeString(
            output.resolve("study-plan.json"),
            firstPlan.toCanonicalJson(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            output.resolve("development-intake.json"),
            firstIntake.toCanonicalJson(),
            StandardCharsets.UTF_8
        );
    }

    private void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
