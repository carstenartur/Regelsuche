package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.NativeExactTheoryEvidence;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CheckedSchemaEvidenceWrapperOwnershipTest {
    private static CheckedLearnedSchemaModel.VerifiedApplication issued;
    private static NativeExactTheoryEvidence nativeEvidence;
    private static ExactTheoryEvidence.Binding historical;

    @BeforeAll static void prepareRealApplication() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(),TraceStrategyTransferExample.limits());
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr source = new ExpressionParser().parseTerm("(x+y)*(x-y)+y*y");
        Expr target = new ExpressionParser().parseTerm("x^2");
        var context = TypedMoveSearch.Context.frozen(target);
        var batch = model.nativeProviders().getFirst().candidates(
            new TypedMoveSearch.State(source,0,0,"",List.of(),Set.of(),0),context);
        var move = batch.moves().stream().filter(value -> value.targetExpression().equals(target)).findFirst().orElseThrow();
        nativeEvidence = ((NativeMoveProof.Exact)move.proof()).evidence();
        nativeEvidence.retainedReferences(new RetainedGraph.Visitor() {
            @Override public void reference(Object value) {
                if (value instanceof CheckedLearnedSchemaModel.VerifiedApplication application) issued = application;
            }
            @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
        });
        assertNotNull(issued,"use the actual privately issued capability reached by the public ownership view");
        historical = ExactTheoryEvidence.fromVerified(issued).binding();
    }
    private static final class HandoffAbort extends RuntimeException { }
    private static final class CleanupAbort extends RuntimeException { }
    private enum Stop { NONE, OPTIONAL, EVIDENCE }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        RetainedJson.Scope json;
        Stop stop = Stop.NONE;
        boolean nativeOptional, legacyOptional, applicationOverlap, finalOverlap, evidenceReceiptOverlap, failedOwner;
        boolean repeatPrimary, failCleanup;
        HandoffAbort failure;
        final CleanupAbort cleanup = new CleanupAbort();
        long work;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); visitor.reference(json); }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void executionWork(long units) {
            work += units;
            if (failure != null) {
                if (units == 4 && repeatPrimary) throw failure;
                if (units == 4 && failCleanup) throw cleanup;
                return;
            }
            if (scope == null) return;
            var current = snapshot();
            if ((stop == Stop.OPTIONAL && current.optional()) || (stop == Stop.EVIDENCE && current.evidence())) {
                failure = new HandoffAbort(); throw failure;
            }
        }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var current = snapshot();
            nativeOptional |= current.nativeOptional(); legacyOptional |= current.legacyOptional();
            applicationOverlap |= current.optional() && current.issued();
            finalOverlap |= current.evidence() && current.legacyOptional();
            evidenceReceiptOverlap |= current.evidence() && current.receipt() && current.verification();
            failedOwner |= failure != null && (stop == Stop.OPTIONAL ? current.optional() : current.evidence());
        }
        private Snapshot snapshot() {
            var objects = graph(scope); boolean nativeBinding = false, legacyBinding = false, evidence = false, receipt = false, verification = false;
            for (Object value : objects) {
                if (value instanceof Optional<?> optional) {
                    nativeBinding |= optional.orElse(null) instanceof NativeExactTheoryEvidence.Binding;
                    legacyBinding |= optional.orElse(null) instanceof ExactTheoryEvidence.Binding;
                }
                evidence |= value instanceof ExactTheoryEvidence;
                receipt |= value instanceof String text && text.startsWith("checked-schema-application:");
                verification |= value instanceof MoveVerifier.Verification;
            }
            return new Snapshot(nativeBinding,legacyBinding,evidence,receipt,verification,objects.contains(issued));
        }
    }
    private record Snapshot(boolean nativeOptional,boolean legacyOptional,boolean evidence,boolean receipt,boolean verification,boolean issued) {
        boolean optional() { return nativeOptional || legacyOptional; }
    }
    private static Set<Object> graph(Object root) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var queue = new ArrayDeque<Object>(); if (root != null) queue.add(root);
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) queue.add(value); }
            @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
        };
        while (!queue.isEmpty()) {
            Object value = queue.remove(); if (!seen.add(value)) continue;
            if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
            else if (value instanceof Optional<?> optional) optional.ifPresent(visitor::reference);
            else if (value instanceof Object[] values) for (Object item : values) visitor.reference(item);
            else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
            else if (value instanceof Map<?,?> values) values.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
        }
        return seen;
    }
    @Test void nativeProviderOwnsItsActualOptionalAndIssuedApplication() {
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = new CheckedSchemaTheoryEvidenceProvider().bindNative(issued).orElseThrow();
            assertEquals(nativeEvidence.binding(),result);
        }
        assertTrue(observation.nativeOptional); assertTrue(observation.applicationOverlap);
        assertReleased(observation);
    }
    @Test void legacyProviderOwnsItsActualOptionalWithoutChangingEvidenceBytes() {
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            try (var json = RetainedJson.open()) {
                observation.json = json;
                assertEquals(historical,new CheckedSchemaTheoryEvidenceProvider().bind(issued).orElseThrow());
            }
        }
        assertTrue(observation.legacyOptional); assertTrue(observation.applicationOverlap);
        assertReleased(observation);
    }
    @Test void installedFactoryKeepsAcceptedBindingOptionalAndFinalCapabilityTogether() {
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            try (var json = RetainedJson.open()) {
                observation.json = json;
                var evidence = ExactTheoryEvidence.fromVerified(issued);
                assertEquals(historical,evidence.binding());
            }
        }
        assertTrue(observation.finalOverlap,"the factory owns its selected binding and returned Optional through capability publication");
        assertReleased(observation);
    }
    @Test void exactVerificationOwnsEvidenceThroughReceiptAndVerificationPublication() {
        var input = new NativeVerification(true,31,new NativeMoveProof.Exact(nativeEvidence),"checked-rule","REPLAYED");
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            try (var json = RetainedJson.open()) {
                observation.json = json;
                var result = input.exportLegacy();
                assertEquals(new MoveVerifier.Verification(true,31,List.of("checked-schema-application:"+historical.evidenceHash()),"REPLAYED"),result);
            }
        }
        assertTrue(observation.evidenceReceiptOverlap,"the actual exported capability overlaps its receipt and final verification");
        assertReleased(observation);
    }
    @ParameterizedTest @CsvSource({"OPTIONAL,false","OPTIONAL,true","EVIDENCE,false","EVIDENCE,true"})
    void failedProviderAndFactoryHandoffsKeepPrimaryErrorsAndReleaseTheirFrames(Stop stop,boolean sameFailure) {
        var observation = new Observation(); observation.stop = stop;
        observation.repeatPrimary = sameFailure; observation.failCleanup = !sameFailure;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            try (var json = RetainedJson.open()) {
                observation.json = json;
                var failure = assertThrows(HandoffAbort.class,() -> ExactTheoryEvidence.fromVerified(issued));
                assertSame(observation.failure,failure); assertTrue(observation.failedOwner);
                if (!sameFailure) assertTrue(Arrays.asList(failure.getSuppressed()).contains(observation.cleanup));
                observation.repeatPrimary = false; observation.failCleanup = false;
                assertEquals(historical,ExactTheoryEvidence.fromVerified(issued).binding());
            }
        }
        assertReleased(observation);
    }
    @Test void nativeOptionalDebitFailureDoesNotLeaveAnOwnedPartialCapability() {
        var observation = new Observation(); observation.stop = Stop.OPTIONAL; observation.repeatPrimary = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(HandoffAbort.class,() -> new CheckedSchemaTheoryEvidenceProvider().bindNative(issued));
            assertSame(observation.failure,failure); assertTrue(observation.failedOwner);
            observation.repeatPrimary = false;
            assertEquals(0,RetainedGraph.measure(scope).retained().nodes());
            assertTrue(new CheckedSchemaTheoryEvidenceProvider().bindNative(issued).isPresent());
        }
        assertReleased(observation);
    }
    @Test void publicDescriptionsRemainUnauthorizedAndEveryHashFieldIsStillValidated() {
        var provider = new CheckedSchemaTheoryEvidenceProvider();
        assertTrue(provider.bind(historical).isEmpty()); assertTrue(provider.bindNative(historical).isEmpty());
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(IllegalArgumentException.class,() -> ExactTheoryEvidence.fromVerified(historical));
            assertThrows(IllegalArgumentException.class,() -> ExactTheoryEvidence.fromVerified(new Object()));
        }
        assertFalse(observation.finalOverlap); assertReleased(observation);
        for (int index = 0; index < 3; index++) {
            for (String bad : Arrays.asList(null,"sha256:bad","sha256:"+"F".repeat(64))) {
                String[] hashes = {historical.evidenceHash(),historical.receiptArtifactId(),historical.runArtifactId()};
                hashes[index] = bad;
                assertThrows(IllegalArgumentException.class,() -> new ExactTheoryEvidence.Binding(
                    historical.sourceExpression(),historical.transformedExpression(),historical.theoryStepId(),hashes[0],hashes[1],hashes[2],
                    historical.canonicalWorkUnits(),historical.canonicalEvidenceJson()));
            }
        }
    }
    private static void assertReleased(Observation observation) {
        var retained = RetainedGraph.measure(observation.scope).retained();
        assertEquals(0,retained.nodes()); assertEquals(0,retained.characters());
    }
}
