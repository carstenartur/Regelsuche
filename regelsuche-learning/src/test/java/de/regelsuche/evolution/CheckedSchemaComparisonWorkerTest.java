package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(180)
class CheckedSchemaComparisonWorkerTest {
    @Test void primitiveControlRestoresWithoutUnusedTrainingAndPaysIndependentReplay() {
        var training = new CheckedSchemaComparisonWorker();
        var setup = training.initialize("BASE");
        assertEquals(0L, setup.get("trainingWork"));
        var serving = new CheckedSchemaComparisonWorker();
        var loaded = serving.restore("BASE", (String) setup.get("model"));
        assertEquals(setup.get("modelHash"), loaded.get("modelHash"));
        var result = serving.run("BASE", "(z+0)*1+101");
        assertEquals(false, result.get("targetReached"));
        assertTrue((Long) result.get("selectedReplayWork") > 0);
        assertTrue((Long) result.get("outputCost") < (Long) result.get("inputCost"));
        assertTrue((Boolean) result.get("internalWorkWithinBudget"));
        assertDoesNotThrow(() -> LearnedSchedulingArtifacts.json(result));
    }

    @Test void learnedKnowledgeIsReprovedAndUsedAfterFreshWorkerRestore() {
        var training = new CheckedSchemaComparisonWorker();
        var setup = training.initialize("LEARNED_SCHEMA");
        assertTrue(setup.containsKey("model"));
        assertTrue((Long) setup.get("trainingWork") > 0);
        var serving = new CheckedSchemaComparisonWorker();
        var loaded = serving.restore("LEARNED_SCHEMA", (String) setup.get("model"));
        assertTrue((Long) loaded.get("restoreWork") > 0);
        var first = serving.run("LEARNED_SCHEMA", "((u+v)*(u-v)+v*v)+113");
        var second = serving.run("LEARNED_SCHEMA", "((u+v)*(u-v)+v*v)+113");
        assertEquals(setup.get("modelHash"), first.get("modelHash"));
        assertEquals(first.get("search"), second.get("search"));
        assertTrue((Long) first.get("outputCost") < (Long) first.get("inputCost"));
        assertTrue(LearnedSchedulingArtifacts.json((List<?>) first.get("witness")).contains("CHECKED_SCHEMA_OCCURRENCE_VERIFIED"));
    }

    @Test void rejectsTamperingOverlapLifecycleAndTargetInjection() throws Exception {
        var worker = new CheckedSchemaComparisonWorker();
        assertThrows(IllegalStateException.class, () -> worker.run("BASE", "x"));
        var setup = worker.initialize("BASE");
        assertThrows(IllegalStateException.class, () -> worker.initialize("BASE"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("BASE", "((m+n)*(m-n)+n*n)*(m+3)"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("BASE", "sin(x)"));
        assertThrows(IllegalArgumentException.class, () -> new CheckedSchemaComparisonWorker().restore("LEARNED_SCHEMA", (String) setup.get("model")));
        assertThrows(IllegalArgumentException.class, () -> new CheckedSchemaComparisonWorker().restore("BASE", ((String) setup.get("model")).replace("/v2", "/obsolete")));
        assertThrows(IllegalArgumentException.class, () -> worker.handle("{\"op\":\"run\",\"profile\":\"BASE\",\"source\":\"x\",\"target\":\"x\"}"));
        assertThrows(Exception.class, () -> worker.handle("{\"op\":\"run\",\"op\":\"run\",\"profile\":\"BASE\",\"source\":\"x\"}"));
    }

    @Test void sourceOnlySelectionIsPaidAndFrozenAcrossRestore() {
        var worker = new CheckedSchemaComparisonWorker();
        var setup = worker.initialize("LEARNED_SELECTED");
        assertTrue((Long) setup.get("trainingWork") > 0);
        assertTrue(((String) setup.get("model")).contains("FULL_CONTINUATION_WORK"));
        var restored = new CheckedSchemaComparisonWorker();
        assertEquals(setup.get("selected"), restored.restore("LEARNED_SELECTED", (String) setup.get("model")).get("selected"));
        var first = worker.run("LEARNED_SELECTED", "((j+k)*(j-k)+k*k)+127");
        var second = restored.run("LEARNED_SELECTED", "((j+k)*(j-k)+k*k)+127");
        assertEquals(first.get("output"), second.get("output"));
        assertEquals(first.get("search"), second.get("search"));
        assertEquals(setup.get("modelHash"), second.get("modelHash"));
    }
}
