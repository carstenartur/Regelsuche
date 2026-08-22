package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.RepresentationMatchedWorkExperiment.CaseResult;
import de.regelsuche.benchmark.RepresentationMatchedWorkExperiment.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepresentationMatchedWorkExperimentTest {

    @Test
    void frozenRoutesProduceIdenticalVerifiedConsequences() {
        RepresentationMatchedWorkExperiment experiment =
            new RepresentationMatchedWorkExperiment();

        Report report = experiment.run(
            RepresentationMatchedWorkExperiment.defaultCases(),
            RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);

        assertTrue(report.allComparableAndEquivalent());
        assertEquals(8, report.cases().size());
        for (CaseResult result : report.cases()) {
            assertTrue(result.comparable(), result.experimentCase().id());
            assertTrue(result.equivalent(), result.experimentCase().id());
            assertTrue(result.representationRoute().verified());
            assertTrue(result.directRoute().verified());
            assertEquals(
                result.representationRoute().consequence(),
                result.directRoute().consequence());
            assertTrue(result.representationRoute().totalWork()
                <= RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);
            assertTrue(result.directRoute().totalWork()
                <= RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);
        }
    }

    @Test
    void reportIsDeterministicAndDoesNotPredeclareAWinner() {
        RepresentationMatchedWorkExperiment experiment =
            new RepresentationMatchedWorkExperiment();
        Report first = experiment.run(
            RepresentationMatchedWorkExperiment.defaultCases(),
            RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);
        Report second = experiment.run(
            RepresentationMatchedWorkExperiment.defaultCases(),
            RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);

        assertEquals(first, second);
        assertEquals(first.toJson(), second.toJson());
        assertEquals(first.toMarkdown(), second.toMarkdown());
        assertEquals(
            first.cases().size(),
            first.representationWins() + first.directWins() + first.ties());
        assertFalse(first.toMarkdown().contains("proves global superiority"));
        assertTrue(first.toMarkdown().contains("Claim boundary"));
    }

    @Test
    void cancelledCoordinateCasesRemainInFrozenReport() {
        Report report = new RepresentationMatchedWorkExperiment().run(
            RepresentationMatchedWorkExperiment.defaultCases(),
            RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);

        CaseResult free = find(report, "cancelled-coordinate-free");
        assertTrue(free.equivalent());
        assertTrue(free.representationRoute().consequence()
            .orElseThrow().canonicalLines().stream()
            .anyMatch(line -> line.contains("basis[0]=[1]")));

        CaseResult contradiction = find(
            report,
            "cancelled-coordinate-contradiction");
        assertTrue(contradiction.equivalent());
        assertTrue(contradiction.representationRoute().consequence()
            .orElseThrow().canonicalLines().contains("contradiction=0=1"));
    }

    @Test
    void reportFilesAreRetained(@TempDir Path temporaryDirectory)
            throws IOException {
        Report report = new RepresentationMatchedWorkExperiment().run(
            RepresentationMatchedWorkExperiment.defaultCases(),
            RepresentationMatchedWorkExperiment.DEFAULT_TOTAL_BUDGET);

        report.write(temporaryDirectory);

        Path json = temporaryDirectory.resolve("matched-work-report.json");
        Path markdown = temporaryDirectory.resolve("matched-work-report.md");
        assertTrue(Files.isRegularFile(json));
        assertTrue(Files.isRegularFile(markdown));
        assertEquals(report.toJson(), Files.readString(json));
        assertEquals(report.toMarkdown(), Files.readString(markdown));
    }

    private static CaseResult find(Report report, String id) {
        return report.cases().stream()
            .filter(result -> result.experimentCase().id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
