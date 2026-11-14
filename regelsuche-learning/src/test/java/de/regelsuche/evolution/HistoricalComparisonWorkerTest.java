package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class HistoricalComparisonWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> EMPTY = Map.of("expressionBlocks", List.of(),
        "recurrenceBlocks", List.of(), "recurrenceFamilyValidation", List.of());
    private Map<String, Object> call(HistoricalComparisonWorker worker, Map<String, Object> request) throws Exception {
        return worker.handle(JSON.writeValueAsString(request));
    }
    private Map<String, Object> task(String kind, String source) { return Map.of("kind", kind, "source", source); }

    @Test void freezeIncludesEveryLawAndBindsExactSourceBytes() throws Exception {
        var identity = Map.of("linear", List.of(List.of("1", "0"), List.of("0", "1")),
            "offset", List.of("0", "0"), "initial", List.of("0", "1"), "degree", 2, "workBudget", 100000);
        String source = JSON.writeValueAsString(Map.of("version", 1, "cases", List.of(Map.of("id", "a", "request", identity))));
        var frozen = JSON.readTree(HistoricalComparisonWorker.freezeInvariants(source));
        assertEquals(5, frozen.required("cases").get(0).required("laws").size());
        assertEquals("COMPLETE", frozen.required("cases").get(0).required("status").asText());
        assertEquals(SchematicProofPlan.hash(source), frozen.required("sourceHash").asText());
        assertNotEquals(frozen.required("sourceHash"),
            JSON.readTree(HistoricalComparisonWorker.freezeInvariants(source + " ")).required("sourceHash"));
        var contaminated = Map.of("id", "a", "request", identity, "reference", "x0");
        String invalid = JSON.writeValueAsString(Map.of("version", 1, "cases", List.of(contaminated)));
        assertThrows(IllegalArgumentException.class, () -> HistoricalComparisonWorker.freezeInvariants(invalid));
    }

    @Test void callsTheExistingStructuralSolverForBothHistoricalGoals(@TempDir Path directory) throws Exception {
        var worker = new HistoricalComparisonWorker();
        var ready = call(worker, Map.of("op", "initialize", "profile", "NATIVE_BASE", "prelude", EMPTY, "directory", directory.toString()));
        assertEquals("INITIALIZED", ready.get("status"));
        assertEquals(0L, ((Number) ready.get("acquisitionApiWork")).longValue());
        for (var input : List.of(task("FACTOR", "u^4+4*v^4"), task("TWO_SQUARES", "(p^2+q^2)*(r^2+s^2)"))) {
            var result = call(worker, Map.of("op", "run", "task", input));
            assertEquals("RESULT", result.get("status"));
            assertEquals(input, result.get("task"));
            assertEquals(true, result.get("solved"));
            assertEquals(true, result.get("withinBudget"));
            assertFalse(((List<?>) result.get("witness")).isEmpty());
            assertTrue(((Number) result.get("nativeApiWork")).longValue() > 0);
        }
    }

    @Test void recurrenceUsesTheActualTransitionAndInitialState(@TempDir Path directory) throws Exception {
        var worker = new HistoricalComparisonWorker();
        call(worker, Map.of("op", "initialize", "profile", "NATIVE_BASE", "prelude", EMPTY, "directory", directory.toString()));
        var recurrence = Map.of("linear", List.of(List.of("0", "1"), List.of("1", "1")),
            "offset", List.of("0", "0"), "initial", List.of("2", "1"), "degree", 2, "workBudget", 100000);
        var result = call(worker, Map.of("op", "run", "task", Map.of("kind", "INVARIANT", "recurrence", recurrence)));
        assertEquals(true, result.get("solved"));
        assertEquals("5", ((Map<?, ?>) result.get("law")).get("initialValue"));
        assertEquals("ALTERNATING", ((Map<?, ?>) result.get("law")).get("evolution"));
        assertEquals(false, result.get("reused"));
    }

    @Test void learnedProducerSavesButOnlyFreshRestoredConsumerQueries(@TempDir Path directory) throws Exception {
        var prelude = Map.of("expressionBlocks", List.of(Map.of("lessons", List.of(
            task("FACTOR", "u^4+4*v^4"), task("FACTOR", "r^4+4*s^4")),
            "validation", List.of(task("FACTOR", "(a*b)^4+4*c^4")))),
            "recurrenceBlocks", List.of(), "recurrenceFamilyValidation", List.of());
        var producer = new HistoricalComparisonWorker();
        var saved = call(producer, Map.of("op", "initialize", "profile", "NATIVE_LEARNED", "prelude", prelude, "directory", directory.toString()));
        assertEquals("SAVED", saved.get("status"));
        assertTrue(((Number) saved.get("acquisitionApiWork")).longValue() > 0);
        var consumer = new HistoricalComparisonWorker();
        var restored = call(consumer, Map.of("op", "restore", "profile", "NATIVE_LEARNED", "prelude", prelude, "snapshot", saved.get("snapshot")));
        assertEquals("RESTORED", restored.get("status"));
        assertEquals(saved.get("generationId"), restored.get("generationId"));
        assertEquals(saved.get("snapshotHash"), restored.get("snapshotHash"));
        assertTrue(((Number) restored.get("restorationApiWork")).longValue() > 0);
        var query = Map.of("op", "run", "task", task("FACTOR", "(q*r)^4+4*s^4"));
        var solved = call(consumer, query);
        assertEquals(true, solved.get("solved"));
        assertEquals(true, solved.get("reused"));
        assertEquals("ERROR", call(producer, query).get("status"));
    }

    @Test void targetInjectionPoisonsTheSession(@TempDir Path directory) throws Exception {
        var worker = new HistoricalComparisonWorker();
        call(worker, Map.of("op", "initialize", "profile", "NATIVE_BASE", "prelude", EMPTY, "directory", directory.toString()));
        var bad = Map.of("op", "run", "task", Map.of("kind", "FACTOR", "source", "x^4+4*y^4", "target", "known answer"));
        assertEquals("ERROR", call(worker, bad).get("status"));
        assertEquals("ERROR", call(worker, Map.of("op", "run", "task", task("FACTOR", "x^4+4*y^4"))).get("status"));
    }

    @Test void preludeBindingIsIndependentOfJsonObjectFieldOrder(@TempDir Path directory) throws Exception {
        var reversed = new java.util.LinkedHashMap<String, Object>();
        reversed.put("recurrenceFamilyValidation", List.of()); reversed.put("recurrenceBlocks", List.of());
        reversed.put("expressionBlocks", List.of());
        var response = call(new HistoricalComparisonWorker(), Map.of("op", "initialize", "profile", "NATIVE_BASE",
            "prelude", reversed, "directory", directory.toString()));
        var ordered = call(new HistoricalComparisonWorker(), Map.of("op", "initialize", "profile", "NATIVE_BASE",
            "prelude", new java.util.TreeMap<>(EMPTY), "directory", directory.toString()));
        assertEquals(ordered.get("preludeHash"), response.get("preludeHash"));
    }

    @Test void teachingHashUsesTheSharedCanonicalJsonLineContract(@TempDir Path directory) throws Exception {
        var response = call(new HistoricalComparisonWorker(), Map.of("op", "initialize", "profile", "NATIVE_BASE",
            "prelude", EMPTY, "directory", directory.toString()));
        // Same UTF-8 canonical JSON line (including LF) that the Python supervisor sends/audits.
        assertEquals(SchematicProofPlan.hash("{\"expressionBlocks\":[],\"recurrenceBlocks\":[],\"recurrenceFamilyValidation\":[]}\n"),
            response.get("preludeHash"));
    }
}
