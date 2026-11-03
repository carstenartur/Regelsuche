package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily.Anchor;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real, independently checked laws can exhaust payload characters before the entry-count limit. */
@Timeout(180)
class RecurrencePayloadLimitTest {
    private static final Budget ACQUISITION = new Budget(500_000);
    private static List<RecurrenceKnowledge.Entry> catalog;
    private static CumulativeTaskKnowledge predecessor;
    private static String predecessorJson;
    private static Proposal invariant;

    @BeforeAll static void prepareRestorableLargeCatalog() {
        String c = BigInteger.TEN.pow(588).add(BigInteger.ONE).toString();
        // x0 and x1 are unchanged by every source below: this is a genuine degree-two invariant.
        invariant = new Proposal(c + "*x0^2 + " + c + "*x0*x1 + " + c + "*x1^2 + "
            + c + "*x0 + " + c + "*x1 + " + c, Evolution.FIXED);
        var entries = new ArrayList<RecurrenceKnowledge.Entry>();
        for (int offset = 0; offset < 26; offset++) entries.add(family(offset));
        for (int coefficient : new int[] {2, 3}) {
            var source = transition(100, coefficient);
            var checked = new RecurrenceInvariantVerifier().verify(source, invariant, values(0, 0, 0), ACQUISITION);
            assertTrue(checked.law().isPresent(), "large exact anchor must be independently valid");
            entries.add(new RecurrenceKnowledge.Entry(source, 2, checked.law().orElseThrow().proposal()));
        }
        catalog = List.copyOf(entries);
        var seeded = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory())
            .withRecurrences(catalog, CumulativeTaskKnowledge.Decision.PROMOTED, CumulativeTaskKnowledge.Costs.zero());
        predecessor = CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), seeded.toCanonicalJson());
        predecessorJson = predecessor.toCanonicalJson();
        int size = RecurrenceKnowledgeCodec.encode(catalog).length();
        assertTrue(size > 199_800 && size <= 200_000, "valid predecessor is close to the payload limit: " + size);
    }

    @Test void oversizedEncodingCannotCreateAnUnloadableSnapshot() {
        var oversized = new ArrayList<>(catalog);
        oversized.add(family(100));
        assertThrows(IllegalArgumentException.class, () -> RecurrenceKnowledgeCodec.encode(oversized));
    }

    @Test void exactLearningRetainsTheCatalogAndChargesTheRejectedAcquisition(@TempDir Path directory) throws Exception {
        var large = new Rational(BigInteger.TEN.pow(37).add(BigInteger.ONE), BigInteger.ONE);
        var source = new Transition(new ExactMatrix(List.of(values(1, 0, 0),
            List.of(Rational.ZERO, large, Rational.ZERO), List.of(Rational.ZERO, Rational.ZERO, large))),
            new ExactVector(values(0, 0, 0)));
        var lesson = new Request(source, values(1, 0, 0), 1, ACQUISITION);
        var validation = new Request(source, values(2, 0, 0), 1, ACQUISITION);
        var update = predecessor.learnRecurrences(List.of(lesson), List.of(validation), ACQUISITION);
        assertEquals(1, update.training().size());
        assertTrue(update.training().getFirst().solved(), "the rejected entry comes from actual successful synthesis");
        assertTrue(update.work().formation() > 200_000, "the complete oversized payload inspection is paid");
        assertRetainedAndRestorable(update, directory);
    }

    @Test void familyLearningRetainsTheCatalogAndChargesTheRejectedAcquisition(@TempDir Path directory) throws Exception {
        var validation = new Request(transition(100, 4), values(2, 0, 0), 2, ACQUISITION);
        var update = predecessor.generalizeRecurrences(List.of(validation), ACQUISITION);
        assertTrue(update.work().formation() > 200_000, "the proved family and complete oversized payload inspection are paid");
        assertRetainedAndRestorable(update, directory);
    }

    private static void assertRetainedAndRestorable(RecurrenceKnowledge.Update update, Path directory) throws Exception {
        assertFalse(update.promoted());
        assertEquals(CumulativeTaskKnowledge.Decision.ACQUISITION_LIMIT, update.reason());
        assertEquals(28, update.next().recurrenceCount());
        assertEquals(26, update.next().recurrenceFamilyCount());
        assertEquals(predecessor.id(), update.next().parentId());
        assertEquals(predecessor.acquisitionWork() + predecessor.sessionSetupWork() + update.work().total(),
            update.next().acquisitionWork(), "rejection must retain all previous and attempted work");
        assertTrue(update.work().total() > 0 && update.work().total() <= ACQUISITION.maxWorkUnits());
        var loaded = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), update.next().save(directory));
        assertEquals(update.next().toCanonicalJson(), loaded.toCanonicalJson(), "rejected successor must still be loadable");
        assertEquals(RecurrenceKnowledgeCodec.encode(catalog), TaskKnowledgeSnapshot.decode(loaded.toCanonicalJson()).recurrences());
        assertEquals(predecessorJson, predecessor.toCanonicalJson(), "predecessor remains immutable");
    }

    private static RecurrenceKnowledge.Entry family(int offset) {
        var checked = new RecurrenceInvariantFamily().form(new Anchor(transition(offset, 2), invariant),
            new Anchor(transition(offset, 3), invariant), 2, ACQUISITION);
        assertTrue(checked.family().isPresent(), "real universal family required: " + checked.outcome());
        return RecurrenceKnowledge.Entry.fromFamily(checked.family().orElseThrow());
    }

    private static Transition transition(int offset, int coefficient) {
        return new Transition(new ExactMatrix(List.of(values(1, 0, 0), values(0, 1, 0), values(0, 0, coefficient))),
            new ExactVector(values(0, 0, offset)));
    }

    private static List<Rational> values(long... values) {
        return java.util.Arrays.stream(values).mapToObj(Rational::of).toList();
    }
}
