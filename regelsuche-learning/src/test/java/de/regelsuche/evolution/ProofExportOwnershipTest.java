package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.NativeExactTheoryEvidence;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProofExportOwnershipTest {
    private static final String INPUT = "x_é_𐐀";
    private static final String HEX = "cdd22faed5cee6d749ce6677b3ad9a2f51147c9afa41137cd07b7c535a1cef14";
    private static final String HASH = "sha256:" + HEX;
    private static NativeMoveProof.Exact proof;
    private static NativeMoveSearch.Result search;
    private static ExactTheoryEvidence.Binding historicalBinding;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        var restored = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        var source = parse("7*((x+y)*(x-y)+y*y)");
        var target = parse("7*x^2");
        search = new NativeMoveSearch().search(new NativeMoveSearch.Problem(source, TypedMoveSearch.Context.frozen(target),
            restored.nativeProviders(), MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(0, 1, 100_000, 10, 10_000_000)), SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED, search.observedOutcome());
        proof = assertInstanceOf(NativeMoveProof.Exact.class, search.witness().getFirst().move().proof());
        historicalBinding = proof.evidence().exportLegacy().binding();
    }

    private static Expr parse(String value) {
        return new ExpressionParser().parseExactTerm(value).expression();
    }

    private static final class Abort extends RuntimeException { }

    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        long work;
        final Abort primary = new Abort();
        final List<Set<Object>> checkpoints = new ArrayList<>();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        Predicate<Set<Object>> stopAt;
        Set<Object> failedDebit;
        RuntimeException closeFailure;

        @Override public void executionWork(long units) {
            work = Math.addExact(work, units);
            if (failedDebit != null && units == 4 && closeFailure != null) throw closeFailure;
            if (failedDebit == null && stopAt != null) {
                var live = graph(scope);
                if (stopAt.test(live)) {
                    failedDebit = live;
                    throw primary;
                }
            }
        }
        @Override public void validationWork(long units) {
            fail("export must not charge new mathematical authorization");
        }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var live = graph(scope);
            checkpoints.add(live);
            for (var value : live) if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        void assertReleased() {
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
            for (var frame : frames) assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
        }
    }

    private static Set<Object> graph(Object root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>();
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
            @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
        };
        visitor.reference(root);
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            visitReferences(value, visitor);
        }
        return seen;
    }

    private static void visitReferences(Object value, RetainedGraph.Visitor visitor) {
        switch (value) {
            case RetainedGraph.View view -> view.retainedReferences(visitor);
            case BinaryExpr binary -> { visitor.reference(binary.left()); visitor.reference(binary.right()); }
            case FunctionExpr function -> visitor.reference(function.arguments());
            case Map<?, ?> map -> map.forEach((key, item) -> { visitor.reference(key); visitor.reference(item); });
            case Collection<?> collection -> collection.forEach(visitor::reference);
            case Object[] array -> {
                for (var item : array) visitor.reference(item);
            }
            default -> { }
        }
    }

    private enum Product {
        UTF8, DIGEST, HEX_TEXT, BOUND_HASH;
        boolean present(Set<Object> live) {
            return switch (this) {
                case UTF8 -> live.stream().anyMatch(value -> value instanceof byte[] bytes
                    && Arrays.equals(INPUT.getBytes(StandardCharsets.UTF_8), bytes));
                case DIGEST -> live.stream().anyMatch(value -> value instanceof byte[] bytes
                    && Arrays.equals(HexFormat.of().parseHex(HEX), bytes));
                case HEX_TEXT -> live.stream().anyMatch(value -> HEX.equals(value));
                case BOUND_HASH -> live.stream().anyMatch(value -> HASH.equals(value));
            };
        }
    }

    @Test void hashRetainsAllCompletedProductsAndKeepsHistoricalUtf8Bytes() {
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SchematicProofPlan.hash(""));
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SchematicProofPlan.hash("abc"));
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertEquals(HASH, SchematicProofPlan.hash(INPUT));
            assertTrue(probe.checkpoints.stream().anyMatch(live -> live.contains(INPUT)
                && Arrays.stream(Product.values()).allMatch(product -> product.present(live))),
                "the input, UTF-8, digest, hex and bound hash overlap before handoff");
            assertTrue(probe.work > INPUT.length());
        }
        probe.assertReleased();
    }

    @ParameterizedTest @EnumSource(Product.class)
    void debitFailureStillObservesEachCompletedHashProduct(Product product) {
        var probe = new Probe();
        probe.stopAt = product::present;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Abort.class, () -> SchematicProofPlan.hash(INPUT)));
            assertTrue(product.present(probe.failedDebit));
            assertTrue(probe.checkpoints.stream().anyMatch(product::present),
                "an aborted work debit must not hide the completed allocation");
        }
        probe.assertReleased();
    }

    @Test void hashPreservesPrimaryAbortAcrossRepeatedAndDistinctCleanupFailures() {
        for (boolean same : List.of(true, false)) {
            var probe = new Probe();
            probe.stopAt = Product.HEX_TEXT::present;
            probe.closeFailure = same ? probe.primary : new IllegalStateException("cleanup");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Abort.class, () -> SchematicProofPlan.hash(INPUT)));
                if (!same) assertTrue(Arrays.asList(probe.primary.getSuppressed()).contains(probe.closeFailure));
            }
            probe.assertReleased();
        }
    }

    @Test void applicationCopiesStayOwnedUntilTheImmutableRecordIsHandedOff() {
        var original = assertInstanceOf(CheckedLearnedSchemaModel.ApplicationData.class, proof.evidence().binding().observation());
        var path = new ArrayList<>(original.path());
        var substitutions = new LinkedHashMap<>(original.substitutions());
        var probe = new Probe();
        CheckedLearnedSchemaModel.ApplicationData copy;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            copy = copy(original, path, substitutions);
            assertTrue(probe.checkpoints.stream().anyMatch(live -> live.contains(path) && live.contains(copy.path())
                && live.contains(substitutions) && live.contains(copy.substitutions())),
                "mutable inputs must overlap both completed immutable copies");
            assertNotSame(path, copy.path());
            assertNotSame(substitutions, copy.substitutions());
        }
        path.clear(); substitutions.clear();
        assertEquals(original, copy);
        assertEquals(original.applicationWork(), copy.applicationWork(), "copy observation is not delegated application work");
        probe.assertReleased();
    }

    @Test void applicationCopyDebitFailureKeepsTheCopiedPathAndBindingsVisible() {
        var original = assertInstanceOf(CheckedLearnedSchemaModel.ApplicationData.class, proof.evidence().binding().observation());
        for (boolean stopOnMap : List.of(false, true)) {
            var path = new ArrayList<>(original.path());
            var substitutions = new LinkedHashMap<>(original.substitutions());
            Predicate<Set<Object>> copied = live -> live.stream().anyMatch(value -> stopOnMap
                ? value instanceof RetainedSortedMap<?, ?> map && map != original.substitutions()
                    && map.size() == substitutions.size()
                    && substitutions.entrySet().stream().allMatch(entry -> map.get(entry.getKey()) == entry.getValue())
                : value instanceof List<?> list && list != path && list != original.path()
                    && list.stream().allMatch(Integer.class::isInstance) && list.equals(path));
            var probe = new Probe();
            probe.stopAt = copied;
            probe.closeFailure = probe.primary;
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Abort.class, () -> copy(original, path, substitutions)));
                assertTrue(probe.checkpoints.stream().anyMatch(copied));
            }
            probe.assertReleased();
        }
    }

    private static CheckedLearnedSchemaModel.ApplicationData copy(CheckedLearnedSchemaModel.ApplicationData data,
            List<Integer> path, Map<String, Expr> substitutions) {
        return new CheckedLearnedSchemaModel.ApplicationData(data.revision(), data.checkerRevision(), data.inventorySemanticsHash(),
            data.modelId(), data.schemaId(), data.proofHash(), data.domain(), data.source(), data.target(), path, substitutions,
            data.applicationWork(), data.modelHash());
    }

    @Test void restoredSchemaExportRetainsCanonicalJsonAndFinalBindingWithoutChangingProofBytes() {
        var probe = new Probe();
        ExactTheoryEvidence.Binding exported;
        try (var scope = RetainedOperation.open(probe); var json = RetainedJson.open()) {
            probe.scope = scope;
            exported = proof.evidence().exportLegacy().binding();
            assertTrue(probe.checkpoints.stream().anyMatch(live -> live.contains(exported)
                && live.contains(exported.canonicalEvidenceJson())), "completed binding and its actual canonical bytes are owned together");
        }
        assertEquals(historicalBinding, exported);
        assertEquals(exported.evidenceHash(), SchematicProofPlan.hash(exported.canonicalEvidenceJson()));
        assertEquals(proof.work().exactTheoryWorkUnits(), exported.canonicalWorkUnits());
        assertTrue(probe.work > exported.canonicalEvidenceJson().length());
        probe.assertReleased();
    }

    @Test void failedBindingPublicationPreservesAbortAndTheCompletedProofData() {
        var probe = new Probe();
        probe.stopAt = live -> live.stream().anyMatch(ExactTheoryEvidence.Binding.class::isInstance);
        probe.closeFailure = probe.primary;
        try (var scope = RetainedOperation.open(probe); var json = RetainedJson.open()) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Abort.class, () -> proof.evidence().exportLegacy()));
            assertTrue(probe.checkpoints.stream().anyMatch(probe.stopAt));
        }
        probe.assertReleased();
    }

    @Test void nativeBindingDebitFailureKeepsTheCompletedTypedBindingVisible() {
        var application = graph(proof.evidence()).stream()
            .filter(CheckedLearnedSchemaModel.VerifiedApplication.class::isInstance)
            .map(CheckedLearnedSchemaModel.VerifiedApplication.class::cast).findFirst().orElseThrow();
        var probe = new Probe();
        probe.stopAt = live -> live.stream().anyMatch(NativeExactTheoryEvidence.Binding.class::isInstance);
        probe.closeFailure = probe.primary;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Abort.class, application::nativeBinding));
            var binding = probe.failedDebit.stream().filter(NativeExactTheoryEvidence.Binding.class::isInstance)
                .map(NativeExactTheoryEvidence.Binding.class::cast).findFirst().orElseThrow();
            assertSame(proof.source(), binding.source());
            assertSame(proof.target(), binding.target());
            assertEquals(proof.work().exactTheoryWorkUnits(), binding.canonicalWorkUnits());
            assertTrue(probe.checkpoints.stream().anyMatch(probe.stopAt));
        }
        probe.assertReleased();
    }

    @Test void explicitSchemaExportPaysOutputWithoutChangingThePublishedSearchReceipt() {
        var receipt = search.accounting();
        long paidSearch = search.totalWork();
        var exported = search.exportLegacy(10_000_000, SearchExpressionStore.Limits.DEFAULT);
        assertTrue(exported.artifactAvailable(), exported.accounting().detail());
        assertFalse(exported.complete(), "export does not qualify the unfinished P04 search inventory");
        assertTrue(exported.accounting().work() > 0);
        assertEquals(paidSearch, search.totalWork());
        assertSame(receipt, search.accounting());
        var step = assertInstanceOf(de.regelsuche.transform.TransformationProvenance.ExactTheoryStep.class,
            exported.projection().witness().getFirst().move().provenance());
        assertEquals(historicalBinding, step.evidence().binding());
    }
}
