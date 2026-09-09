package de.regelsuche.evolution;

import de.regelsuche.evolution.RepresentationStrategyLearner.Profile;
import de.regelsuche.evolution.RepresentationTransferExperiment.Case;
import de.regelsuche.evolution.RepresentationTransferExperiment.Row;
import de.regelsuche.evolution.RepresentationTransferExperiment.Study;
import de.regelsuche.json.JsonReader;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationJson;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepresentationLearningDiagnosisTest {
    @Test void detectsSaturatedSelectionAndSeparatesAuditWithoutChangingThePolicy() {
        var study = new RepresentationTransferExperiment().run();
        String frozen = RepresentationTransferExperiment.policyHash(study.policy());
        var diagnosis = RepresentationLearningDiagnosis.analyze(study);
        assertEquals(40, diagnosis.comparableCases());
        assertEquals(2, diagnosis.unverifiedCases());
        assertEquals(0, diagnosis.additionalSolvableCases());
        assertEquals(0, diagnosis.cheaperVerifiedCases());
        assertEquals(0, diagnosis.avoidableFixedWork());
        assertEquals(0, diagnosis.learnedChoiceDifferences());
        for (var work : diagnosis.work()) {
            long recorded = study.rows().stream().filter(row -> row.profile() == work.profile())
                .mapToLong(row -> row.application().totalWork()).sum();
            assertEquals(recorded, work.application());
        }
        var learned = diagnosis.work().stream().filter(work -> work.profile() == Profile.LEARNED).findFirst().orElseThrow();
        assertEquals(23268, learned.construction());
        assertEquals(55192, learned.audit());
        assertEquals(84, learned.selection());
        var manifest = new JsonReader(RepresentationTransferExperiment.manifestJson(study)).readObject();
        assertEquals(LinearRepresentationJson.hash(RepresentationLearningDiagnosis.toJson(study)), manifest.get("diagnosisHash"));
        assertEquals(frozen, RepresentationTransferExperiment.policyHash(study.policy()));
    }

    @Test void detectsRealRemainingWorkWhenTheReferenceUsesAMoreExpensiveVerifiedRoute() {
        var study = singleCase(20_000);
        var learner = new RepresentationStrategyLearner();
        var rows = study.rows().stream().map(row -> row.profile() == Profile.FIXED_AUTO
            ? new Row(row.task(), row.profile(), learner.apply(study.policy(), row.task().equations(), Profile.MATRIX, 20_000))
            : row).toList();
        var diagnosis = RepresentationLearningDiagnosis.analyze(new Study(study.policy(), rows, List.of()));
        assertEquals(1, diagnosis.comparableCases());
        assertEquals(1, diagnosis.cheaperVerifiedCases());
        assertTrue(diagnosis.avoidableFixedWork() > 0);
        assertEquals("BLOCKS", diagnosis.cases().getFirst().bestVerifiedRoute());
    }

    @Test void neverTreatsACheaperBudgetFailureAsABetterVerifiedSolution() {
        var study = singleCase(1100);
        var diagnosis = RepresentationLearningDiagnosis.analyze(study);
        var bound = diagnosis.cases().getFirst();
        var direct = study.rows().stream().filter(row -> row.profile() == Profile.DIRECT).findFirst().orElseThrow();
        assertFalse(direct.application().audit().verified());
        assertTrue(direct.application().totalWork() < bound.bestVerifiedRouteWork());
        assertEquals("BLOCKS", bound.bestVerifiedRoute());
        assertEquals(0, diagnosis.avoidableFixedWork());
        assertThrows(IllegalArgumentException.class, () -> RepresentationLearningDiagnosis.analyze(
            new Study(study.policy(), study.rows().subList(1, study.rows().size()), List.of())));
        var rows = new java.util.ArrayList<>(study.rows());
        rows.add(study.rows().getFirst());
        assertThrows(IllegalArgumentException.class, () -> RepresentationLearningDiagnosis.analyze(
            new Study(study.policy(), rows, List.of())));
    }

    private static Study singleCase(int budget) {
        var learner = new RepresentationStrategyLearner();
        var policy = learner.fit(RepresentationTransferExperiment.training(), 20_000);
        Case task = RepresentationTransferExperiment.evaluation().stream().filter(c -> c.id().equals("recurrence-0"))
            .findFirst().orElseThrow();
        List<Row> rows = Arrays.stream(Profile.values()).map(profile ->
            new Row(task, profile, learner.apply(policy, task.equations(), profile, budget))).toList();
        return new Study(policy, rows, List.of());
    }
}
