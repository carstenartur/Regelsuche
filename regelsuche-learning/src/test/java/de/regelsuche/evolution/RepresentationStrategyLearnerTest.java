package de.regelsuche.evolution;

import de.regelsuche.json.JsonReader;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationJson;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Route;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepresentationStrategyLearnerTest {
    @Test void freezesTrainingBeforeApplyingToNewTopologyAndRetainsAllCosts() {
        var study = new RepresentationTransferExperiment().run();
        assertEquals(18, study.policy().observations().size());
        assertEquals(252, study.rows().size());
        assertEquals(study.policy().fitWork() + study.policy().observations().stream()
            .mapToInt(RepresentationStrategyLearner.Observation::totalWork).sum(), study.policy().learningWork());
        for (var summary : study.summaries()) {
            assertEquals(40, summary.solved());
            assertEquals(0, summary.regressions());
        }
        var transfer = study.rows().stream().filter(row -> row.task().id().equals("recurrence-0")
            && row.profile() == RepresentationStrategyLearner.Profile.LEARNED).findFirst().orElseThrow();
        assertEquals(Route.BLOCKS, transfer.application().result().selected());
        assertTrue(transfer.application().audit().verified());
        assertFalse(study.policy().trainingIdentities().contains(RepresentationStrategyLearner.identity(transfer.task().equations())));
        String frozen = RepresentationTransferExperiment.policyHash(study.policy());
        var negative = new RepresentationStrategyLearner().apply(study.policy(), List.of("sin(x)=0"),
            RepresentationStrategyLearner.Profile.LEARNED, 1000);
        assertFalse(negative.audit().verified());
        assertTrue(negative.totalWork() > 0);
        assertEquals(frozen, RepresentationTransferExperiment.policyHash(study.policy()));
        String full = RepresentationTransferExperiment.toJson(study);
        var manifest = new JsonReader(RepresentationTransferExperiment.manifestJson(study)).readObject();
        assertEquals(LinearRepresentationJson.hash(full), manifest.get("studyHash"));
        assertNotEquals(manifest.get("studyHash"), LinearRepresentationJson.hash(full.replace("recurrence-0", "changed")));
        String artifact = LinearRepresentationJson.toJson(transfer.application().result(), transfer.application().audit());
        assertEquals(artifact, LinearRepresentationJson.replay(new JsonReader(artifact).readObject()));
    }

    @Test void learnedSelectorBeatsStaticBaselineAndExpertReferenceIsNotAnUnlearnedBaseline() {
        var study = new RepresentationTransferExperiment().run();
        var direct = study.summaries().stream()
            .filter(summary -> summary.profile() == RepresentationStrategyLearner.Profile.DIRECT)
            .findFirst().orElseThrow();
        var fixed = study.summaries().stream()
            .filter(summary -> summary.profile() == RepresentationStrategyLearner.Profile.FIXED_AUTO)
            .findFirst().orElseThrow();
        var learned = study.summaries().stream()
            .filter(summary -> summary.profile() == RepresentationStrategyLearner.Profile.LEARNED)
            .findFirst().orElseThrow();

        long savedAgainstStaticBaseline = direct.applicationWork() - learned.applicationWork();
        assertTrue(savedAgainstStaticBaseline > 0,
            "The learned selector must be compared with a static route baseline, not only with an expert rule");

        long learnedSelectionWork = study.rows().stream()
            .filter(row -> row.profile() == RepresentationStrategyLearner.Profile.LEARNED)
            .mapToLong(row -> row.application().selectionWork()).sum();
        assertEquals(learnedSelectionWork, learned.applicationWork() - fixed.applicationWork(),
            "FIXED_AUTO and LEARNED choose the same routes here; their difference is selector accounting only");
        for (var task : RepresentationTransferExperiment.evaluation()) {
            var fixedRoute = study.rows().stream().filter(row -> row.task().id().equals(task.id())
                && row.profile() == RepresentationStrategyLearner.Profile.FIXED_AUTO)
                .map(row -> row.application().result().selected()).findFirst().orElseThrow();
            var learnedRoute = study.rows().stream().filter(row -> row.task().id().equals(task.id())
                && row.profile() == RepresentationStrategyLearner.Profile.LEARNED)
                .map(row -> row.application().result().selected()).findFirst().orElseThrow();
            assertEquals(fixedRoute, learnedRoute);
        }

        long breakEvenCases = (study.policy().learningWork() * (long) RepresentationTransferExperiment.evaluation().size()
            + savedAgainstStaticBaseline - 1) / savedAgainstStaticBaseline;
        assertEquals(108, breakEvenCases,
            "One-time training cost must be amortized over repeated deployment, not charged as a permanent penalty");

        String diagnosis = RepresentationLearningDiagnosis.toJson(study);
        assertTrue(diagnosis.contains("\"learnsMathematicalProcedures\":false"));
        assertTrue(diagnosis.contains("\"knowledgeAblation\":false"));
    }

    @Test void excludesRenamedReorderedTrainingEquationsAndBindsArtifacts() {
        assertEquals(RepresentationStrategyLearner.identity(List.of("x+y=3", "x-y=1")),
            RepresentationStrategyLearner.identity(List.of("a-b=1", "b+a=3")));
        assertThrows(IllegalArgumentException.class, () -> new RepresentationStrategyLearner().fit(List.of(
            List.of("x+y=3", "x-y=1"), List.of("a-b=1", "b+a=3")), 20_000));
        String request = "{\"schema\":\"regelsuche.linear-solve-request/v1\",\"equations\":[\"x+y=3\",\"x-y=1\"]}";
        String solved = LinearRepresentationJson.solve(new JsonReader(request).readObject());
        assertEquals(solved, LinearRepresentationJson.replay(new JsonReader(solved).readObject()));
        var forged = new JsonReader(solved).readObject();
        forged.put("contentHash", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> LinearRepresentationJson.replay(forged));
        var badRequest = new JsonReader(request).readObject();
        badRequest.put("maxWorkUnits", 2.5);
        assertThrows(IllegalArgumentException.class, () -> LinearRepresentationJson.solve(badRequest));
    }
}
