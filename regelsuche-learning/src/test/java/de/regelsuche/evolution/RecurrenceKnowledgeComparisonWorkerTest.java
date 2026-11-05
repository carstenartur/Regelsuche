package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(90)
class RecurrenceKnowledgeComparisonWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REVISION = "regelsuche.recurrence-knowledge-worker/v1";
    @TempDir Path directory;

    @Test void realBaselineReportsItsTaskLawAndImmutableGeneration() throws Exception {
        var worker = worker();
        var initialized = send(worker, Map.of("command", "initialize", "profile", "BASELINE"));
        common(initialized, "INITIALIZED", "BASELINE");
        assertEquals(0L, number(initialized, "acquisitionWork"));
        var task = task("7", "3", "4");
        var result = send(worker, Map.of("command", "run", "id", "unseen", "task", task));
        common(result, "RESULT", "BASELINE");
        assertEquals(task, result.get("task"));
        assertEquals("unseen", result.get("id"));
        assertEquals("VERIFIED", result.get("outcome"));
        assertEquals(true, result.get("solved"));
        assertEquals(false, result.get("reused"));
        assertTrue(number(result, "totalWork") > 0);
        assertEquals(initialized.get("generationId"), result.get("generationId"));
        var law = (Map<?, ?>) result.get("law");
        assertEquals(Set.of("polynomial", "evolution", "initialValue"), law.keySet());
        assertEquals("ALTERNATING", law.get("evolution"));
    }

    @Test void acquiresGeneralizesSavesAndRestoresWithoutQueryMutation() throws Exception {
        var producer = seeded("FAMILY");
        var general = send(producer, Map.of("command", "generalize", "validation",
            List.of(task("3", "2", "3"), task("5", "3", "4")), "budget", 500_000));
        common(general, "GENERALIZED", "FAMILY");
        assertEquals(true, general.get("promoted"));
        assertEquals(1L, number(general, "recurrenceFamilyCount"));
        assertEquals(List.of(), general.get("training"));
        assertTrue(((Map<?, ?>) general.get("work")).containsKey("total"));
        var saved = send(producer, Map.of("command", "save", "directory", directory.toString()));
        common(saved, "SAVED", "FAMILY");
        var snapshot = Path.of((String) saved.get("snapshot"));
        assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(snapshot))), saved.get("snapshotHash"));
        var consumer = worker();
        var restored = send(consumer, Map.of("command", "restore", "profile", "FAMILY", "snapshot", snapshot.toString()));
        common(restored, "RESTORED", "FAMILY");
        assertEquals(saved.get("snapshotHash"), restored.get("snapshotHash"));
        assertEquals(saved.get("generationId"), restored.get("generationId"));
        assertTrue(number(restored, "sessionSetupWork") > 0);
        var result = send(consumer, Map.of("command", "run", "id", "r", "task", task("7", "3", "4")));
        assertEquals(true, result.get("reused"));
        assertEquals(true, result.get("solved"));
        assertEquals(saved.get("generationId"), result.get("generationId"));
        assertEquals(restored.get("acquisitionCosts"), result.get("acquisitionCosts"));
        assertEquals("77", ((Map<?, ?>) result.get("law")).get("initialValue").toString().replace("-", ""));
        error(send(consumer, Map.of("command", "learn", "training", List.of(task("1", "0", "1")),
            "validation", List.of(task("1", "2", "3")), "budget", 500_000)));
        error(send(producer, Map.of("command", "run", "id", "r", "task", task("7", "3", "4"))));
    }

    @Test void acquisitionCutoffIsPaidAndDoesNotBecomeTechnicalError() throws Exception {
        var producer = worker();
        send(producer, Map.of("command", "initialize", "profile", "FAMILY"));
        var learned = lesson(producer, "1", 20);
        common(learned, "LEARNED", "FAMILY");
        assertEquals(false, learned.get("promoted"));
        assertEquals("ACQUISITION_LIMIT", learned.get("reason"));
        assertTrue(number(learned, "acquisitionWork") > 0);
        assertTrue(number(learned, "acquisitionWork") <= 20);
        assertEquals(0L, number(learned, "recurrenceCount"));
        assertTrue(((Map<?, ?>) learned.get("work")).containsKey("total"));
    }

    @Test void exactFrozenDoesNotGeneralizeAndTamperedSnapshotPoisonsConsumer() throws Exception {
        var exact = seeded("EXACT_FROZEN");
        var saved = send(exact, Map.of("command", "save", "directory", directory.toString()));
        var path = Path.of((String) saved.get("snapshot"));
        Files.writeString(path, Files.readString(path).replace("ALTERNATING", "FIXED"));
        var consumer = worker();
        error(send(consumer, Map.of("command", "restore", "profile", "EXACT_FROZEN", "snapshot", path.toString())));
        error(send(consumer, Map.of("command", "initialize", "profile", "BASELINE")));
        var other = seeded("EXACT_FROZEN");
        error(send(other, Map.of("command", "generalize", "validation", List.of(task("3", "2", "3")), "budget", 500_000)));
    }

    @Test void strictRequestsRejectProfilesStatesDuplicatesAndTrailingTokens() throws Exception {
        for (var request : List.of("{\"command\":\"initialize\",\"profile\":\"UNKNOWN\"}",
                "{\"command\":\"initialize\",\"profile\":\"BASELINE\",\"target\":1}",
                "{\"command\":\"initialize\",\"profile\":\"BASELINE\",\"profile\":\"FAMILY\"}",
                "{\"command\":\"initialize\",\"profile\":\"BASELINE\"} {}", "[]", "null")) {
            var worker = worker();
            error(sendLine(worker, request));
            error(send(worker, Map.of("command", "initialize", "profile", "BASELINE")));
        }
        var fresh = worker();
        error(send(fresh, Map.of("command", "run", "id", "r", "task", task("7", "3", "4"))));
        var baseline = worker();
        send(baseline, Map.of("command", "initialize", "profile", "BASELINE"));
        error(lesson(baseline, "1", 500_000));
        var initialized = worker();
        send(initialized, Map.of("command", "initialize", "profile", "BASELINE"));
        error(send(initialized, Map.of("command", "initialize", "profile", "BASELINE")));
    }

    @Test void taskConversionRequiresBoundedCanonicalRationalsAndExactFields() throws Exception {
        for (var invalid : List.of("01", "2/4", "1/0", "1.0", "1/1", "9".repeat(100))) {
            var task = task(invalid, "3", "4");
            var worker = worker();
            send(worker, Map.of("command", "initialize", "profile", "BASELINE"));
            error(send(worker, Map.of("command", "run", "id", "r", "task", task)));
        }
        var worker = worker();
        send(worker, Map.of("command", "initialize", "profile", "BASELINE"));
        var task = new java.util.HashMap<>(task("1/2", "3", "4"));
        task.put("target", "forbidden");
        error(send(worker, Map.of("command", "run", "id", "r", "task", task)));
    }

    @Test void rationalQueriesNegativeControlsAndBudgetCutoffsPreserveHonestOutcomes() throws Exception {
        var baseline = worker();
        var initialized = send(baseline, Map.of("command", "initialize", "profile", "BASELINE"));
        var rational = send(baseline, Map.of("command", "run", "id", "rational", "task", task("1/2", "3", "4")));
        assertEquals(true, rational.get("solved"));
        var negative = Map.of("linear", List.of(List.of("2")), "offset", List.of("0"),
            "initial", List.of("1"), "degree", 2, "workBudget", 100_000);
        var rejected = send(baseline, Map.of("command", "run", "id", "negative", "task", negative));
        assertEquals("NO_LAW_IN_ANSATZ", rejected.get("outcome"));
        assertEquals(false, rejected.get("solved"));
        assertEquals(false, rejected.get("reused"));
        assertNull(rejected.get("law"));
        var cutoff = new java.util.HashMap<>(task("7", "3", "4"));
        cutoff.put("workBudget", 0);
        var limited = send(baseline, Map.of("command", "run", "id", "limited", "task", cutoff));
        assertEquals("BUDGET_INCONCLUSIVE", limited.get("outcome"));
        assertEquals(0L, number(limited, "totalWork"));
        assertNull(limited.get("law"));
        assertEquals(initialized.get("generationId"), limited.get("generationId"));
    }

    @Test void producerCannotRunAndExactConsumerCannotBorrowAFamilyProfile() throws Exception {
        var producer = worker();
        send(producer, Map.of("command", "initialize", "profile", "FAMILY"));
        error(send(producer, Map.of("command", "run", "id", "early", "task", task("7", "3", "4"))));
        var family = seeded("FAMILY");
        send(family, Map.of("command", "generalize", "validation", List.of(task("3", "2", "3")), "budget", 500_000));
        var saved = send(family, Map.of("command", "save", "directory", directory.toString()));
        error(send(worker(), Map.of("command", "restore", "profile", "EXACT_FROZEN", "snapshot", saved.get("snapshot"))));
        error(send(worker(), Map.of("command", "restore", "profile", "BASELINE", "snapshot", saved.get("snapshot"))));
    }

    @Test void boundedIntegerAndMatrixFieldsRejectInvalidTypesAndShapes() throws Exception {
        for (var change : List.of(Map.of("degree", 2.5), Map.of("degree", 3), Map.of("workBudget", 1L << 32),
                Map.of("workBudget", -1), Map.of("linear", List.of(List.of("0", "1"))),
                Map.of("offset", List.of("0")), Map.of("initial", List.of(3, 4)))) {
            var task = new java.util.HashMap<>(task("7", "3", "4"));
            task.putAll(change);
            var baseline = worker();
            send(baseline, Map.of("command", "initialize", "profile", "BASELINE"));
            error(send(baseline, Map.of("command", "run", "id", "invalid", "task", task)));
        }
    }

    private static RecurrenceKnowledgeComparisonWorker worker() { return new RecurrenceKnowledgeComparisonWorker(); }
    private static Map<String, Object> sendLine(RecurrenceKnowledgeComparisonWorker worker, String line) throws IOException {
        return worker.handle(line);
    }
    private static Map<String, Object> send(RecurrenceKnowledgeComparisonWorker worker, Map<String, ?> request) throws Exception {
        return sendLine(worker, JSON.writeValueAsString(request));
    }
    private static Map<String, Object> task(String p, String a, String b) {
        return Map.of("linear", List.of(List.of("0", "1"), List.of("1", p)), "offset", List.of("0", "0"),
            "initial", List.of(a, b), "degree", 2, "workBudget", 100_000);
    }
    private static Map<String, Object> lesson(RecurrenceKnowledgeComparisonWorker worker, String p, int budget) throws Exception {
        return send(worker, Map.of("command", "learn", "training", List.of(task(p, "0", "1")),
            "validation", List.of(task(p, "2", "3")), "budget", budget));
    }
    private static RecurrenceKnowledgeComparisonWorker seeded(String profile) throws Exception {
        var worker = worker();
        send(worker, Map.of("command", "initialize", "profile", profile));
        for (var p : List.of("1", "2")) {
            var update = lesson(worker, p, 500_000);
            common(update, "LEARNED", profile);
            assertEquals(true, update.get("promoted"));
            var training = (List<?>) update.get("training");
            assertEquals(1, training.size());
            assertEquals(Set.of("outcome", "solved", "reused", "totalWork"), ((Map<?, ?>) training.getFirst()).keySet());
        }
        return worker;
    }
    private static long number(Map<String, Object> response, String key) { return ((Number) response.get(key)).longValue(); }
    private static void error(Map<String, Object> response) {
        assertEquals("ERROR", response.get("status"));
        assertEquals(false, response.get("accountingComplete"));
        assertNotNull(response.get("error"));
    }
    private static void common(Map<String, Object> response, String status, String profile) {
        assertEquals(status, response.get("status"), response::toString);
        assertEquals(REVISION, response.get("revision"));
        assertEquals(profile, response.get("profile"));
        assertFalse(((String) response.get("generationId")).isBlank());
        assertTrue(number(response, "acquisitionWork") >= 0);
        assertTrue(number(response, "sessionSetupWork") >= 0);
        assertTrue(number(response, "recurrenceCount") >= 0);
        assertTrue(number(response, "recurrenceFamilyCount") >= 0);
        var costs = (Map<?, ?>) response.get("acquisitionCosts");
        assertEquals(Set.of("lessons", "formation", "preparation", "validation", "selection", "restoration", "splitChecking", "total"), costs.keySet());
        assertEquals(number(response, "acquisitionWork"), ((Number) costs.get("total")).longValue());
    }
}
