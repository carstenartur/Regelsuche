package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaRetentionTest {
    private static CheckedLearnedSchemaModel historical;
    private static CheckedLearnedSchemaModel fresh;
    private static CumulativeTaskKnowledge promoted;

    @BeforeAll static void acquireIndependentLibraries() {
        var genome = TraceStrategyTransferExample.inventory();
        var traces = new TraceRewriteStrategyLearner().learn(genome,
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        historical = CheckedLearnedSchemaModel.learn(traces);
        var update = CumulativeTaskKnowledge.empty(genome).learnLocalBatch(
            PairedTacticTraining.lessons(), PairedTacticTraining.gates(), 1_000_000);
        assertTrue(update.promoted());
        promoted = update.next();
        fresh = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(update.next().toCanonicalJson()).model(),
            genome.contentHash());
    }

    @Test void preservesBothAcquisitionsAndRechecksTheSerializedUnion() {
        var union = historical.retainWith(fresh);
        var retained = union.model();
        assertTrue(retained.schemas().size() > Math.max(historical.schemas().size(), fresh.schemas().size()));
        for (var source : List.of(historical, fresh)) {
            for (var schema : source.schemas()) {
                var copy = retained.schemas().stream().filter(s -> s.id().equals(schema.id())).findFirst().orElseThrow();
                assertEquals(schema.proofHash(), copy.proofHash());
                assertTrue(copy.supportingObservationIds().containsAll(schema.supportingObservationIds()));
            }
        }
        assertTrue(union.omittedSchemaIds().isEmpty());
        assertTrue(union.work() > retained.toCanonicalJson().length());
        assertEquals(List.of(historical.identity(), fresh.identity()), retained.attempts().getFirst().observationIds());
        assertEquals(retained.toCanonicalJson(), CheckedLearnedSchemaModel
            .load(retained.toCanonicalJson(), retained.inventoryHash()).toCanonicalJson());
        assertSame(historical, historical.retainWith(historical).model());
        assertEquals(fresh.toCanonicalJson(), promoted.retainSchemasWith(promoted).model().toCanonicalJson());
        assertThrows(IllegalArgumentException.class, () -> historical.retainWith(fresh.requiring(List.of("condition"))));
        assertThrows(IllegalArgumentException.class, () -> historical.retainWith(
            CheckedLearnedSchemaModel.empty(SchematicProofPlan.hash("unrelated inventory"))));
    }

    @Test void incumbentHasPriorityAndNewCorroborationIsBounded() {
        var a = historical.schemas().getFirst();
        var b = fresh.schemas().stream().filter(rule -> !rule.id().equals(a.id())).findFirst().orElseThrow();
        var first = isolated(historical, a.id(), List.of("old-lesson"));
        var second = isolated(fresh, b.id(), List.of("another-lesson"));
        var full = first.retainWith(second);
        assertEquals(List.of(b.id()), full.omittedSchemaIds());
        assertEquals(List.of(a.id()), full.model().schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).toList());
        var corroborated = first.retainWith(isolated(historical, a.id(), List.of("fresh-lesson")));
        assertEquals(List.of("fresh-lesson", "old-lesson"), corroborated.model().schemas().getFirst().supportingObservationIds());
        assertThrows(IllegalArgumentException.class, () -> historical.retainWith(first));
        var observations = new ArrayList<String>();
        for (int i = 0; i < 256; i++) observations.add("trace-" + i);
        var crowded = isolated(historical, a.id(), observations);
        assertThrows(IllegalArgumentException.class, () -> crowded.retainWith(first));
    }

    // Reuse a genuinely derived statement. These tests vary only the descriptive
    // origin labels and capacity. The normal loader rechecks the statement each time.
    private static CheckedLearnedSchemaModel isolated(CheckedLearnedSchemaModel original, String id, List<String> origins) {
        var root = (ObjectNode) CheckedSchemaSupport.read(original.toCanonicalJson());
        var schema = (ObjectNode) root.get("schemas").findParents("id").stream()
            .filter(node -> node.get("id").asText().equals(id)).findFirst().orElseThrow();
        var values = schema.putArray("supportingObservationIds");
        new TreeSet<>(origins).forEach(values::add);
        root.putArray("schemas").add(schema);
        ((ObjectNode) root.get("bounds")).put("maximumSchemas", 1);
        return CheckedLearnedSchemaModel.load(CheckedSchemaSupport.write(root), original.inventoryHash());
    }
}
