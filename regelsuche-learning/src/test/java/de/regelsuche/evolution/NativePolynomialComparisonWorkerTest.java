package de.regelsuche.evolution;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
class NativePolynomialComparisonWorkerTest {
    @Test
    void baselineUsesNativeAlgebraWithoutUnusedTraining() {
        var worker = new NativePolynomialComparisonWorker();
        var setup = worker.initialize("NATIVE_BASE");
        assertEquals(0L, setup.get("trainingWork"));
        var result = worker.run("NATIVE_BASE", "(1/5)*z+(4/5)*z");
        assertEquals("z", result.get("output"));
        assertEquals(0L, result.get("outputCost"));
        assertEquals(true, result.get("internalWorkWithinBudget"));
        assertEquals(setup.get("modelHash"), result.get("modelHash"));
        assertTrue((Long) result.get("selectedReplayWork") > 0);
        assertFalse(((List<?>) result.get("witness")).isEmpty());
    }

    @Test
    void actualTrainingIsPersistableAndReprovedInAFreshConsumer() {
        var producer = new NativePolynomialComparisonWorker();
        var acquired = producer.initialize("NATIVE_LEARNED");
        assertTrue((Long) acquired.get("trainingWork") > 0);
        assertThrows(IllegalStateException.class, () -> producer.run("NATIVE_LEARNED", "z+101"));
        var consumer = new NativePolynomialComparisonWorker();
        var restored = consumer.restore("NATIVE_LEARNED", (String) acquired.get("model"));
        assertEquals(acquired.get("modelHash"), restored.get("modelHash"));
        assertTrue((Long) restored.get("restorationWork") > 0);
        assertTrue((Integer) restored.get("schemas") > 0);
        var first = consumer.run("NATIVE_LEARNED", "((u+v)*(u-v)+v*v)+101");
        var second = consumer.run("NATIVE_LEARNED", "((u+v)*(u-v)+v*v)+101");
        assertEquals(2L, first.get("outputCost"));
        assertEquals(first.get("search"), second.get("search"));
        assertEquals(first.get("totalWork"), second.get("totalWork"));
    }

    @Test
    void rejectsTargetsProfileChangesForeignCapabilitiesAndTrainingOverlap() throws Exception {
        var worker = new NativePolynomialComparisonWorker();
        assertThrows(IllegalStateException.class, () -> worker.run("NATIVE_BASE", "x"));
        assertThrows(Exception.class, () -> worker.handle("{\"op\":\"initialize\",\"profile\":\"NATIVE_BASE\",\"target\":\"x\"}"));
        assertThrows(Exception.class, () -> worker.handle("{\"op\":\"initialize\",\"op\":\"run\",\"profile\":\"NATIVE_BASE\"}"));
        var model = (String) worker.initialize("NATIVE_BASE").get("model");
        assertThrows(IllegalStateException.class, () -> worker.initialize("NATIVE_BASE"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("NATIVE_LEARNED", "x"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("NATIVE_BASE", "(a+b)*(a-b)+b*b+3"));
        assertThrows(IllegalArgumentException.class, () -> new NativePolynomialComparisonWorker().restore("NATIVE_LEARNED",
            model.replace("POLYNOMIAL_SIMPLIFICATION_V1", "NONE")));
    }
}
