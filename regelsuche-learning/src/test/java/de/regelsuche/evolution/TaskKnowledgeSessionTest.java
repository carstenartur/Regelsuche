package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskKnowledgeSessionTest {
    private static CumulativeTaskKnowledge learned;
    @BeforeAll static void train() { learned = CumulativeTaskKnowledgeContract.generations(); }

    static CheckedSchemaTaskSolver.Task task(String source, int nodes) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), new MoveSearch.Budget(32, 32, 200_000, 512, 200_000));
    }

    @Test void indexedPreparationPreservesProofsIdentityAndSuccessors() {
        var inventory = TraceStrategyTransferExample.inventory();
        var prepared = CumulativeTaskKnowledge.restorePrepared(inventory, learned.toCanonicalJson());
        assertTrue(prepared.usesIndexedPreparation());
        assertEquals(learned.id(), prepared.id());
        assertEquals(learned.toCanonicalJson(), prepared.toCanonicalJson());
        assertTrue(prepared.sessionSetupWork() > 0);
        var query = task(CumulativeTaskKnowledgeContract.nested("r+s", 3, "t"), 9);
        var original = learned.solve(query, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
        var actual = prepared.solve(query, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
        assertTrue(original.solved()); assertTrue(actual.solved());
        new ExactPolynomialAnalysis().requireEquivalent(ExpressionFormatter.format(query.source()),
            ExpressionFormatter.format(actual.solution().orElseThrow().evidence().incumbent().expression()));
        var rejected = prepared.learnBatch(List.of(task("x+13", 1), task("y+17", 1)), List.of(task("z+19", 1)));
        assertFalse(rejected.promoted());
        assertTrue(rejected.next().usesIndexedPreparation());
        assertEquals(prepared.schemaCount(), rejected.next().schemaCount());
        assertFalse(CumulativeTaskKnowledge.restore(inventory, learned.toCanonicalJson()).usesIndexedPreparation());
        assertTrue(CumulativeTaskKnowledge.emptyPrepared(inventory, TaskConstructionProfile.NONE).usesIndexedPreparation());
    }

    @Test void repeatedLoadsReuseOnlyVerifiedCanonicalKnowledge(@TempDir Path directory) throws Exception {
        Path path = learned.save(directory);
        try (var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 2, 1_000_000)) {
            var first = session.prepare(path);
            var again = session.prepare(path);
            assertFalse(first.reused()); assertTrue(again.reused());
            assertSame(first.knowledge(), again.knowledge());
            assertTrue(first.preparationWork() > 0); assertEquals(0, again.preparationWork());
            assertTrue(again.lookupWork() > 0);
            assertEquals(2, session.statistics().loads()); assertEquals(1, session.statistics().hits());
            assertEquals(first.preparationWork(), session.statistics().preparationWork());
            Files.writeString(path, "{}");
            assertThrows(java.io.IOException.class, () -> session.prepare(path));
            assertEquals(1, session.statistics().failures());
            assertFalse(session.statistics().accountingComplete());
        }
    }

    @Test void recomputedAddressCannotAuthorizeFalseOrForeignMathematics(@TempDir Path directory) throws Exception {
        try (var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 2, 1_000_000)) {
            session.prepare(learned.save(directory));
            var json = CheckedSchemaSupport.JSON;
            var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(learned.toCanonicalJson());
            var model = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(snapshot.path("model").textValue());
            ((com.fasterxml.jackson.databind.node.ObjectNode) model.path("schemas").get(0)).set("target", json.readTree("[\"N\",\"0\"]"));
            snapshot.put("model", model.toString());
            String falseModel = snapshot.toString();
            Path forged = TaskKnowledgeSnapshot.save(directory, SchematicProofPlan.hash(falseModel), falseModel);
            assertThrows(IllegalArgumentException.class, () -> session.prepare(forged));
            String foreign = learned.toCanonicalJson().replace(TraceStrategyTransferExample.inventory().contentHash(),
                "sha256:" + "0".repeat(64));
            assertNotEquals(learned.toCanonicalJson(), foreign);
            Path foreignPath = TaskKnowledgeSnapshot.save(directory, SchematicProofPlan.hash(foreign), foreign);
            assertThrows(IllegalArgumentException.class, () -> session.prepare(foreignPath));
            assertEquals(0, session.statistics().hits());
            assertEquals(1, session.statistics().retainedGenerations());
        }
    }

    @Test void cachedKnowledgeDoesNotBypassFileOrSymlinkChecks(@TempDir Path directory) throws Exception {
        Path path = learned.save(directory.resolve("original"));
        try (var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 2, 1_000_000)) {
            session.prepare(path);
            Path link = Files.createDirectories(directory.resolve("alias")).resolve(path.getFileName());
            Files.createSymbolicLink(link, path);
            assertThrows(java.io.IOException.class, () -> session.prepare(link));
            assertEquals(0, session.statistics().hits());
        }
    }

    @Test void lruCapacityEvictsAndOversizedGenerationsRemainUncached(@TempDir Path directory) throws Exception {
        Path first = learned.save(directory);
        Path second = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory()).save(directory);
        try (var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 1, 1_000_000)) {
            session.prepare(first); session.prepare(second);
            assertFalse(session.prepare(first).reused());
            assertEquals(2, session.statistics().evictions());
            assertEquals(1, session.statistics().retainedGenerations());
        }
        try (var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 1, 1)) {
            assertFalse(session.prepare(first).reused());
            assertFalse(session.prepare(first).reused());
            assertEquals(0, session.statistics().retainedGenerations());
            assertEquals(0, session.statistics().retainedCharacters());
            assertTrue(session.statistics().preparationWork() > 0);
        }
    }

    @Test void closeDropsRetainedReferencesAndPreventsMoreLoads(@TempDir Path directory) throws Exception {
        var session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 1, 1_000_000);
        Path path = learned.save(directory); session.prepare(path);
        session.close(); session.close();
        assertEquals(0, session.statistics().retainedGenerations());
        assertEquals(0, session.statistics().retainedCharacters());
        assertThrows(IllegalStateException.class, () -> session.prepare(path));
    }
}
